package icwbe;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import mrtjp.core.vec.Size;
import mrtjp.projectred.fabrication.BundledCableICPart;
import mrtjp.projectred.fabrication.IntegratedCircuit;
import mrtjp.projectred.fabrication.TileICWorkbench;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * 蓝图（.icbp）文件的读写。
 *
 * <h3>文件格式</h3>
 * 就是 {@code IntegratedCircuit.save(NBTTagCompound)} 的产物，再用
 * {@code CompressedStreamTools}（gzip）落到磁盘上。键名全部沿用原版：
 * {@code name / sw / sh / iost / parts}，每个零件是 {@code {id, xpos, ypos, ...零件自己的字段}}。
 *
 * <p>这样选有两个好处：
 * <ul>
 *   <li>{@code ic.save(tag)} / {@code ic.load(tag)} 天然是一对，永不失配；</li>
 *   <li>和 tile 的存档、ProjRed 蓝图物品用的是<b>同一套 NBT</b>，
 *       所以仓库里现成的任何 IC NBT 片段丢进 {@code blueprints/} 都能被直接打开。</li>
 * </ul>
 *
 * <p>目录固定在 {@code .minecraft/blueprints/}。文件是普通 gzip 文件，
 * 想分享就直接拷给别人，想手改就用任意 NBT 编辑器。
 *
 * <h3>加载如何同步</h3>
 * 和撤销一样：{@code ic.load(tag)} 是就地重建（内部 {@code clear()} + {@code setPart_do}，
 * 不发包），紧跟一次 {@code tile.sendNewICToServer(ic)} 走原版 stream 5 全量同步。
 * 服务端 {@code TileICWorkbench.read(in, 5)} 会 {@code circuit().readDesc(in)} 整块替换。
 * 协议是原版的，所以服务端不需要装本模组。
 *
 * <h3>0.3.8：这个类为什么被 ICWbEx 接管</h3>
 * 它原本在底座 jar 里，但保存路径上出了两次事故，而它又是<b>所有写文件的唯一出口</b>
 * （面板的"保存"按钮、名称框回车、分享码导入落盘，全都经过 {@link #save}），
 * 所以从 0.3.8 起由本项目自带一份（签名与底座完全一致，行为向后兼容）。改了两点：
 * <ol>
 *   <li><b>绝不给"半同步状态下的板子"拍照</b>。两阶段提交期间（见 {@link Sync}）
 *       本地板会被回滚成"编辑前的那张板"，好让服务器还在发的状态帧都能找到自己的零件。
 *       这段窗口里如果有人保存，写进文件的就是<b>上一张板</b>——2026-10-01 就是这么把
 *       用户刚导入的好蓝图覆盖成旧板的（文件 mtime 精确落在切换那一秒，
 *       内容与另一张蓝图逐格相同，内部 name 却是本文件名：典型的"用名称框里的名字
 *       保存了回滚后的板"）。现在 {@link Sync#boardBeingSynced} 会把"这次同步真正要落地的板"
 *       交出来，保存它就对了。</li>
 *   <li><b>覆盖前先留底</b>：任何一次覆盖都会把被覆盖的那份拷进
 *       {@code icwbe_blueprint_backups/session-<启动时刻>/}（同一会话里同名文件只留最早那份）。
 *       加上 {@link Replay#backupBlueprintsOnce()} 的会话开局全量快照，
 *       误覆盖最多损失"一次会话内的第一次改动"。</li>
 * </ol>
 */
public final class Blueprints {

    public static final String EXT = ".icbp";

    /**
     * 集束缆的通道数。反汇编 {@code BundledCableICPart.<init>} 得到：
     * {@code bipush 16; newarray byte} 给 {@code signal} 和 {@code tmpSignal}。
     */
    private static final int BUNDLED_CHANNELS = 16;

    /**
     * NBT 标签类型 id（1.7.10 的 {@code NBTBase} 常量）。
     *
     * <p><b>用之前先记住这两个 API 的参数含义是相反的</b>：
     * <ul>
     *   <li>{@code NBTTagCompound.hasKey(key, type)} —— {@code type} 是<b>标签自身</b>的类型；</li>
     *   <li>{@code NBTTagCompound.getTagList(key, type)} —— {@code type} 是<b>元素</b>的类型！</li>
     * </ul>
     * 原版 {@code IntegratedCircuit.load} 读 {@code parts} 用的是
     * {@code getTagList("parts", 10)}（10 = Compound，元素类型），不是 9。
     * <p><b>传错不会报错，只会静默返回一个空列表</b> —— 这个坑很贵，踩过一次：
     * 明明载入了 116 个零件，回执却说 "0 个"；同时"死信号"拦截整个失效。
     */
    private static final int TAG_BYTE = 1;

    /** 见 {@link #TAG_BYTE} 的说明。 */
    private static final int TAG_BYTE_ARRAY = 7;

    /** 见 {@link #TAG_BYTE} 的说明。 */
    private static final int TAG_LIST = 9;

    /** 见 {@link #TAG_BYTE} 的说明（{@code parts} 的元素类型）。 */
    private static final int TAG_COMPOUND = 10;

    /** 0.3.8：覆盖前留底的目录名（在游戏目录下，不在 {@code blueprints/} 里，面板不会列出来）。 */
    private static final String BACKUP_DIR = "icwbe_blueprint_backups";

    /** 0.3.8：把"另一块板覆盖同名蓝图"的第一次点击拦下来，这段时间内再点一次就算确认。 */
    private static final long CONFIRM_MS = 10000L;

    /** 0.3.8：本次会话的备份子目录名，惰性生成（一次 JVM 一个）。 */
    private static String sessionStamp;

    /** 0.3.8：上一次被拦下的覆盖目标（配合 CONFIRM_MS 做两步确认）。 */
    private static String warnName;

    private static long warnAt;

    private Blueprints() {
    }

    /** {@code .minecraft/blueprints/}，不存在就建。 */
    public static File dir() {
        Minecraft mc = Minecraft.func_71410_x();
        File base = mc == null ? new File(".") : mc.field_71412_D;
        File d = new File(base, "blueprints");
        if (!d.isDirectory()) {
            d.mkdirs();
        }
        return d;
    }

    /** 已保存的蓝图名（去掉扩展名），按名字排序。 */
    public static List<String> list() {
        List<String> out = new ArrayList<String>();
        File[] fs = dir().listFiles();
        if (fs == null) {
            return out;
        }
        Arrays.sort(fs, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        String lower = EXT.toLowerCase();
        for (File f : fs) {
            String n = f.getName();
            if (f.isFile() && n.toLowerCase().endsWith(lower)) {
                out.add(n.substring(0, n.length() - EXT.length()));
            }
        }
        return out;
    }

    /** 把用户输入的名字洗成安全的文件名（保留中文、字母、数字、下划线、短横）。 */
    public static String sanitize(String name) {
        StringBuilder sb = new StringBuilder();
        String s = name == null ? "" : name.trim();
        for (int i = 0; i < s.length() && sb.length() < 48; i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.length() == 0 ? "untitled" : sb.toString();
    }

    public static File fileFor(String name) {
        return new File(dir(), sanitize(name) + EXT);
    }

    // ------------------------------------------------------------ 写

    /** 保存当前电路。返回 null 表示成功，否则是给用户看的错误消息。 */
    public static String save(String name, IntegratedCircuit ic) {
        if (ic == null) {
            return "no circuit";
        }
        try {
            // 0.3.8：如果这一刻正好有一次同步在飞，本地板是"回滚后的旧板"，
            // 直接拍它就会把旧板写进文件。要拍的是这次同步真正要落地的那张板。
            NBTTagCompound staged = Sync.boardBeingSynced(ic);
            NBTTagCompound t;
            if (staged != null) {
                t = staged;
                // 底座 save 的第一步是 ensureCaches(ic)：把"客户端还没算出来的"集束缆缓存
                // 从 null 补成全零的 16 字节，这样紧跟其后的死信号检查才不会误拦。
                // 快照可能是在任何一次 ensureCaches 之前拍的，所以这里对标签做同样的事。
                ensureCachesInTag(t);
            } else {
                // 顺序必须与底座一致：先补缓存，再查死信号（顺序反了会把正常蓝图拦下）
                ensureCaches(ic);
                t = new NBTTagCompound();
                ic.save(t);
            }
            // 集束缆的 signal 一旦是 null / 长度不对（"死信号"），同步到服务端会变 null，
            // 进存档就炸（EncoderException 断连）。在源头拦下，比让用户存一个以后不敢读的文件强。
            int dead = t == staged ? countDeadBundledInTag(t) : countDeadBundled(ic);
            if (dead > 0) {
                return Lang.t("st.bp_dead", String.valueOf(dead));
            }
            if (staged != null) {
                System.out.println("[ICWbEx] saving the staged board - the live copy is"
                    + " rolled back while its sync converges");
            }
            // 0.3.8：要把"这块板"写进"那个文件"之前，先确认那个文件里装的就是这块板。
            // 面板的名称框在<b>单击列表行</b>时就会被填上那一行的名字，于是"点了一下想切过去"
            // （双击太慢没切成功）+ 一次保存 = 当前板覆盖掉那个蓝图。这里把这种情况拦成两步确认。
            File dst = fileFor(name);
            String blocked = overwriteGuard(sanitize(name), t, dst);
            if (blocked != null) {
                return blocked;
            }
            // 文件里的 name 和文件名保持一致（底座这一步靠 Share.saveNamed 先把 ic.name 设好；
            // 走暂存分支时 ic 是回滚后的旧板，名字得我们自己写进去）
            t.func_74778_a("name", sanitize(name));
            // 加两个自己的标记，方便以后做版本迁移；ic.load 不认得的键会直接忽略
            t.func_74768_a("icwbe:ver", 1);
            t.func_74778_a("icwbe:savedBy", "ICWbEx");

            // 先写临时文件再原子替换。
            // 直接写目标文件的话，一旦序列化中途抛异常，磁盘上会留下一个截断的 gzip ——
            // 它能被 list() 列出来，但读的时候必然失败，用户看到的就是"列表里有这个蓝图，
            // 点开却报 cannot read xxx.icbp"。临时文件方案让失败不留痕。
            File tmp = new File(dst.getParentFile(), dst.getName() + ".tmp");
            CompressedStreamTools.func_74795_b(t, tmp);
            keepPrevious(dst);
            if (dst.exists() && !dst.delete()) {
                tmp.delete();
                return "cannot overwrite " + dst.getName();
            }
            if (!tmp.renameTo(dst)) {
                tmp.delete();
                return "cannot rename " + tmp.getName();
            }
            return null;
        } catch (Throwable e) {
            return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        }
    }

    /**
     * 0.3.8：把"用另一块板覆盖同名蓝图"的第一次点击拦下来（第二步确认）。
     *
     * <p>为什么需要：面板的"名称"框在<b>单击列表行</b>时就会被填成那一行的名字
     * （{@code GuiBlueprint.selectRow} → {@code nameBox.text_$eq}），而"保存"正是拿名称框里的字
     * 去覆盖同名文件。于是"点了一下想切过去"（双击窗口只有 350ms，慢了就没切成功）再按一次保存，
     * 就把<b>当前工作台</b>写进了<b>那个</b>蓝图 —— 用户的原话是"切换的时候有时会把我选择的
     * 蓝图覆盖掉"。判据是文件里记着的板名：那个名字和要写进去的板名不一样 ⇒ 这不是"保存我这块板"，
     * 而是"拿 A 覆盖 B"，值得确认一次。
     *
     * <p>做法沿用了面板自己的习惯（删除键也是"再点一次确认"，见 {@code bp.del_twice}）：
     * 第一次只返回提示，10 秒内再按一次就当真。
     *
     * @return null 表示放行，否则是要显示给用户的提示
     */
    private static String overwriteGuard(String target, NBTTagCompound board, File dst) {
        try {
            if (board == null || !dst.isFile()) {
                return null;            // 新文件，没什么可覆盖的
            }
            NBTTagCompound old = CompressedStreamTools.func_74797_a(dst);
            if (old == null) {
                return null;            // 读不了（损坏/异形文件）：让这次保存给它覆盖掉
            }
            String oldName = old.func_74779_i("name");
            if (oldName == null || oldName.length() == 0) {
                return null;
            }
            String boardName = board.func_74779_i("name");
            boolean nameDiffers = !sanitize(oldName).equals(sanitize(boardName));
            // 0.4.0：名字相同也要看一眼尺寸。两阶段提交期间若发生快速连点，客户端板可能
            // 短暂地"带着 A 的名字装着 B 的内容"（见 Sync.foldName）；那种板一旦存进
            // 名字相同的文件就会把那个蓝图换成另一个板。尺寸不同是"这压根不是同一块板"
            // 的强信号，而正常编辑绝不会改变尺寸（改尺寸只能通过原版"新建 IC"/空白 desc，
            // 那会连名字一起换）。
            boolean sizeDiffers = old.func_74771_c("sw") != board.func_74771_c("sw")
                || old.func_74771_c("sh") != board.func_74771_c("sh");
            if (!nameDiffers && !sizeDiffers) {
                return null;            // 同一块板的普通保存
            }
            long now = System.currentTimeMillis();
            if (target.equals(warnName) && now - warnAt <= CONFIRM_MS) {
                warnName = null;
                warnAt = 0L;
                return null;            // 第二步：用户确实要覆盖
            }
            warnName = target;
            warnAt = now;
            String oldDesc = nameDiffers ? sanitize(oldName) : describe(old);
            String newDesc = nameDiffers ? sanitize(boardName) : describe(board);
            return Lang.t("st.bp_overwrite", target, oldDesc, newDesc);
        } catch (Throwable t) {
            return null;                // 守卫本身绝不能挡住保存
        }
    }

    /** "WxH / N 件" - for the overwrite confirmation when the names match. */
    private static String describe(NBTTagCompound tag) {
        if (tag == null) {
            return "?";
        }
        return (tag.func_74771_c("sw") & 0xFF) + "x" + (tag.func_74771_c("sh") & 0xFF)
            + " / " + countPartsInTag(tag) + "p";
    }

    /** Part count of a saved board tag. */
    private static int countPartsInTag(NBTTagCompound tag) {
        try {
            NBTTagList list = tag.func_150295_c("parts", TAG_COMPOUND);
            return list == null ? 0 : list.func_74745_c();
        }
        catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 0.3.8：覆盖一个已存在的蓝图之前，把它拷进本次会话的备份目录。
     *
     * <p>为什么必须有：面板的"名称"框在<b>单击列表行时就会被填上那一行的名字</b>
     * （{@code GuiBlueprint.selectRow}），而"保存"就是拿名称框里的字去覆盖同名文件。
     * 于是"我只是点了一下想切过去，没切成功，又按了一次保存"这种手滑，
     * 会把当前板子直接写进那个蓝图。留底之后这类误操作最多损失一次会话。
     *
     * <p>同一会话里同名文件只留<b>最早</b>那份（第一次覆盖前的内容往往才是原作，
     * 后面可能一路被覆盖成别的）。
     */
    private static void keepPrevious(File dst) {
        try {
            if (dst == null || !dst.isFile()) {
                return;
            }
            Minecraft mc = Minecraft.func_71410_x();
            if (mc == null || mc.field_71412_D == null) {
                return;
            }
            if (sessionStamp == null) {
                sessionStamp = new SimpleDateFormat("yyyy-MM-dd_HHmmss").format(new Date());
            }
            File dir = new File(new File(mc.field_71412_D, BACKUP_DIR), "session-" + sessionStamp);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return;
            }
            File to = new File(dir, dst.getName());
            if (to.isFile()) {
                return;   // keep the earliest version of this session
            }
            if (copyFile(dst, to)) {
                System.out.println("[ICWbEx] kept the blueprint about to be overwritten: "
                    + to.getPath());
            }
        } catch (Throwable ignored) {
            // 留底失败绝不能挡住保存本身
        }
    }

    private static boolean copyFile(File from, File to) {
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(from);
            out = new FileOutputStream(to);
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
            }
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 把"客户端还没算出来的缓存数组"补成占位，避免任何序列化路径炸掉。
     *
     * <p>踩过的坑：{@code BundledCableICPart.save} 会写 {@code signal} 这个 {@code byte[]}：
     * <pre>
     *   // 反汇编 BundledCableICPart.save
     *   tag.func_74773_a("signal", this.signal());
     * </pre>
     * 而这个数组是<b>运行时缓存</b>，不是数据 —— 构造器里分配 {@code new byte[16]}
     * （反汇编 {@code BundledCableICPart.<init>}：{@code bipush 16; newarray byte}），
     * 客户端在 {@code readDesc} 里会被 {@code BundledCommons.unpackDigital} 覆盖，为空时是 {@code null}。
     * 一旦 {@code new NBTTagByteArray(null)} 进了 NBT，写入时读
     * {@code byteArray.length} 就会抛
     * {@code NullPointerException: Cannot read the array length because "this.field_74754_a" is null}
     * （{@code field_74754_a} 就是 {@code NBTTagByteArray} 的数组字段）。
     *
     * <p>原版自己很少撞上，因为它的 IC 存档都在<b>服务端</b>（跑过传播、缓存齐全）。
     * 我们在客户端做快照、做整块同步，就必须自己补。
     *
     * <p><b>为什么补完不还原</b>（早期版本试过"补→用→还"，是错的）：
     * <ul>
     *   <li>MC 的网络发送走 netty，<b>编码在 netty 线程异步进行</b> ——
     *       调用方 return 之后包才真正被编码。提前还原 = 在编码时读到 null，照炸不误。
     *       实机表现就是 FML 报
     *       {@code EncoderException ... "this.field_74754_a" is null}。</li>
     *   <li>补出来的值是"全零"，语义上就是<b>所有通道无信号</b>，而客户端本来也算不出真实信号
     *       （要靠服务端传播后下发覆盖），所以这个占位基本无感。</li>
     *   <li>而且补过之后就不再是 null，后续调用是 no-op，不存在"反复污染"。</li>
     * </ul>
     * 结论：<b>宁可留一个语义正确的占位，也不能让 null 进 NBT。</b>
     *
     * <p><b>适用范围要清楚</b>：这个补丁救的是<b>本地序列化</b>（蓝图存档、撤销快照、复制、
     * 以及任何把 NBT 写出去的路径）。它<b>救不了网络那条路</b> —— IC 的网络同步走的是
     * {@code BundledCommons.packDigital(signal)}，而反汇编显示：
     * <pre>
     *   packDigital(byte[] b)      : if (b == null) return 0;   // null 和"全零"都压成 0
     *   unpackDigital(byte[] o, int d): if (d == 0) return null; // 0 读回来就是 null
     * </pre>
     * 也就是原版用一个 int 表示 16 路信号，"无信号"与 null 无法区分，是<b>有损编码</b>。
     * 那是原版行为，客户端补什么值都绕不过去。见 {@link Sync}。
     *
     * @return 本次实际补了几个零件
     */
    public static int ensureCaches(IntegratedCircuit ic) {
        int n = 0;
        if (ic == null) {
            return 0;
        }
        try {
            // NB: the signature stub of scala.collection.Iterator is non-generic, so this
            // must use the raw type (same as the loops in Sync/Replay).
            scala.collection.Iterator it = ic.parts().values().iterator();
            while (it.hasNext()) {
                Object o = it.next();
                if (o instanceof BundledCableICPart) {
                    BundledCableICPart bc = (BundledCableICPart) o;
                    if (bc.signal() == null) {
                        bc.signal_$eq(new byte[BUNDLED_CHANNELS]);
                        n++;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 补不了也别挡着调用方；序列化时该报的错会由调用方的 catch 报出来
        }
        return n;
    }

    // ------------------------------------------------------------ 无信号集束缆检测

    /**
     * 0.3.8: {@link #ensureCaches} 的"标签版"，只给 {@link #save} 的暂存分支用。
     *
     * <p>暂存分支写的是<b>之前拍下的快照</b>，它完全可能在 ensureCaches 跑之前就被拍下来了，
     * 于是集束缆的 {@code signal} 还是个 null 数组。那样写盘会 NPE
     * （{@code NBTTagByteArray} 的数组字段为 null —— 见 {@link #ensureCaches} 的说明），
     * 而且 {@link #countDeadBundledInTag} 会先把它当成"死信号"直接拒绝保存。
     * 这里用与 ensureCaches 相同的规则补成全零的 16 字节，让暂存分支的行为和正常保存一致。
     */
    private static void ensureCachesInTag(NBTTagCompound t) {
        try {
            NBTTagList parts = t.func_150295_c("parts", TAG_COMPOUND);
            int len = parts.func_74745_c();
            for (int i = 0; i < len; i++) {
                NBTTagCompound pt = parts.func_150305_b(i);
                // 带类型地取：红石线的 signal 是 byte(tag 1)，这里只关心集束缆的 byte[](tag 7)
                if (pt == null || !pt.func_150297_b("signal", TAG_BYTE_ARRAY)) {
                    continue;
                }
                if (pt.func_74770_j("signal") == null) {
                    pt.func_74773_a("signal", new byte[BUNDLED_CHANNELS]);
                }
            }
        } catch (Throwable ignored) {
            // 补不了就交给后面的死信号检查去拒绝，绝不因为补缓存本身报错
        }
    }

    /**
     * 一根 signal 会不会在同步后把服务端变成 null。
     *
     * <p><b>判据必须与底座 jar 完全一致</b>（反汇编 {@code Blueprints.isDeadSignal}）：
     * <pre>
     *   private static boolean isDeadSignal(byte[] sig) {
     *       return sig == null || sig.length != BUNDLED_CHANNELS;   // 16
     *   }
     * </pre>
     * 只有 <b>null</b> 和<b>长度不是 16</b> 算"死信号"。
     *
     * <p>为什么"全零数组"不能算：客户端那 16 字节是<b>运行时缓存</b>，正常状态下就是
     * {@link #ensureCaches} 填的 {@code new byte[16]}（全零）—— 也就是说，若把全零判成死信号，
     * <b>任何含集束缆的蓝图都存不了、也读不了</b>。旧工作目录里的那份源码正是这么写的
     * （多了一段"逐字节非零才算活"的循环），0.3.8 把 Blueprints 接管过来时差点直接照抄：
     * 现场证据是 {@code 测试1.icbp} 的两根集束缆 signal 全零、却一直是能正常载入的。
     * 教训见 HANDOFF 铁律：<b>底座 jar 里的类必须 javap 实证，不能凭旧源码猜。</b>
     */
    private static boolean isDeadSignal(byte[] sig) {
        return sig == null || sig.length != BUNDLED_CHANNELS;
    }

    /** 电路里有多少根"死信号"的集束缆（同步后服务端会变 null 的那种）。 */
    public static int countDeadBundled(IntegratedCircuit ic) {
        int n = 0;
        if (ic == null) {
            return 0;
        }
        try {
            scala.collection.Iterator it = ic.parts().values().iterator();
            while (it.hasNext()) {
                Object o = it.next();
                if (o instanceof BundledCableICPart && isDeadSignal(((BundledCableICPart) o).signal())) {
                    n++;
                }
            }
        } catch (Throwable ignored) {
            // 统计失败按 0 算；真有问题的话保存/同步自己会再报
        }
        return n;
    }

    /**
     * 蓝图标签里有多少根"死信号"的集束缆。
     *
     * <p>直接查 NBT 而不是先 {@code ic.load} —— load 是就地整块替换，
     * 先查可以在<b>动电路之前</b>就拒绝，不留半截状态。
     *
     * <p>结构（反汇编 {@code IntegratedCircuit.save} / {@code BundledCableICPart.save}）：
     * {@code parts} 是 NBTTagList，每项 {id:byte, xpos:byte, ypos:byte, ...零件自己的键}，
     * 集束缆有 {@code signal:byte[]}。
     *
     * <p><b>踩过的坑（务必看）</b>：{@code getTagList(key, type)} 的第二个参数
     * <b>不是列表自身的类型，而是列表里元素的类型</b>。原版反汇编为证：
     * <pre>
     *   // IntegratedCircuit.load
     *   79: ldc string parts
     *   81: bipush 10                                     // ← 10 = Compound，是元素类型
     *   83: invokevirtual NBTTagCompound.func_150295_c (Ljava/lang/String;I)Lnet/minecraft/nbt/NBTTagList;
     * </pre>
     * 这里之前误传了 {@code 9}（NBTTagList 自身的类型），于是
     * {@code getTagList} 发现 {@code list.getTagType()(10) != 9}，<b>返回一个空列表</b>，
     * 结果永远数出 0 根 —— 死信号拦截整个失效。
     * <p>另注：{@code hasKey(key, type)}（{@code func_150297_b}）比的<b>是标签自身的类型</b>，
     * 两个 API 的参数含义正好相反。所以下面查 {@code parts} 存在性用单参 {@code hasKey}，
     * 取列表才带类型常量，免得再混。
     */
    public static int countDeadBundledInTag(NBTTagCompound t) {
        if (t == null) {
            return 0;
        }
        int n = 0;
        try {
            // 第二参数是"元素类型"(10)，不是列表自身类型(9)。键不存在时
            // getTagList 返回空列表（原版行为），所以这里不需要先判存在性。
            NBTTagList parts = t.func_150295_c("parts", TAG_COMPOUND);
            int len = parts.func_74745_c();
            for (int i = 0; i < len; i++) {
                NBTTagCompound pt = parts.func_150305_b(i);
                if (pt == null) {
                    continue;
                }
                // ★ "signal" 这个名字被两种零件共用，类型不同：
                //   - BundledCableICPart.save  →  byte[16]（集束缆，tag 7）  ← 我们关心的
                //   - RedwireICPart.save       →  byte（红石线信号强度 0~15，tag 1）
                // 所以判定必须"带类型"，不能只按名字取：
                //   getByteArray("signal") 遇到 tag 1 会 ClassCastException 被吞掉、
                //   返回 byte[0]，于是每根红石线都会被误判成"死信号"。
                if (!pt.func_150297_b("signal", TAG_BYTE_ARRAY)) {
                    continue;
                }
                if (isDeadSignal(pt.func_74770_j("signal"))) {
                    n++;
                }
            }
        } catch (Throwable ignored) {
            // 结构不合预期就按 0 算，让后续流程按老规矩报错
        }
        return n;
    }

    public static NBTTagCompound read(String name) {
        try {
            File f = fileFor(name);
            if (!f.isFile()) {
                return null;
            }
            return CompressedStreamTools.func_74797_a(f);
        } catch (Throwable e) {
            return null;
        }
    }

    // ------------------------------------------------------------ 读

    /** 加载蓝图文件到电路并同步给服务端。返回 null 表示成功，否则是错误消息。 */
    public static String load(String name, IntegratedCircuit ic, TileICWorkbench tile) {
        NBTTagCompound t = read(name);
        if (t == null) {
            return "cannot read " + sanitize(name) + EXT;
        }
        return apply(t, ic, tile);
    }

    /**
     * 把一份"蓝图 NBT"应用到电路并同步给服务端。
     *
     * <p>蓝图文件和分享串（{@link Share}）走的是<b>同一个入口</b>，
     * 所以两条路的校验完全一致，不会出现"文件拦了、字符串没拦"的缺口。
     *
     * <p>校验顺序（必须在 {@code ic.load} 之前 —— 它是就地整块替换，
     * 一旦开始改就没有"拒绝"的余地了）：
     * <ol>
     *   <li>结构像不像 IC 蓝图（必须有 {@code parts}，{@code sw}/{@code sh} 用来定板子尺寸）；</li>
     *   <li>有没有"死信号"集束缆 —— 这类缆同步到服务端会变 null，
     *       进存档就炸（详见 {@link Sync}）。</li>
     * </ol>
     *
     * @return null 表示成功，否则是给用户看的错误消息
     */
    public static String apply(NBTTagCompound t, IntegratedCircuit ic, TileICWorkbench tile) {
        if (t == null) {
            return "empty blueprint";
        }
        // 结构校验。用带类型的 hasKey（它比的是"标签自身类型"）：
        // parts 一定是 List(9)、sw 一定是 Byte(1)。
        // 早先写成 hasKey("sw", 99)，99 不对应任何标签类型 → 永远 false，
        // 等于这道校验根本没生效（靠后面的 parts 判断侥幸没出事）。
        if (!t.func_150297_b("parts", TAG_LIST) || !t.func_150297_b("sw", TAG_BYTE)) {
            return "not an IC blueprint";
        }
        // 0.3.9：尺寸变化只能靠整板 desc 传过去，而服务器在"没插 IC 蓝图/板"（hasBP=false）时
        // 会**静默丢弃**整板 desc（TileICWorkbench.read case 5 的 else 分支）。真发出去的结果是
        // 服务器留着旧尺寸的板、客户端换成新尺寸的板 ⇒ 两边长期分叉 ⇒ 服务器为它还在发的零件
        // 送状态帧时客户端那格是空的 ⇒ PR 兜底建 subID=0 ⇒ 断连。这里提前拒绝并讲清楚。
        if (ic != null && tile != null && !tile.hasBP()) {
            int tw = t.func_74771_c("sw") & 0xFF;
            int th = t.func_74771_c("sh") & 0xFF;
            Size cur = ic.size();
            if (cur != null && (tw != cur.width() || th != cur.height())) {
                return Lang.t("st.no_bp_size", String.valueOf(tw), String.valueOf(th));
            }
        }
        int dead = countDeadBundledInTag(t);
        if (dead > 0) {
            return Lang.t("st.bp_dead", String.valueOf(dead));
        }
        try {
            ic.load(t);
            ic.refreshErrors();
            Sync.toServer(tile, ic);
            return null;
        } catch (Throwable e) {
            return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        }
    }

    public static boolean delete(String name) {
        try {
            File f = fileFor(name);
            return f.isFile() && f.delete();
        } catch (Throwable e) {
            return false;
        }
    }
}

# 构建 ICWbEx

先说清楚这个项目的特殊之处：**ICWbEx 没有 dev 环境**。它是 1.7.10 的纯客户端模组，直接以 SRG 名（`func_74762_e` 这类）引用 Minecraft / ProjectRed / CodeChickenLib 的内部 API，构建时用一套**签名桩**（`stubs/`）代替真实类——桩只有方法签名、没有实现，运行时链接的是游戏 jar 里的真类。所以：

- 不需要 Forge/MCP 开发环境，不需要反混淆 Minecraft；
- 代价是：**桩签名必须和运行时 jar 逐字节一致**，否则就是 0.2.7 的下场（见下文"铁律"）。

## 前置

- JDK：`javac` 在 PATH 里即可（需要支持 `--release 8`，JDK 9~25 都行；产物必须是 Java 8 字节码 / major 52）。
- Python 3.6+。

## 一键构建

```bash
python build.py        # 产出 out/ICWbEx-<版本>.jar
```

脚本做五件事：

1. **编译桩**：`stubs/` 下全部 `.java`（排除 `stubs/icwbe/GuiBlueprint.java`）→ `build/stubsout`；
2. **编译源码**：`src/` + 那个 GuiBlueprint 影子桩（它引用真实的 `GuiICWbEx`，必须和 src 同批；编出的 `.class` 不进 jar，运行时用底座 jar 里的原版）；
3. **调用点断言**（防 0.2.7 那一类回归，详见下文）：例如 `Replay` 对 CCL `MCDataOutput` 的调用必须全部是 `invokeinterface`、**禁止**出现 `func_74762_e`（getInteger）而**必须**有 `func_74771_c:(Ljava/lang/String;)B`、`Sync` 的流重置 TraitSetter 签名必须精确；
4. **组装 jar**：`libs/ICWbEx-0.2.6.jar` 作底座，替换更新过的类 + 追加新类（见 `build.py` 的 `REPLACE` / `ADD`），把版本字节 `0.2.6 → <NEW_VER>`，重写 `mcmod.info`，再逐类 `javap` 全量验证；
5. **校验内部类打包**：`REPLACE`/`ADD` 里那些类的内部类（`Foo$1`、`Foo$2` …）必须一并进 jar——漏一个就是运行期 `NoClassDefFoundError`（0.3.9 实测：点击按钮即崩，编译却全绿）。

> 改完代码用 `python build.py` 构建，**全绿才可用**；产物用 `python tools/deploy.py` 部署（游戏在跑会拒绝并提示先关游戏）。

## 改桩的铁律（0.2.7 翻车实录，务必读）

0.2.7 曾带着两个错误桩发布，实测"第一次加载集束线缆全没了、再加载一次服务器 NPE 踢人"：

1. `MCDataOutput` 桩写成了 abstract class——**真实是 interface**（CodeChickenCore 1.4.22 内嵌的 CodeChickenLib）。invokevirtual 调接口方法 → `IncompatibleClassChangeError`。
2. NBT 桩把 `func_74771_c` 当 `getInteger(String)I`——**它真是 `getByte(String)B`**。`getInteger = func_74762_e (Ljava/lang/String;)I`（`NBTTagCompound` 唯一的 `(String)I` 方法）。

规则：

- **class 还是 interface**：`javap -p` 运行时 jar 实证，别信任何注释（0.2.7 的桩注释里就写着"verified as abstract class"，是错的）。
- **名字 + 描述符**：用 Forge universal jar 里的官方映射核对：`libraries/net/minecraftforge/forge/1.7.10-10.13.4.1614-1.7.10/deobfuscation_data-1.7.10.lzma`（`python -c "import lzma; open('srg.csv','wb').write(lzma.decompress(open(<jar内条目>,'rb').read(), format=lzma.FORMAT_ALONE))"`）。注意该 CSV 是 obf→SRG，**不含 MCP 名**——查语义要靠描述符消歧，或对照 ProjectRed 自己的字节码（`javap -c` PR 的类，抄它的调用名:描述符，那是地面真值）。
- **编译后必断言**：`build.py` 第 3 步已内建关键断言；改了新桩后，把新调用点也加进 `build.py` 的 `assert_call_sites()`。
- 运行时 NBT getter 对**类型不符的键返回默认值 0**（不是抛异常），所以读错类型不会立刻炸，而是悄悄产生错误数据——比对描述符是唯一可靠的手段。
- **`getInteger` 的类型陷阱（0.3.2 实战）**：`IntegratedCircuit.save` 把 `sw`/`sh` 与每个零件的 `id`/`xpos`/`ypos` 都写成 **NBT Byte**（`javap` 可见 `i2b` + `func_74774_a`），必须用 `func_74771_c`（getByte）读。曾有整整三个版本用 `func_74762_e`（getInteger）去读——类型不符时它**不抛异常、静默返回 0**，结果"每个零件都被映射到 (0,0)、每次编辑都被判成扩板"。`build.py` 现在反向断言：出现 `func_74762_e` 即构建失败。
- **接管底座 jar 里的类时，要逐方法比对行为，不能只对签名**：本地留存的旧源码可能是另一个迭代。0.3.8 接管 `Blueprints` 时差点照抄，而那份源码的 `isDeadSignal()` 会把"全零数组"也算死信号（底座只判 `null` 与长度≠16）——照抄会让**任何含集束缆的蓝图都存不了、读不了**。做法：把新旧两份 class 用 `javap -c -p` 逐方法比对（去掉偏移量与 `#常量池` 序号后比指令序列），只允许有意改动的那几个方法不同。

## ProjectRed IC 同步协议备忘（CFR 反编译实证）

- 服务器 `TileICWorkbench.read(in, key)`：`case 4` = op 流（`readICStream`，循环读到 0xFF，只捕获 IndexOutOfBoundsException）；`case 5` = 整板 desc（**无 BP 时服务器直接丢弃**；有 BP 才 readDesc + echo 给观察者）；`case 1/2/3` = hasBP 布尔 / part desc / part 流。
- `sendNewICToServer` 走独立的 `writeStream(5)` 通道，与 icStream 缓冲无关。
- **`tile.getICStreamOf(key)` 被调用的瞬间就把 key 字节写进客户端 tile 的持久缓冲**（在任何 payload 之前），且 `TileICWorkbench.update()/updateClient()` **每 tick flush**。所以"先拿流再写内容"的代码中途炸掉，会在缓冲里留下孤立的 key 字节，最迟一个 tick 后发给服务器 → 服务器把 0xFF 当 opId → `CircuitOpDefs$.apply(255)` 返回 null → `getOp()` NPE。`Sync.toServer` 的 catch 里用 TraitSetter 把两个缓冲置 null 来防这个（`mrtjp$projectred$fabrication$NetWorldCircuit$$icStream_$eq`，签名 `(Lcodechicken/lib/packet/PacketCustom;)V` 必须逐字节精确）。
- CircuitOp 共 61 个（ordinal 0..60），`CircuitOp$.getOperation` **无判空**，自产 opId 必须限制在 0..255。
- `OpWire.writeOp` = 4 字节 `[x0][y0][x1][y1]`；服务器 `readOp` 逐格放置，占用格/边界格静默跳过。

## 同步层为什么这么写（0.4.x 反面教训）

`Sync` / `Replay` 里的两阶段提交不是设计洁癖，是逐条踩出来的。动它之前先读这一节：

- **删除必须"先回滚、再删"**：PR 为每个元件持续发状态帧。若某帧到达时客户端那一格刚被清空，PR 会用 subID 0 把元件重建出来，紧接着一个带状态的门发出 `key>10` 帧 → `Invalid gate subID: 0` 掉线。所以危险改动先把客户端板子回滚到改动前、只发删除，等两边收敛再应用目标板并重建。
- **phase 2 绝不能再发一遍删除**：服务器会回显 `partRemoved`（本地跟着走），但**不会回显 case-1 新建**；重发删除会把刚重建的元件静默删掉。
- **差分只比"身份"**：只比 `subID`/`orient`/`max`/`inc`/`dec`、颜色、开关；任何运行期字段（计数器数值、计时器进度、连接图）都必须忽略，否则"重载一张没改动过的蓝图"会退化成拆板重建。
- **停泊（parking）**：删除 / 替换前一个窗口，把还会发状态帧的门用 case-1 换成 `connMap=0` 的惰性线材，保证没有任何帧落在即将变空的格子上。
- **看护要克制**：只有在"确认这是回滚回声"时才去修板，**绝不能**因为"元件数量对不上"就动手——那条规则会撤销玩家自己删掉的东西。
- **接管原版工具 = 连它的预览和三个键一起接管**：0.4.4 试过接管"放置"，结果是预览消失、右键打不开配置、中键失效、位置错位，只能整体回退。橡皮擦能被接管，是因为它本来就没有预览。下次想接管某个原版工具前，先想清楚这一条。

## 如何自己反编译 PR 作参考

1. 从 GTNH 实例的 `mods/ProjRed-*.jar` 解出目标 `.class`；
2. 用 python `zipfile` 打成小 jar（CFR 不直接吃目录）；
3. `java -jar cfr.jar <打包>.jar --outputdir <out>`（CFR 0.152 实测可用）。

## 版本发布

- 改 `build.py` 顶部 `NEW_VER` 与 `MCINFO_DESCRIPTION`，以及 `src/icwbe/Lang.java` 里 `help.title` 的版本号；
- **版本号必须是 5 个字符**（如 `0.4.6`，与底座 `0.2.6` 等长）：class 内的版本串是字节级补丁，变长（如 `0.2.10`）会撑坏常量池 → FML 识别 mod 时即崩、游戏无法启动。`build.py` 已内建双保险自动拦截；
- 底座固定是 `libs/ICWbEx-0.2.6.jar`（最后一个稳定的完整 jar），不要升级底座除非你清楚要重验哪些旧类；
- 产物命名 `ICWbEx-<版本>.jar`；部署用 `python tools/deploy.py`（它会把旧版改名 `.bak` 保留、校验 md5、并断言 mods 里只剩一个 ICWbEx jar）。
  **不要用"后台守护脚本等游戏退出后自动换 jar"这种办法**——agent 的后台任务在每轮回复结束时就会被终止，守护根本跑不到。

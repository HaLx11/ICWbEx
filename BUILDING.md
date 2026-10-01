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

## 电路板的"合法性"规则（0.4.7 取字节码实证，改扩版/粘贴前必读）

`Clipboard.stamp` 走的是 `IntegratedCircuit.setPart`，**绕过所有放置检查**——所以"能放上去"不等于"合法"。原版的合法性格外严格，规则全在 `CircuitOp$` / `OpGate` / `OpIOGate`：

| 位置判定 | 定义 | 谁受它约束 |
| --- | --- | --- |
| `CircuitOp$.isOnBorder(size,p)` | `x==0 \|\| y==0 \|\| x==w-1 \|\| y==h-1`（最外一圈） | —— |
| `CircuitOp$.isOnEdge(size,p)` | 四个角 | —— |
| `OpGate.canPlace` | `!isOnBorder` | **门**（simple/complex/array）绝不能挨着最外圈 |
| `OpIOGate.canPlace` | `isOnBorder && !isOnEdge` | **IO** 必须**在**最外圈上，且不能在四个角 |
| `OpWire` / `SimplePlacementOp` | 没有 `canPlace` | 线材、火把、拉杆、按钮 **哪都能放**，包括最外圈 |

其他要点：

- **IO 的位置就是它的身份**：`IOGateICPart.getIOSide()` 直接返回 `rotation()`，没有任何独立字段。
  所以 IO 只靠"站在哪一格"来合法，板子一变大，原来贴边的 IO 就落到中间 → **蓝图立刻不合法**。
  修的时候要**搬元件**（`removePart` + `setPart`，`setPart_do` 会重新 `bind` 坐标），
  不要改字段——搬动保留 `rotation`，也就保留了它代表 IC 的哪一面。
- **上限 64×64**：`NewICNode.maxBoardSize = Size(4,4)`，单位是 16 格。`IntegratedCircuit.size_$eq`
  **不做任何校验**，想多大就多大——上限只是 UI 约定，所以扩版代码必须自己守住它。
- **扩版只能往右/下长**：`size_$eq` 只是换个 `Size`，内容不会平移。所以贴在左/上边界的门
  **没法靠扩版救**（板子再大，`x==0` 还是最外圈），规划器对此不触发扩版。
- **剪贴板里有 IO 时，一律不许扩版**（`GuiICWbEx.stampAt` 里 `Grow.containsIO` → 丢掉 plan）。
  理由：IO 只在圈上合法，而**扩版就是在移动圈**——粘贴时瞄准边界的 IO，板子一变大就不在边界上了。
  这个判断刻意放在调用方而不是 `Grow` 里：`Grow` 只回答"怎样才合法"，"玩家在放接口、别把板子挪走"
  是**意图**层面的策略，留在外面才能让几何表保持纯函数（也才测得了）。
  - 副作用一：被抑制后粘贴**不再扩容**，超出板外的格子会被 `stamp` 静默丢弃——所以
    `Grow.countOffBoard` 会数出来并提示玩家，不然"已粘贴 N 个"会莫名其妙地少于框选数量。
  - 副作用二：同一个剪贴板里的**门**可能因此落到圈上。原版不报错（见上），我们自己也不报——
    这是已知取舍，若以后要提示，加个 `Grow.gatesOnRing` 即可。
- **PR 对"不合法"是零反馈的**（重要）：`refreshErrors` 只收集 `IErrorCircuitPart`，而**整个模组里
  唯一的实现是 `WireICPart.postErrors`**，它只报两件事——`connMap` 位数 0 → "Unreachable wiring"、
  位数 1 → "Useless wiring"。也就是说**边界上的门、板中间的门、板中间的 IO，都不会报错、不会崩**。
  运行期 IO 只是按 `rotation` 登记进 `iostate[4]`（`IntegratedCircuit` 用
  `collect { case io: IIOCircuitPart => io }` 收集，**不看坐标**），所以一个跑到中间的 IO 依然
  会当作那一面的接口参与运算。结论：**这类问题不会炸，只会静默地做错事**——凡是我们自己发现
  的不合法状态，必须由我们自己说出口。
- 界面里唯一另一个改尺寸的入口是原版"新建 IC"对话框，它 `load` 一块**空板**——也就是说它会
  清空现有元件（原版行为，不是我们的锅）。**所以"有内容的板子怎么变大"目前只有一条路：粘贴到
  板外**，且剪贴板不含 IO（含 IO 时禁止扩版，见上）。
  > ⚠️ **0.4.7 的教训：不要为了解决"含 IO 无法扩版"而单独加一个扩版入口。** 当时加了 G 键
  > （向右下各加一档 16），结果两条规则互相打架：粘贴禁止为含 IO 的内容扩版，而 G 键又在扩版时
  > 把 IO 往外搬 —— 用户实测后判定是自相矛盾的设计，要求整体回退。**改这块之前先想清楚"谁是
  > 唯一的扩版入口"**，别让两条路径对同一个不变量各说各话。
- **粘贴永不写入边框（0.4.7）**——规则只有一条：
  **`Clipboard.stamp` 跳过任何"非 IO 元件落到最外圈"的格子**，无条件、四角计入。
  计数在 `lastSkippedBorder`，状态栏用 `st.border_cleared` 报出来。
  - **两个判定必须分清**，混用正是 0.4.7 中途留下洞的原因：
    | 原版判定 | 含义 | 对应方法 |
    | --- | --- | --- |
    | `OpGate.canPlace = !isOnBorder` | 门不能碰边框，**四角也算** | `Grow.isOnBorder` |
    | `OpIOGate.canPlace = isOnBorder && !isOnEdge` | IO 在边框上、但**不能在四角** | `Grow.isOnRing` |
    当时用 `isOnRing`（不含四角）去承担"门的合法性"，于是**门可以落进四角**；
    而"门的合法性"只以"是否扩版"的形式存在，**扩版又只能往右/下长**，所以门落在**左/上**边框
    时既不触发扩版、也没人拦——四条边表现不一致。
  - **顺序**：`stampAt` 先跑 `planGrowth`（门落到右/下时扩版，门被"救"到内部），再由 `stamp`
    兜底丢弃。所以右/下的门是**救回**、左/上/四角的门是**丢弃**——这是扩版单向性带来的必然差别，
    不是遗漏。
  - ⚠️ **本规则比原版严**：原版只禁止**门**在边框上，线材/火把/拉杆/按钮（`OpWire` /
    `SimplePlacementOp` 没有 `canPlace`）本来就允许贴边。现在**粘贴**一律不写边框 ⇒
    **靠边铺线只能手动放，不能粘贴**。要放宽成"只拦门"，把 `Clipboard.stamp` 里的
    `!Grow.isIO(id)` 换成 `Grow.isGate(id)` 即可（`Grow.isGate` 已经有了）。
- **粘贴框吸附 + 拒收**（0.4.7）：剪贴板含 IO 时，粘贴框被吸附到"能让每个 IO 都落在 `isOnRing` 上、
  且整块内容都在板内"的落点（`Grow.ioPasteOrigins` + `nearestOrigin`）。**预览与落笔共用
  `GuiICWbEx.snappedOrigin`**，框画在哪就贴在哪。没有任何合法落点时**在进入粘贴模式前就拒绝**，
  因为玩家无论把框移到哪都不成立，让人试一遍再报错就是死胡同。
  - 推论：某个 IO 要够到右边界就必须 `dx == clipW-1`（在内容右边缘），所以
    **不在内容边缘上的 IO 永远到不了边框** ⇒ `hasInteriorIO` ⇒ 拒绝；两条相对边同时有 IO ⇒ 拒绝；
    相邻两边（右+下）可满足，只有 **1 个**落点。
  - 实测落点数（16 内容）：板 16/32/48 → 1/17/33（单边）；相对边、3 边、4 边 → 0；同尺寸板任何组合 → 1。
- **按键占用表**（改动前先看这里，别撞车）：原版 `GuiICWorkbench` **完全没有** `keyPressed` 覆写；
  `PrefboardNode.keyPressed_Impl` 是一个只有 `case 1`（ESC）的 `tableswitch`。我们自己占用的
  LWJGL 码：Ctrl 组合 46=C / 45=X / 47=V / 30=A / 44=Z / 21=Y，单位键 35=H、211/14=Del、1=Esc。
- 0.4.7 起几何计算集中在 `icwbe/Grow.java`，**不引用任何 MC/PR 类型**，可以直接 `javac` 单独编译
  并在游戏外跑用例（devpack 的 `tools/verify_growth.py`，覆盖边界/四角/上限/IO 冲突/
  非 16 倍数的旧板/`containsIO`/`countOffBoard`/`nextPlate`）。改动这块逻辑先跑它，别靠进游戏手测。

## 界面备忘

- **操作说明页（H 键）**：内容是 `Lang` 的 `help.body` 一整串字符串，**结构由代码推导，不在文案里写死**。
  `HelpLayout.parse` 按"行首是 `【` 或 `[`"切成区块（中英各一套标记），标题去掉括号，条目去掉缩进；
  `GuiICWbEx.drawHelp` 负责量高度、分配到列、绘制。
  - **为什么要拆开**：旧版是**一坨平铺文本**——区块名写在正文里且和正文同色同缩进（没有层次）、
    区块之间零间距、而且是**单列**，那些行只有 20 来个字，宽度用不到一半；单列的代价是 30 行放不下，
    行高被压到 **7px**，比字体本身的 9px 还紧 —— 这就是"过于拥挤"的来源。现在区块有青色标题 +
    半透明分隔线、条目缩进、块间空一行，并且**按需要分 2 列**。
  - **列数与行高是一起解出来的**：`HelpLayout.distribute` 贪心分配到各列（保持顺序、**区块不拆**），
    `maxColumnLines` 给出最高列，`lineHeightFor` 反推能放下的行高。外层从 1 列试到 3 列，
    **一旦达到 `PREFERRED_LINE_H`（9px，正好一个字体行高）就停**，即"列只有在文字会被压得比字体还紧时
    才增加，不是为了填满宽度"。同分时保留更少（更宽）的列。
  - 实测（中文正文）：427×240 → 2 列/8px；**480×270（1080p 自动档）→ 2 列/10px**；
    640×360 → 1 列/9px；854×480 → 1 列/11px。全部正好放得下（旧版在 427×240 是 1 列/7px 且溢出）。
  - `HELP_MIN_COL_W = 96`：列太窄会让中文一行装不下十来个字，比"高一点"更难读，所以宁可压行高。
  - **换行只有一份实现**：`GuiICWbEx.wrapText(..., draw)` 同时负责测量和绘制（`draw=false` 只数行）。
    测量和绘制一旦不一致，区块就会重叠 —— 所以提示区（`hint.*`）也走它，只是带 `maxLines` 上限。
  - ⚠️ `Theme.header()` 用的是 **`PANEL_INNER`**（`#121216` α112，深色），不是名字很像的
    `PANEL_HEADER`（`#DEE6F0`，浅色）。青色标题压在深底上对比度是够的；**别看名字选颜色**。
  - 布局是纯逻辑（无 MC 类型），可离线跑：devpack 的 `tools/verify_helplayout.py`
    （用**真实的中英 help.body** 驱动，96 条用例覆盖切分/分配/列高/行高/四种屏幕尺寸 +
    `StatusLine` 的定位与预算）。改这块先跑它，别靠开 GUI 眯眼看。
- **状态行在顶部条带，提示留在右侧**（0.4.7）。两者性质不同，所以分开放：
  | | 内容 | 位置 | 理由 |
  | --- | --- | --- | --- |
  | **状态** `st.*` | 长、单行、瞬时 | **顶部条带** | 最长中文 349px / 英文 798px，需要横向空间 |
  | **提示** `hint.*` | 短、天生多行、常驻 | 右侧 62px 窄列 | 最长一行 64px，窄列正合适 |
  以前两者挤在同一个 62px 窄列里互相顶替；现在**互不遮挡**（读状态时提示还在）。
  - **顶部条带 = 画板视口上方**：原版把画板放在 `ClipNode(7,18,252x197)`，所以条带是
    **y=0..17，正好 2 行 9px**。状态从 y=1 画到 y=18，**刚好不越过画板上沿**。
    ⚠️ 这也被绘制顺序强制：`drawBack` 在子节点**之前**执行，写到 y≥18 会被画板盖掉。
  - **起点要躲开原版标题**：标题画在 `(8,6)`，来自 `I18n`，**中英宽度差很多**
    （中文 49px / 英文 168px），所以起点是运行时量出来的，不是写死的。取不到翻译时会返回 key 本身
    （极长），此时起点被 clamp 到宽度的 1/3，否则状态就没地方了。
  - ⚠️ **62px 窄列是"截断"这个 bug 的根源，位置只是表象**。原来把一条粘贴能触发的三条通知
    （扩版 + 搬 IO + 保护线缆）**无条件拼在一起** = 中文约 566px / 英文约 924px，在 62px 下需要
    **10~28 行**而上限是 7 行 ⇒ 渲染器**静默**从句中切断。修法是两条：搬到顶部（宽度 ×3.7）+
    **按预算收条数**（`StatusLine.fitFlags`：逐条累加、超预算就**整条不收**，绝不收半条）。
    实测中文全部 1~2 行无截断；英文组合也 2 行放下，只有**英文单条超长消息 + 大 GUI 缩放**
    （427/480 宽）才会出现省略号 —— 那是可见的、诚实的降级，而不是无从察觉的截断。
  - 定位与预算的纯逻辑在 `icwbe/StatusLine.java`（无 MC 类型，可离线测）；`GuiICWbEx` 负责用
    真实字体量宽度再传进去。**长消息的显示时长也按长度延长**（`say()` 里
    `ttl = STATUS_TTL_BASE(200=10s) + 4×字符数`，上限 +`STATUS_TTL_BONUS_CAP`(500=25s)；
    原来是固定 110=5.5s，长消息根本读不完）。
- **操作说明页会翻页**（0.4.7 第二版）。第一版解出列数与行高来适配窗口，但**兜底分支仍把每一行都画出来**
  —— 小 GUI 下内容比面板还高，文字就**画到面板外、跑出屏幕**了。现在：
  - 面板**顶到窗口顶部并占满整个窗口高度**（原来是居中 + 高度被 clamp，纯属浪费）。
  - 内容**逐行**构建成条目表，按 `helpScroll` 偏移绘制，**只画落在可见窗口内的行** —— 逐行裁剪，
    不需要 GL scissor。
  - 滚轮翻页（`mouseScrolled_Impl` **最先**拦截 `helpVisible`；`helpScroll += ±HELP_SCROLL_STEP(3)`），
    右侧一条细滚动条，页脚显示 `滚轮翻页 x/y`。H 打开 / Esc 关闭都会把 `helpScroll` 归零。
  - 翻页的数学在 `HelpLayout`（`visibleLines` / `scrollMax` / `clampScroll` / `scrollable`），可离线测。
  - ⚠️ **`helpBlockLines`（测量）与绘制条目表必须逐行一致**：标题各算 1 行做分隔线；列间间隔只在
    "块之间"加，**最后一块后面不加**。差一行就会让最后一行画到内容区之外 —— 正是要修的那个溢出。
    改这两处时务必一起改。
  - 实测：480x270（2 列/9px）、640x360（2 列/11px）、854x480（1 列/11px）**一页放得下**；
    只有 427x240 需要翻页（多 11 行），且行高仍保持 8px 可读下限（旧版是靠压到 7px 硬塞，仍溢出）。

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

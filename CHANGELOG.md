# Changelog

## 0.4.7 (2026-10-01) — 扩版重构（16 格为单位）+ "粘贴不写边框" + 界面改版

本版把"蓝图自动扩版"整条链路重做了一遍，并按用户实测反馈反复收紧了"哪些元件可以落在边界上"的规则；
同一版里还重排了操作说明页、把状态行搬到顶部条带。**没有改动 `.icbp` 蓝图文件格式**，旧蓝图照常读。

### 一、扩版（粘贴触发）

- **取代逐格扩展，改为以 16×16 板块为单位**。旧实现是 `max(当前, 需要值)`，会做出 20×17 这种非网格尺寸；
  现在是 `roundUp16`，尺寸只可能是 16/32/48/64。**上限严格 64×64**（原版 `NewICNode.maxBoardSize = Size(4,4)`，
  单位 16），超过则拒绝粘贴并提示。
- **触发条件改为"这次粘贴会不会做出非法板"**。判定来自原版字节码：
  `CircuitOp$.isOnBorder = x==0||y==0||x==w-1||y==h-1`；`OpGate.canPlace = !isOnBorder`（**门不许碰边框**）；
  `OpIOGate.canPlace = isOnBorder && !isOnEdge`（**出入口必须在边框、且不在四角**）。
  所以「贴出板外」或「门会落在右/下边框圈上」才触发扩版；线材/火把/拉杆/按钮落在边框**不触发**
  （原版就没限制它们）。
- **扩版后边界上的出入口会被搬到新边界**（`moveEdgeIOs`）。根因：`IOGateICPart.getIOSide()` 直接返回
  `rotation()` —— **IO 没有独立字段，它的身份就是它站的格子**，板子一大就落到中间、成为非法板。
  修法是搬元件（`removePart` + `setPart`，保留 `rotation`，即保留它代表 IC 的哪一面）。
  **顺序是 `size_$eq` → 搬 IO → 再落笔**：先放大（`setPart` 会断言边界），再搬（否则粘贴会覆盖还没搬走的 IO）。
- **左/上边界是例外**：`size_$eq` 只换一个 `Size`、不平移内容，所以板子再大 `x==0`/`y==0` 仍是最外圈，
  **扩版救不了左边的门**，规划器对此不触发扩版。
- 几何全部集中在新的 `icwbe/Grow.java`，**不引用任何 MC/PR 类型**，可离线编译单测
  （devpack `tools/verify_growth.py`，用真实源码 + jar 内字节码各跑一遍）。

### 二、粘贴与边框规则（多轮收紧）

- **剪贴板含 IO 时不扩版**。理由同上：IO 必须落在边框上，扩版会把边框推开、把 IO 留在中间。
- **粘贴永不写入边框（四角计入），出入口例外**。这是本版补上的最后一处漏洞，也是最隐蔽的一处：
  - 修复前：**左/上**边框的门无人拦（扩版只往右/下长），**四个角**连保留规则都躲过。
  - 根因是**把两个原版判定当成了一个**：规则用了 `isOnRing`（= `isOnBorder && !isOnEdge`，排除四角），
    而门要的其实是 `isOnBorder`（**含四角**）。现已拆成 `Grow.isOnBorder` / `Grow.isOnRing` 两个函数。
  - 右/下的门先由扩版"救回"内部，其余边框格由粘贴兜底丢弃，并计数提示。
  - ⚠️ **这条比原版严**：原版只拦门，线材本来允许贴边 ⇒ **靠边铺线现在只能手动放，不能粘贴**。
    要收窄成只拦门，把 `Clipboard.stamp` 里的 `!Grow.isIO(id)` 换成 `Grow.isGate(id)` 即可。
- **含 IO 的粘贴框自动吸附到边界**。逐个 IO 检查"是否落在边框上、且整块内容在板内"，把粘贴框吸附到
  最近的合法落点（预览与落笔走同一个 `snappedOrigin`，框画在哪就贴在哪）。
  **没有任何合法落点时在进入粘贴模式前就拒绝**并说明原因，而不是等玩家点下去才失败：
  - IO 不在内容边缘上 → 永远够不到边界；
  - 相对两条边都有 IO（要求内容宽度同时等于 0 和板宽）、三边、四边 → 在更大的板上无解；
  - 相邻两条边（右+下）可满足，只有 1 个落点。
  - ⚠️ **代价**：成品电路（四边都有 IO）基本只能贴回同尺寸原位；旧行为是"贴上去、超出的丢弃"。

### 三、界面

- **操作说明页（H）改为分块布局**：区块标题着青色并带分隔线、条目缩进，块间空一行；
  内容按块分配到多栏（块不会被从中间切开），列数与行高**一起解**以求可读行高（不再压到 7px 硬塞）。
  布局逻辑抽到 `icwbe/HelpLayout.java`（无 MC 类型，可离线测）。
  **文案一字未改**，结构由代码从 `【】`/`[]` 推导。
- **说明页支持滚轮翻页**：面板占满窗口高度，内容逐行构建、**只绘制可见窗口内的行**（逐行裁剪，不依赖 scissor），
  右侧细滚动条 + 页脚显示进度。上一版"装不下就把每一行都画出来"会导致文字画到面板外、跑出屏幕，已修。
- **状态行移到顶部条带**（原来在右上角 62px 窄列里）。根因不是位置而是**可用宽度**：
  一条粘贴能拼接 3 条消息，中文约 566px、英文约 924px，在 62px 列里需 10~28 行而上限是 7 ⇒
  渲染器**从句中静默截断**。现在宽度约为原来的 3.7 倍，且**起点在运行时避开本地化的机器标题**
  （中英宽度差很多）。拼接改为**按预算逐条累加、整条收或不收**，不再收半条。
  常驻操作提示留在右侧窄列（它本来就适合窄列多行），且不再被状态消息盖掉。
- **状态停留时间加长**：基础 110 → **200 tick（10 秒）**，并按文本长度加成（+4/字符，上限 +25 秒）。

### 四、移除

- **G 键扩版已整体回退**。它当初是为了绕过"含 IO 不许扩版"而加的，结果两条规则互相打架
  （粘贴禁止为含 IO 的内容扩版，而 G 键又在扩版时把 IO 往外搬）。用户实测后判定自相矛盾，要求一并移除。
  `Grow.nextPlate()` / `GuiICWbEx.doGrowBoard()` / `keyPressed_Impl` 的 `n==34` 分支 / `st.grow_board`、
  `st.grow_max` 文案全部删除。
  ⚠️ **由此产生的现实**：含 IO 且有内容的板子现在**没有扩版手段**（粘贴被规则挡住、原版"新建 IC"对话框
  会清空元件、G 键已移除）。这是明确选择的取舍。

### 验证与产物

- `tools/verify_growth.py`（扩版几何）**109 条**、`tools/verify_helplayout.py`（说明页布局 + 状态行预算）
  **125 条**，均用**真实源码与 jar 内打包字节码**分别跑通。
- 产物 `ICWbEx-0.4.7.jar`；版本号、`mcmod.info`、Lang 帮助标题均升到 0.4.7。

## 0.4.6 (2026-10-01) — 修"Shift 拖动画板时擦除仍生效 → 擦错位置"

- **用户报告**：和原版冲突 —— 按住 Shift + 左键在原版是**拖动画板**，此时擦除仍然生效，导致**擦除错位**。
- **根因（javap 实证）**：原版的画板平移是 `mrtjp.core.gui.PanNode`（GUI 树为 `ClipNode → PanNode → PrefboardNode`），
  它的 `dragTestFunction` = `Keyboard.isKeyDown(42)`（**左 Shift**），平移逻辑跑在 `PanNode.frameUpdate_Impl` 里。
  而 ICWbEx 的 `applyIntercept()` **只关掉 prefboard 的交互，从不碰它的父节点 PanNode** —— 于是 Shift 拖动时画板真的在动，
  而擦除框是按**按下时的屏幕坐标**记的，松手时仍按那个框删除 ⇒ 删到了已经滑走的位置。
  另一个独立缺陷：即使画板因为别的原因（滚动条、缩放）移动，框也照样生效。
- **修复**：
  1. `intercepting()` 改为 `eraserMode() && !shiftHeld()` —— **按住 Shift 时不再接管鼠标**，把操作完整还给原版（平移）；
     擦除的点击分支同样要求 `!shiftHeld()`，Shift 点击不再开启擦除框。
  2. 新增**画板移动判定**（`beginGesture` / `boardMovedSinceGesture` / `gestureAborted`）：框选开始时记下
     prefboard 的 `position()` 与 `cellPx()`；拖动过程中只要画板被平移或缩放了，就**放弃这次框选**，
     松手时什么都不删，并提示"画板移动或缩放了，已取消这次框选（避免删错）"。
  3. 预览同步：手势被放弃后不再显示红色危险色与"将删除 N 个"计数，Shift 按住时也不显示悬停描边。
- 版本 → 0.4.6；Lang 新增 `st.marquee_moved`、标题升 v0.4.6；build.py 新增三条断言
  （`intercepting` 必须查 `shiftHeld`、画板移动守卫与提示必须存在、手势簿记必须存在）。

## 0.4.5 (2026-10-01) — 回到 0.4.3 的擦除方案：补上"删除预览"与"缩放"

- **背景（用户实测 0.4.4 后决定搁置）**：0.4.4 把原版**放置**工具也接管了，实测暴露四个问题 ——
  放置/删除**没有预览**、**中键与右键失效**（元件只能放下、无法右键打开配置）、**放置位置错位**。
  用户判断这版风险过高，要求**退回 0.4.3 的基础**，只修两个具体问题：
  **擦除时看不到自己选中了哪些元件**、**选中擦除后滚轮缩放失效**（导致没法自由挑选要删的多个元件）。
- **回退**：删除全部放置接管代码 —— `placerMode()` / `placeWithOp()` / `forwardToPrefboard()` /
  `forwardPlacerRelease()` / `placer*` 字段，以及 `stubs` 里的 `SimplePlacementOp`、`OpGateCommons`、
  `IntegratedCircuit.sendOpUse`。放置回到原版行为，右键开配置界面、中键拾取一并恢复。
- **修复 1：缩放**。新增 `GuiICWbEx.mouseScrolled_Impl`：当 ICWbEx 收走了鼠标（`intercepting()`，
  即选中擦除或按住 Ctrl）时，用节点自己的 `PrefboardNode.incScale()/decScale()` 驱动缩放 ——
  步长 0.2、夹取 0.5~3.0 与原版一致，且**不需要任何坐标换算**（原版 `mouseScrolled_Impl` 要把光标
  过两层坐标系，正是这类转发最容易出错的地方）。没被收走时不介入，原版行为不变。
- **修复 2：删除预览**。擦除框选时逐格用**红色危险色**标出"松手就会删掉"的元件，框用危险色描边，
  并在框旁打印 `将删除 N 个元件`；未按下时，光标下的元件也会被红色描边，单击不再是盲操作。
  格子来自**与真正删除完全相同的换算**（同一套 `gridXAt/gridYAt`，并夹取到板子范围），
  所以预览不可能与实际结果不一致。
- **顺带**：`Sync` 里去掉 0.4.4 那三个放置看护（`noteOpSent/followSentOp/forceFollowSentOp`，
  其中 `forceFollowSentOp` 会在暂存编辑前把差分基准重设成"当前板"，**会让这次编辑差分出"没有变化"**），
  只保留 `followLocalBoard`，并新增 `watchArmed()` 门：**看护窗口内绝不跟随**（那段时间本地板
  故意与服务器不一致）。
- 版本 → 0.4.5；Lang 去掉已无用的 `st.place_na`、新增 `st.erase_preview`，标题升到 v0.4.5；
  build.py 断言同步（新增缩放/预览跟随断言，移除放置断言，并断言 0.4.4 的放置看护必须已删除）。

## 0.4.4 (2026-10-01) — 原版**放置**工具也接管了（已搁置，见 0.4.5）

- **用户诉求**："放置也能换成我们的吗，这样就和最开始的理念差不多了，一个脚本模组"。
- **为什么值得做**：放置本身从不是危险动作（op 只会创建零件，两端执行同一个 op，不删任何东西）。
  危险的是**时机**——原版路径在鼠标松开的那一刻执行，包括落在两阶段提交的收敛窗内：
  - `Sync.pump()` 随后会用暂存的目标板整块替换板子，**刚放下的零件被无声吞掉**（"有时候不生效"）；
  - 更糟的是差分基准（`lastSnap`）还停在**放置之前**的板上 —— 于是"放置后立刻擦除"会被算成
    **没有变化**，删除永远发不出客户端，而服务器保留该零件；服务器下一次为它发状态帧就会走进
    PR 的 subID=0 兜底 → 正是我们一直在消灭的那类断连。
- **修复**：
  1. 选中放置类工具时（`SimplePlacementOp` / `OpGateCommons` / `OpWire` 三个基类的 `instanceof`），
     `GuiICWbEx.intercepting()` 把鼠标从板面收回来，左键由我们驱动；
  2. 执行走**原版唯一入口** `IntegratedCircuit.sendOpUse`（`PrefboardNode.mouseReleased_Impl` 调的就是它）
     —— 门的 subID/朝向、线材的颜色与自动连接形态全部由 op 自己决定，**我们没有重实现任何东西**；
  3. `Sync.busy()` 期间拒绝（避免被 pump 覆盖），并报"这里放不下"而不是静默失败；
  4. 新增 **placement watch**（`Sync.noteOpSent / followSentOp / forceFollowSentOp`）：op 发出后本地板
     要等服务器回显才更新，这段窗口里边差分基准会跟着本地板走（零件数变化触发，100ms 节流）；
     窗口内若有新编辑，`stageEdit` 先强制拉平基准再算差分。
- **保留的原版行为**：右键**转发回原版板面**（打开零件自己的配置界面）、中键拾取工具照常、
  **hover 与拖拽预览照常**（按下时把事件也转给原版板面，让它记录 `leftMouseDown`/`mouseStart` ——
  原版的预览就挂在这两个字段上；松开时先把自己发过的 op **遮起来**再转发，原版只负责收尾，不会重复放置）、
  单击仍然只放一个零件。
- 新增桩 `SimplePlacementOp` / `OpGateCommons`、`IntegratedCircuit.sendOpUse`、
  `PrefboardNode.mouseClicked/mouseReleased/mouseDragged/currentOp_$eq`。

## 0.4.3 (2026-10-01) — 原版擦除工具改成"安全擦除"（框选/单击直接走两阶段删除）

- **用户两个诉求**：
  1. "删了哪个元件又崩了" —— 提供日志定位；
  2. **"原版的擦除能变成我们的框选删除吗，选择原版擦除，框选或单击就直接触发框选删除"** —— 本版实现。
- **崩溃取证（`fml-client-latest.log` 12:40–12:45）**：
  ```
  12:44:17  phase2 done: local "测试1" 16x16/174p        ← 载入蓝图，成功
  （12:44:17–12:44:45 之间 ICWbEx 没有任何 stageEdit 记录）
  12:44:45  couldnt find part @[1 1] @[1 2] @[4 1] → critical exception → Invalid gate subID: 0
  ```
  崩在 `SequentialGateICPart.assertLogic`。**关键：那 28 秒里没有任何 `[ICWbEx]` 同步记录** ⇒ 你用的是**原版擦除工具**，它绕过了 ICWbEx 的两阶段同步。
- **根因**：原版工具（`ICToolsetNode` 选中的 `CircuitOpErase`）直接把 op 写到本地板并发出，没有任何停泊/两阶段保护。于是"客户端已删、服务器还在为那格发尾帧"这个窗口重新敞开：若被删的格子旁边（或被删的本身就是）会发 key&gt;10 帧的门，PR 的 `readPartStream` 兜底就用 subID=0 重建它，下一帧 `assertLogic()` 直接断连 —— 与 0.3.5 修的是同一个洞，只是入口换成了原版工具。
- **修复（0.4.3）**：把原版擦除接管成安全擦除。
  1. 新增 `GuiICWbEx.eraserMode()`：`pref().currentOp() instanceof CircuitOpErase`（PR 里 `CircuitOpErase.getOpName()` 返回 `"Erase"`；**用类型判断而不是字符串**，PR 改文案不影响）；
  2. `intercepting()` 增加 `eraserMode()` ⇒ 选中擦除时把鼠标从 `PrefboardNode` 收回来（与 Ctrl 框选同一机制）；
  3. 左键按下开始拉框，松开时走 `eraseRect()`：矩形内**所有**有零件的格子（**包含集束缆** —— 选择框选会跳过缆，因为"粘贴集束缆"是同步唯一不能重放的东西，但"删除缆"完全没问题）交给新的统一出口 `deleteKeys()`；
  4. `deleteKeys()` 就是原来 Del 键的逻辑（`removePart` → `refreshErrors` → **`Sync.toServer`** → undo 提交），`doDelete()` 也改成调它 —— 两条路再也不会分叉。
  5. **单击 = 1 格矩形** ⇒ 单击擦一格的原有手感完全保留，只是不再裸发 op。
- 版本号 → 0.4.3。

## 0.4.2 (2026-10-01) — 擦除后零件"自己回来"：看护（settle watch）在撤销你的删除

- **用户报告**："这么删除操作有时候会有假东西在上面？表现为擦除集束线缆后，擦除选择的几根线缆不与其他线缆链接，同时占的位置也无法操作放置新元件"。
- **日志铁证（12:32–12:34，0.4.1 会话）**：
  ```
  12:33:xx  local board lost 3 part(s) to a late echo; restored them from the committed board   ×6
  stageEdit: before "BEC蜂群选择器" 32x32/357p -> target "BEC蜂群选择器" 32x32/371p removals=0
  push:      before "BEC蜂群选择器" 32x32/357p board  "BEC蜂群选择器" 32x32/371p
  ```
  你擦掉 14 个件（371 → 357），**看护把零件从旧快照里补了回来**；接着下一帧的差分看到"357 → 371"，把这次改动判成**纯新增**（`removals=0`）⇒ 你删掉的件重新出现、占着格子、还挡住新元件的放置。
- **根因**：`Sync.checkSettle` 的判据是"**本地零件数少于已提交板就补**"（0.3.6 放宽成"少于就补齐缺的格子"）。而"少了几件"恰恰就是**玩家正常删除**的表现 —— 看护分不清"被迟到回显偷走"和"玩家自己删的"，于是把玩家的删除当异常修复掉。
- **修复（0.4.2）**：把看护收到它唯一真正该管的场景上：
  1. **只在"这次提交发过整板 desc"时启用**（`watchDesc`）—— 只有 desc 会被 ProjectRed 回显、被 `readDesc` 整块替换本地板，而 ICWbEx 只在**改尺寸**时发 desc（同尺寸编辑一律纯 op 重建，没有任何回显能替换本地板）；
  2. **只在本地板被清空（`have == 0`）时才修** —— 那是 desc 回显的特征。任何"少了一部分"都视为玩家自己的编辑，**绝不干预**；
  3. 窗口 20s → 5s（尺寸变化的回显很快），日志改成打印 `have -> need` 两个数字。
- 版本号 → 0.4.2。

## 0.4.1 (2026-10-01) — 补齐 0.4.0：活板不再被改名；无可信基准时不许把板排进队列

- **背景**：0.4.0 复测日志体检（会话 12:15:34 启动）。**致命项依旧全为 0**（`Invalid gate` / `critical exception` / `local board lost` / `staged apply failed` / `assertCoords` / `EncoderException` / 断连 均为 0）；原版"新建 IC"对话框被接管 **7 次**（含你新建的 48×48 / 64×64 板，都走完两阶段）。
- **但 0.4.0 只修了一半**，日志里仍出现一次：
  ```
  12:17:37  board switch queued behind the running sync (target "测试1" 32x32/371p)
            ← 32x32/371p 是另一个蓝图的内容，却挂着 测试1 的名字
  ```
- **根因（两层）**：
  1. `foldName` 虽然不再折到"旧的 pendTarget"，但它**仍然改了活板的名字**（`ic.name_$eq`）。而两阶段提交期间**活板就是我们回滚到的那块板**，给它改名字就造出了"名字是 A、内容是 B"的板。
  2. 这份混合板随后被 `Replay.snapPure` 快照成 `incoming`，而那一刻 `before` 不可用（`lastSnap == null`），无法用 `isRollbackEcho` 认出它是"回滚板"⇒ 被当成"用户的新切换"排进 `pendNext`（并且 `ic.load(null)` 抛异常被吞，日志打印的还是那块混合板）。
- **修复**：
  1. **`foldName` 永不再动活板**——名字只写在"即将应用的那块板"（`pendNext ?: pendTarget`）上，它本来就会在 pump 应用时成为活板，也正是 `Blueprints.save` 通过 `Sync.boardBeingSynced` 取来落盘的那块。
  2. **`before == null` 时不再排队**：没有可比的基准就分不清"载入的第二次调用（带回滚板）"和"真的又切了一次"，排队会把回滚板重新铺回去、把载入撤销（0.3.3 的老毛病）——改为丢弃并记一行明确的日志 `call while pending with no diff base = dropped as echo`。
  3. 队列日志改成**同时打印"被排队的板"和"基准板"**（`... (queued <X> over base <Y>)`），下次再有异常一眼能看出来源（原来只打印活板，正好掩盖了这次的错配板）。
  4. build.py 新增断言：`foldName` 里**不得出现** `name_$eq`（活板不许改名），必须保留标签改名；`no diff base` 守卫与双板日志必须存在。
- 版本号 → 0.4.1。

## 0.4.0 (2026-10-01) — 连点切换时"名字与内容错配"+ 覆盖确认补看尺寸（0.3.9 复测的日志体检发现）

- **背景**：0.3.9 复测"看着没问题了"，日志体检：`Invalid gate` / `critical exception` / `local board lost` **全部为 0**（对比 0.3.8 会话里的 14 次看护恢复 + 一次断连），原版"新建 IC"对话框被正确接管 **6 次**（每次都是 `re-routed` → `phase1a parked` → `phase1b` → `phase2 done`）。
- **但轨迹里发现一处新问题**：快速连点切换时，板子会短暂"**带着 A 的名字、装着 B 的内容**"：
  ```
  stageEdit: before "测试2" 16x16/167p -> target "BEC蜂群选择器" 32x32/371p
  board switch queued behind the running sync (target "测试1" 32x32/371p)   ← 371 件是 BEC 的内容，名字却是 测试1
  call 2 while pending = name-only echo; target stays "测试2" 16x16/174p
  phase2 done: local "测试2" 16x16/174p                                      ← 174 件是 测试1 的内容，名字却是 测试2
  ```
  它会在下一次切换时自我纠正（下一阶段就把真正的目标板铺上），但它**恰好是 0.3.8 修过的那类"内容写进错名字的文件"的原料**——在这个窗口里保存，就会把 A 的内容存进 B 的蓝图。
- **根因**：`Sync.foldName`（0.3.4 为"loadNamed 的第二次 toServer 只带名字"而加）。它把来板的名字折到 **`pendTarget`（仍在收敛的旧目标）** 上；但快速连点时，第二次调用带的名字其实属于**更新的一次请求**（`Blueprints.load` 已把新板存进 `pendNext`，随后 `loadNamed` 只是在被回滚的板上改了名再调一次）⇒ 旧目标被冠上了新请求的名字。
- **修复**：
  1. `foldName` 改为折到**最新的板**：`pendNext != null ? pendNext : pendTarget`；并且只有在没有 `pendNext` 时才去改活板的名字（有排队时活板是旧阶段的回滚板，必须保持自己的身份）。
  2. `overwriteGuard` 增加**尺寸判据**：文件里的 `sw/sh` 与要写进去的板不一致时也要求二次确认。正常编辑从不改变尺寸（改尺寸只能走原版"新建 IC"/空白 desc，那会连名字一起换），所以这不误伤日常保存，却能挡住"内容配错名字"的那类写入。
  3. build.py 新增断言：`Sync` 必须同时保留 `foldName` 与 `pendNext`。
- 版本号 → 0.4.0（0.3.10 是 6 字符，违反 5 字符铁律，故进位）。

## 0.3.9 (2026-10-01) — 堵住"原版新建 IC 对话框"这条裸发整板 desc 的路（断连 + 世界里的板被清空）

- **用户报告**："载入蓝图后，我手动选择原版的重构有时候不生效，而且还崩了"。
- **现场（`fml-client-latest.log` 11:51:24–11:51:30）**：
  ```
  11:51:28 stageEdit: before "untitled" 16x16/0p -> target "测试1" 16x16/174p   ← 板子已经是 0 件、名字 untitled
  11:51:30 couldnt find part @[1 1] @[1 2] @[2 5]… → critical exception → Invalid gate subID: 0
  ```
  崩在 `SequentialGateICPart.assertLogic`。（该会话 11:50:47–11:51:22 还有 **14 次** `local board lost 167 part(s) to a late echo` 看护恢复，说明有东西在反复整块替换客户端板。）崩溃后看护已解除，下一次编辑就把空板写回了服务器 ⇒ **存档里 DIM180 (22,44,46) 那块 371 件的板变成 `untitled` / 16×16 / 0 件**。
- **根因：原版 IC 工作台的"新建 IC"对话框（`NewICNode`）直接发整板 desc**。反汇编 `GuiICWorkbench$$anonfun$onAddedToParent_Impl$8$$anonfun$apply$mcV$sp$1`：
  ```java
  IntegratedCircuit ic = new IntegratedCircuit();
  ic.name = nic.getName();
  ic.size = nic.selectedBoardSize().$times(16);
  tile.sendNewICToServer(ic);          // ★ 原始整板 desc，没有任何停泊/两阶段保护
  ```
  而 **PR 会把每一张收到的整板 desc 原样回显**，客户端 `readDesc` 会 `clear()` 后**整块替换本地板**。如果这一刻服务器还在为旧板发逐元件状态帧，这些帧就落到空格子上 → PR 兜底 `CircuitPart.createPart(id)`（无 subID）→ 序列/计时门的下一个 key>10 帧 → `Invalid gate subID: 0` **断连**。
  - 这解释了"**有时候不生效**"：服务器只在 `hasBP`（插了 IC 蓝图/板）为真时才接受整板 desc，别的时机点"确定"会被静默丢弃（`TileICWorkbench.read` case 5 的 else 分支），客户端与服务器从此分叉 —— 分叉本身也正是下一次断连的温床。
  - 12:01 之前 ICWbEx 已经禁掉了自己所有的 desc 出口（0.3.3/0.3.8），**唯独漏了原版这个按钮** —— 它就在我们界面树里（`GuiICWbEx extends GuiICWorkbench`）。
- **修复（0.3.9）**：
  1. `GuiICWbEx` 重写 `addChild`：凡是加进来的 `NewICNode`，都把它的 `completionDelegate` 换成我们的实现（`rerouteNewIC`）；新实现（`newBoardSafely`）按对话框里选的**名字与尺寸**造一张空板，先应用到本地，再交给 `Sync.toServer` 走**与蓝图载入完全相同的两阶段路径**（停泊还会发帧的门 → 空白 desc 带目标尺寸/名字 → 收敛后应用目标板）。行为与原版一致，只是不再裸发 desc。
  2. `Blueprints.apply` 补一道同源守卫：目标蓝图尺寸与当前板不同、而 `hasBP` 为假时**提前拒绝**并提示（否则整板 desc 被服务器丢弃，两边分叉）。
  3. `build.py` 新增 `verify_class_is_packaged()`：REPLACE 类的**内部类**必须一起打包 —— 本版新增的 `GuiICWbEx$2`（那个替换 delegate 的匿名 `ScalaH.Act`）第一次构建就被漏掉，会在用户点击"确定"时 `NoClassDefFoundError`；断言已补上。
  4. 版本号 → 0.3.9；Lang 新增 `st.newic` / `st.newic_nobp` / `st.no_bp_size`。
- **数据**：`blueprints/` 里两张 BEC 蓝图在 11:5x 被删掉了（应是面板删除键），已从 `icwbe_blueprint_backups/session-2026-10-01_105646/` 把 **371 件** 的 `BEC蜂群选择器.icbp` 放回 `blueprints/`；世界里那块被清空的板载入它即可恢复。

## 0.3.8 (2026-10-01) — 修"切换时把选中的蓝图覆盖掉"：保存路径不许拍"回滚中的板" + 覆盖两步确认

- **用户报告**："切换的时候，为什么有时会把我选择的蓝图覆盖掉，bec转换覆盖了我要选择的bec选择器"（10:32）。
- **现场取证（全部对得上）**：
  - `blueprints/BEC蜂群选择器.icbp` 与 `blueprints/BEC蜂群信号转换.icbp` 的**内容逐格完全相同**（160 格、0 处差异），只有 3 字节的文件名长度差；
  - 但被覆盖的那份**内部 `name` 标签写的是它自己的名字**（`BEC蜂群选择器`）——若只是"把 A 拷成 B"，标签会保留 A 的名字。**"名字来自名称框、内容来自另一块板"正是 `Share.saveNamed(name, ic)`（面板保存/名称框回车）写入的特征**；
  - 它的 mtime 是 **10:29:05.73**，正落在日志里 `10:29:05 stageEdit: before "BEC蜂群选择器" 160p -> target "BEC蜂群选择器" 32x32/371p` 的**那 400ms 收敛窗口内**。
- **根因（两半，缺一不可）**：
  1. **两阶段提交期间本地板是"回滚后的旧板"**（见 `Sync.stageEdit`：`ic.load(before)` 是为了让服务器还在发的状态帧都能找到自己的零件）。而保存路径 `GuiBlueprint.doSave → Share.saveNamed → Blueprints.save` 拍的就是这块活板 ⇒ **窗口里保存 = 把上一块板写进文件**。分享串导入 `Share.applyAndFile` 也会在 `apply`（内含 toServer）之后紧接着保存一次，同样踩这条。
  2. **面板的"名称"框在单击列表行时就被填成那一行的名字**（`GuiBlueprint.selectRow → nameBox.text_$eq`），而"保存"就是拿名称框里的字覆盖同名文件。双击窗口只有 350ms，慢一点就"只选中、没载入" ⇒ 用户以为切过去了，再按一次保存就把当前板写进了那个蓝图。这正是用户描述的那一幕。
- **修复（0.3.8 起 `Blueprints` 由 ICWbEx 自带一份；它是所有写文件的唯一出口）**：
  1. `Sync.boardBeingSynced(ic)`：同步在飞时，把"这次同步真正要落地的那张板"交出来（`pendNext` 优先于 `pendTarget`）。`Blueprints.save` 拍的是它，**不再拍回滚后的活板**；文件里的 `name` 也随之写成玩家要存的那个名字。
  2. `Blueprints.overwriteGuard()`：目标文件里记着的板名 ≠ 要写进去的板名 ⇒ **第一次保存只给提示**（`st.bp_overwrite`，沿用面板"再点一次删除确认"的习惯），10 秒内再按一次才真的覆盖。"点一行 + 保存"这种手滑从此不会静默生效。
  3. `Blueprints.keepPrevious()`：任何覆盖都先把被覆盖的那份拷进 `<游戏目录>/icwbe_blueprint_backups/session-<启动时刻>/`（同一会话同名只留最早一份）。加上 0.3.7 的会话开局全量快照，误覆盖最多损失一次会话的第一次改动。
- **接管 `Blueprints` 时差点抄错（重要教训）**：旧工作目录里的那份源码 `isDeadSignal()` 是"全零也算死信号"的版本，而**底座 jar 只判 `null` 与长度≠16**（javap 实证）。照抄会让**任何含集束缆的蓝图都存不了、也读不了**（`测试1/测试2` 的集束缆 signal 就是全零，且一直能正常载入）。同时底座的 `save()` 是**先 `ensureCaches` 再查死信号**，顺序反过来同样会误拦。现已逐方法比对字节码：`apply/delete/dir/fileFor/isDeadSignal/load/read/sanitize` 与底座**逐条一致**，其余方法的差异只是 javac 的分支写法（等价的 `ifeq`/`ifne` 形态）。
- **数据恢复**：那块 32×32 / 371 件的板子**还活在世界里**（DIM180 `(22,44,46)` 的 IC 工作台），因此从存档重建了 `blueprints/BEC蜂群选择器.icbp`（32×32 / 371 件，与存档逐格一致），并先把被污染的四份蓝图留底到 `icwbe_blueprint_backups/2026-10-01_1032_pre-restore/`。
- 版本号 → 0.3.8；build.py 增加断言：成品 jar 里的 `Blueprints` 必须是自带的那份、必须保留 `boardBeingSynced`/`keepPrevious`/`overwriteGuard`、必须保留底座 `GuiBlueprint`/`Share` 调用的全部公开签名、`isDeadSignal` 不许再出现 `baload`（不许再读数组元素）。

## 0.3.7 (2026-10-01) — 修"清空/缩小板子时删除清单为空"的静默 bug + 蓝图自动备份

- **现场**：0.3.6 部署后复测（`fml-client-latest.log` 10:11 启动 → 10:15:40 断连）。这次差分**全程 `removals=0`**（白名单生效 ✓），但日志里有一条致命组合：
  ```
  10:15:40 stageEdit: before "测试2" 16x16/167p -> target "untitled" 16x16/0p  removals=0   ← 板子被清空了，删除清单却是 0！
  10:15:40 push: before "测试2" 167p board "untitled" 0p                                   ← 走了"什么都没变/纯新增"分支
  10:15:40 couldnt find part @[1 1] @[1 2] ×2 → Invalid gate subID: 0
  ```
- **根因（`Replay.planRemovals` 的静默漏判）**：循环里先用 `old.remove(cell)` 取快照条目，然后 `if (part == null || oldTag == null) continue;` —— **当"这一格在旧板里有、在新板里已经空了"时，快照条目已经被 remove 掉了，却没有被记成要删除的格子**。于是：
  1. 清空板子（或蓝图变小）时 **removals 为空** ⇒ `stageEdit` 走"纯新增/无变化"分支 ⇒ **服务器从来没被告知要删掉那些零件**；
  2. 客户端已经没有那些零件、服务器还在留着它们 ⇒ 服务器继续为它们发状态帧 ⇒ 客户端那格是空的 ⇒ `readPartStream` 兜底 `createPart(id)`（无 subID）⇒ key>10（计时器/计数器）⇒ **`Invalid gate subID: 0` 断连**。
  （同一个模式在 `Replay.push` 里写对了——所以"缩小板子"的情况一直是 push 兜住的；只有 `planRemovals` 漏了。这也解释了为什么这次是"清空"而不是"切换"出事。）
- **修复**：`planRemovals` 在 `part == null` 时，只要快照里那一格有零件就把它记入删除清单（与 `push` 一致）。
- **顺带修一个数据损失**：用户那块 32×32 的 `BEC蜂群选择器` 蓝图**已经被覆盖成空板**（`blueprints/BEC蜂群选择器.icbp` 从 371 个零件变成 113 字节 / 0 零件）——09:57 那次断连把客户端清空后，面板的"保存"把那张空板写回了同一个文件名。存档、各维度、第二个存档、以及 `.minecraft/backups`（只到 9/27）里都找不到那 371 个零件，**无法恢复**。因此 0.3.7 增加**蓝图自动备份**：每个游戏会话第一次绘制 ICWbEx 界面时（= 玩家还来不及保存覆盖之前），把 `blueprints/` 整个复制到 `<游戏目录>/icwbe_blueprint_backups/<时间戳>/`，只保留最近 20 份。
- 版本号 → 0.3.7；build.py 增加 `backupBlueprintsOnce`（Replay + Lang.t 挂钩）与 5 参 `push` 的描述符断言。

## 0.3.6 (2026-10-01) — 差分改成"白名单" + 第二阶段不再补发删除

- **现场**：0.3.5 部署后复测（`fml-client-latest.log` 09:53 启动 → 09:57:18 断连），用户反馈"情况好多了，但也触发了"。日志显示**每一次载入/切换都成功了**（`phase2 done: local <目标板>` 逐条对齐），唯一一次断连发生在最后一次操作 **6 秒之后**：
  ```
  09:57:11 stageEdit: before "测试1" 174p -> target "测试1" 174p removals=18   ← 同一张板比它自己，还报 18 处"改动"
  09:57:12 phase1a: parked 18 … → phase1b: 18 removals → phase2 done: local "测试1" 174p
  09:57:18 couldnt find part @[2 2] @[3 2] @[1 2] @[1 1] ×2 → Invalid gate subID: 0
  ```
- **两个根因（0.3.5 都只修到一半）**：
  1. **差分仍在比"派生字段"**。0.3.5 用黑名单挡了已知的运行时字段，但 09:55/09:56 的日志里 **BEC 与它自己比对仍报 72 处改动**（正好是全部 72 个组合门）、测试1 与它自己比对报 18 处——说明还有派生字段留在比较里（门自己的 `shape`/`state` 这类"由连线与逻辑算出来的"值会随电路运行漂移）。
  2. **第二阶段会把自己刚建的零件再删一次**（本次断连的直接原因）。`Replay.push` 在第二阶段对"相对中间板有差异"的格子会先发 case-2 删除再发 case-1 建件；**服务器对删除会回显 partRemoved（我们的副本跟着被删），而对 case-1 建件从不回显** ⇒ 客户端永久失去那 18 个门，服务器却还留着它们 ⇒ 服务器下一次为这些门发状态帧时（计数器/计时器只在"计数的那一刻"发帧，**所以是 6 秒后**）客户端没有对应零件 ⇒ `readPartStream` 兜底 `createPart(id)`（无 subID）⇒ key>10 时 `assertLogic()` ⇒ IAE 断连。
- **修复**：
  1. **白名单差分**：`Replay.identityKeys(id)` 只比"玩家配置"——门 = `subID`+`orient`，时序门（id 8）再加 `max/inc/dec`，线材/缆 = `colour`，拉杆/按钮 = `on`，火把/其它 = 什么都不比。**其余一切字段（含以后新出现的）都不可能让一格被判成"改过"**。类型也对上：字节字段用 `func_74771_c`、整数用 `func_74762_e`。
  2. **差分自报家门**：被判"改动"的格子会打印 `diff: cell (x,y) id N differs on [字段名]`（每次最多 6 条），下次日志里如果还有误判，日志会直接点名。
  3. **第二阶段不再发任何删除**：`Replay.push(..., emitRemoves=false)`。第一阶段已经把该空的格子清干净（危险件先停泊），case-1 建件是 `setPart_do` 覆盖写、OpWire 也是 `setPart` 覆盖写，本来就不需要空格子。
  4. **提交后看护扩大**：`checkSettle` 不再只在"整板被清空"时救场，而是"只要零件数少了就按已提交的板**本地补齐**缺的那几格"（不发包，服务器本来就有），窗口 5s → 20s（因为致命帧可能几秒后才到）。
- 版本号 → 0.3.6；build.py 断言同步（去掉"禁止 getInteger"的旧断言，改为校验白名单字段与 `emitRemoves` 分支、`repairLocal`）。

## 0.3.5 (2026-10-01) — 差分不再拆"正在运行"的门 + 危险删除先"换成惰性线材"

- **现场**：0.3.4 部署后复测，日志（`fml-client-latest.log` 08:09:55 启动 → 08:11:38 断连）：
  ```
  08:11:38 stageEdit: before "测试1" 16x16/174p -> target "测试2" 16x16/167p removals=24 resized=false
  08:11:38 phase1: 24 removals
  08:11:38 couldnt find part @[1 2] / @[1 1] ×2
  08:11:38 critical exception → Invalid gate subID: 0
  ```
  也就是说：**0.3.4 之后载入/切换本身是"成功"的**，真正打死客户端的是**同一秒里的 24 个删除**。而 (1,1)、(1,2) 两个格子对照蓝图是 `测试1`/`测试2` **都有的同一个门**（id7 subID3 的 combo + id8 subID18 的 timer），只是"运行时状态"不同（`state` 20 vs 0、`tsave` 533733 vs 555406）——**它们本来根本不需要被删掉重建**。
- **根因（两层，都是本次才定案）**：
  1. **差分把"运行时状态"当成了"改动"**：门的 `state`/`state2`/`tsave`/`pelapsed`/`pmax`/`val`/`freq` 等字段是每 tick 重算的活状态（javap 逐个 `save()` 核对：`Counter.val`、`TTimerGateLogic.pmax/pelapsed/tsave`、`TExtraStateLogic.state2`、`TFreqIOICGateLogic.freq`、`CircuitGateLogic.masks/bout`…）。拿它们跟蓝图里那次保存的值比，**每一个正在运行的门都必然"被改过"** ⇒ 每次载入都把它们删掉重铺。
  2. **删掉一个"还会发帧"的门 = 必然断连**：ProjectRed 把零件的活状态流成 `[id][x][y][key][数据]`；服务器在**应用我们的删除那一个 tick** 里，上一 tick 已经排好的帧正好和删除回显**挤在同一批包里，且回显在前**。客户端先被回显删掉该格，随后读到那个 key>10 的帧 → `readPartStream` 兜底 `createPart(id)` → 这个兜底件没有 subID → 第一次 `assertLogic()` 就 `Invalid gate subID: 0` 断连。而 **key>10 只有带活状态的门会发**：Counter/Sequencer/Timer/StateCell/Synchronizer/SRLatch/ToggleLatch（11–14）与模拟/集束 IO（12）；线材、集束缆、火把、拉杆、按钮、纯组合门只在 1–5。
- **修复**：
  1. **差分只认"身份"**（id + 持久配置：subID/shape/orient/colour/cmode/max/inc/dec），忽略上列运行时字段；`samePart()` 先比 id，不同即不同。⇒ **重载同一张蓝图变成 0 操作**，切换时也只动真正不同的格子。
  2. **危险删除先"停泊"**：对要删/要换的、且**旧零件是门（id≥6）**的格子，先发一次**普通 case-1 建件包**把它换成**一根惰性合金线**（connMap=0，什么都不连、永不发帧）。case-1 服务器**不会回显**（`setPart_do`+`readDesc`，不走 `sendPartAdded`），所以**我方那一格仍是原来的门**，继续吸收服务器为它发出的最后几帧；等一个"停泊窗口"（150ms）过去，服务器那格已经是一根不会发帧的线，此时再发真正的删除 ⇒ **该格不可能再有状态帧在飞** ⇒ 兜底件不会被造出来 ⇒ 不再断连。
  3. 阶段重排：`stageEdit` 只发停泊包并挂起，`pump` 分 `phase1a（停泊）→ phase1b（删除/空白 desc）→ phase2（应用+补建）`；resize 时把**整张旧板**里 id≥6 的格子全部停泊后再发空白 desc（否则清板瞬间同样会撞上那些帧）。
  4. build.py 新增断言：`Replay` 必须保留 `CircuitPart.createPart`（停泊线材的零件来源）与 `writeDesc` 调用点。

## 0.3.4 (2026-10-01) — 蓝图载入调了两次 toServer，第二次带的是"回滚后的旧板"

- **现场**：0.3.3 加了定点日志后复测，日志把真相直接写了出来：
  ```
  stageEdit: before "BEC" 16x16/7p -> target "BEC" 32x32/371p removals=3 resized=true
  phase1: blank desc "BEC蜂群选择器" 32x32
  board switch queued behind the running sync (target "BEC蜂群选择器" 16x16/7p)   ← 排队的竟然是旧板！
  phase2: apply "BEC" 32x32/371p  →  phase2 done: local "BEC" 32x32/371p        ← 载入本来是成功的
  stageEdit: before "BEC" 32x32/371p -> target "BEC" 16x16/7p removals=273 resized=true
  phase1: blank desc "BEC" 16x16  →  phase2 done: local "BEC" 16x16/7p           ← 旧板又被铺回去了
  ```
  存档里那块板最终就是 `name='BEC蜂群选择器'`、16×16、7 个零件——**载入成功之后又被自己回滚回去**。
- **根因**：底座 jar 的蓝图载入路径其实**调了两次 `Sync.toServer`**（`GuiBlueprint.doLoad` → `Share.loadNamed` → `Blueprints.load`→`apply`→`toServer` 第 1 次；随后 `loadNamed` 为了"用文件名覆盖板名"执行 `ic.name_$eq(文件名)` 再 `toServer` 第 2 次，javap 实证）。
  第 1 次的**第一阶段已经把本地板回滚到编辑前的旧板**，所以第 2 次带过去的根本不是玩家要的蓝图，而是**那张旧板**（只是被改了名字——这也正好解释了为什么存档里旧板的名字变成了 'BEC蜂群选择器'）。0.3.1 引入的"排队"逻辑把这张旧板当成"下一次切换目标"存了下来，第二阶段完成后又把它重新暂存一遍 ⇒ **每一次载入都在成功之后被自己撤销**；连续切换几次就把工作台越切越残。
- **修复**：`stageEdit` 在"已有编辑在收敛"时先判断来板是不是**我们刚回滚到的那张板**（同尺寸 + 同零件数）。是 ⇒ 这是"只改名字的回声"，把名字并进待应用的目标里、丢弃这次调用；不是 ⇒ 才按真正的切换排队。
- 附带收益：把"名字"这条路径彻底理清了——名字是装饰性的，现在只在真正的改名回声里传递，不再触发任何重发。

## 0.3.3 (2026-10-01) — 不再用整板 desc 去送"名字"（回显会把客户端板覆盖掉）

- **现场**：0.3.2 部署后复测，日志 `[ICWbEx] board switch queued` 出现了（说明第二阶段在跑），但仍有 **776 条 `client part stream couldnt find part`（其中一次 765 条）**，紧接 `Invalid gate subID: 0` 断连；存档里 (22,44,46) 变成 `name='BEC蜂群选择器'`、**16×16、只剩 7 个零件**。
- **取证与推论**：
  1. 那 765 条帧对应 **241 个坐标**，逐一比对后 241/241 全是 BEC 蓝图的**线材格**（线材会因 connMap 变化下发帧，门不会）→ **可以解释为"客户端此时是空的、而服务器有板"**。
  2. **ProjectRed 会把收到的每一张整板 desc 立刻回显**（`TileICWorkbench.read` case 5 → `sendICDesc` → 客户端 `read` case 2 → `IntegratedCircuit.readDesc`），而 `readDesc` 会 **clear() 后整块替换本地板**。
  3. 而旧代码的 **Tier 2 / Tier 3 发 desc 的唯一理由就是"送名字"**（尺寸另有通道）：只要名字变了（= 每一次蓝图切换）就发一张 desc → 必然换来一次"本地板被替换"。
  4. 当这次回显落在我们自己的第二阶段之后，本地板就变成空板 ⇒ 服务器发帧、客户端全"找不到元件" ⇒ 兜底以 subID=0 裸建运行中的序列门 ⇒ **Invalid gate subID: 0 断连**；随后某次编辑又把这张空板当目标发给服务器 ⇒ 工作台只剩残件。
- **修复**：
  1. **只有"尺寸会不对"时才允许发 desc**（坐标越出服务器板会触发 assertCoords 中断 ICH 流，这是 desc 唯一不可替代的用途）；名字是装饰性的，不再为它付一次整板往返；
  2. 其余全部改为**纯 op 重建**（与玩家手动放置走同一批包），集束缆也走 OpWire —— 不再有任何东西回来覆盖本地板 ⇒ 同尺寸的蓝图切换/粘贴/撤销 **完全不发 desc**；
  3. 新增 **settle watch**：提交后 5 秒内记住"本地板应有的样子"，若本地板被迟到的回显清空则就地恢复（不发包，服务器本来就有），防止"客户端空/服务器全"这种必然断连的状态；
  4. 新增**定点诊断日志**：`stageEdit: before <签名> -> target <签名> removals=N resized=B`、`phase1: blank desc/removals`、`phase2: apply/phase2 done: local`、`push: ... sizeFix/hasBP`、`local board was emptied by a late echo; restoring N parts`。下次再看日志就能直接定位到哪一步。

## 0.3.2 (2026-10-01) — 修复"静默的 NBT 类型错误"：所有差分一直在读 0

- **现场**：0.3.1 部署后复测仍不行——载入蓝图后工作台被清成"16×16 只剩 7 个元件"，日志里 1000 条 `client part stream couldnt find part`，最后一条 `Invalid gate subID: 0` 断连。
- **取证**：导出存档 NBT 的**标签类型**（不是值）：`sw/sh`（板尺寸）与每个零件的 `id/xpos/ypos` 全是 **tag 1 = Byte**（javap `IntegratedCircuit.save` 实证：`i2b` + `func_74774_a`）。而 `Replay` 一直用 `func_74762_e`（getInteger）读它们——**getInteger 要求 tag 3 = Int，类型不符时它不抛异常，直接返回 0**。
- **后果（全链路）**：
  1. `Replay.index()` 把快照里**每一个零件都映射到 (0,0)**，"编辑前的板"变成垃圾；
  2. `Replay.sizeDiffers()` 读到的 `sw/sh` 恒为 0 → **每一次编辑都被判定为"扩板"**；
  3. 于是 `stageEdit` 每次都走危险分支并**发一张空白整板 desc 把服务器清空**；`push()` 也因 `ow/oh` 恒为 0 再发第二张空白 desc + 全量重建；
  4. ProjectRed 会把收到的 desc **回显**给客户端——第二张 desc 的回显比第二阶段（400ms）来得更晚，**正好盖在第二阶段刚应用的目标板上，把客户端清空**；
  5. 服务器（有板）继续发逐元件状态帧，客户端（空）全部"找不到元件"→ PR 兜底用 subID=0 裸建运行中的序列门 → `Invalid gate subID: 0` 断连；
  6. 下一次编辑又把"已被清空的客户端板"当目标发给服务器 → 工作台最终只剩几个残件。
- **修复**：
  1. 三处读取改用 `func_74771_c`（getByte）：`Replay.index()`（xpos/ypos）、`Replay.sizeDiffers()`（sw/sh）、`Replay.push()`（sw/sh）；
  2. 第一阶段那张空白 desc 改为携带**目标板的**名字与尺寸（原先取的是"回滚后"的板，等于发的是旧尺寸，这才逼出第二张空白 desc 与它的迟到回显）；
  3. **同一处比较里的第二个静默缺陷**：快照里每个零件是 `{id, xpos, ypos}` + `CircuitPart.save()` 的字段，而活零件调用 `part.save()` 只写后者（javap `IntegratedCircuit$$anonfun$save$1` 实证）——两边键集不同，`samePart()` **永远返回 false**，于是每次编辑都把全部格子当成"改了"，整板删了重铺。现在比较时两侧都忽略这三个框架键（位置身份本来由 map key 承载）；
  4. build.py 断言反向更新：**禁止** Replay 出现 `func_74762_e`，**必须**有 `func_74771_c:(Ljava/lang/String;)B`——这类"名字对、类型错、静默返回 0"的 bug 只能靠断言守住。
- **教训**：`func_74762_e`（getInteger）与 `func_74771_c`（getByte）在 0.2.7/0.2.8 已坑过一次（桩的描述符写错 → NoSuchMethodError）。0.2.8 把调用点改成 getInteger **修掉了崩溃，却换来静默返回 0**——比崩溃更糟：崩了看得见，0 看不见。**读 NBT 前先确认标签类型**（本仓库用 `tools/` 下的类型导出脚本可现场取证）。

## 0.3.1 (2026-10-01) — 修复蓝图面板内切换蓝图毁板（两阶段提交的"第二拍"没人推）

- **现场**：0.3.0 部署后反馈"蓝图功能几乎失效——载入后要么有线缆但没元件，要么完全没反应"（存档 DIM180，坐标 (22,44,46) 的 IC 工作台）。
- **取证**：导出该工作台的存档 NBT，与 `blueprints/BEC蜂群选择器.icbp` 逐格比对——**板上恰好是那张蓝图 269 根线材（117 红石线 + 55 绝缘线 + 97 集束缆），而 102 个门（20 IO 门 / 72 简单门 / 10 阵列门）一个不剩**，且仅存在于蓝图、仅存在于板上的格子都是 0（不是被改坏，是被整批删掉）。线材 connMap 与蓝图一致（说明门当时在），另 82 处的 connMap 比蓝图小 1~2 个方向位（说明门是**之后**被删的）。板名仍是上一张板的 '测试1'（差分式 push 不携带名字）。
- **根因**：两阶段提交的第二阶段（`Sync.pump()`）**只由 `GuiICWbEx.frameUpdate_Impl` 推动**，而蓝图面板 `GuiBlueprint` 是独立的 GuiScreen——打开它时 GuiICWbEx 不再更新，pump 随之停摆。蓝图载入走的是同一条 `Sync.toServer` → `stageEdit`：第二阶段永不到来，于是"客户端回滚到编辑前(看起来什么都没发生)" + "服务器已经吃下第一阶段的删除"。
  为什么删掉的偏偏是门：`planRemovals` 用"去掉 connMap/signal/schedTime 后的 NBT"判同一性，线材的差异全在 connMap（被忽略）→ 永远判等；而门的 `state`/`tsave`/`pelapsed` 是运行时字段、不在忽略名单里 → 运行中的门永远判"变了" → 全部门进入删除清单，只等第二阶段把它们重建回来。第二阶段不来，门就永久消失了。
- **修复**：
  1. **pump 增加驱动器**：`Lang.t()` 顶部调用 `Sync.pump()`（空转时只是一次 volatile 读）。每个 ICWbEx 界面的绘制路径都会走 Lang.t——`GuiBlueprint.drawBack_Impl` 每帧就有 5 处（javap 实证）——于是无论当前是哪个界面，第二阶段都能落地。
  2. **切换蓝图不再与进行中的编辑打架**：上一笔还在收敛时又来一次整板切换（蓝图/分享串），不再拿已经过期的 base 做 diff（那会跳过两端共有的元件，而服务器恰恰刚把它们删掉——这正是"整排门消失"的第二重成因），而是把新板暂存下来，等上一笔在 pump 里收敛完毕、两端重新对齐后再正常暂存。语义上等价于"冷却"：连点切换只会让最后一次生效，不会丢数据。
- 存档里已经缺门的板**不会自愈**——重新载入一次那张蓝图即可补回（此时是"纯新增"分支，只会补门、不动线材）。

## 0.3.0 (2026-10-01) — 修复"Invalid gate subID: 0"的第二个触发器（乐观同步追赶窗口）

> 版本号说明：原定 0.2.10 的构建产物被构建脚本的变长版本号字节替换损坏（`0.2.6`→`0.2.10` 由 5 字节变 6 字节，撑坏 ICWbEx.class 常量池，FML 在 mod discovery 阶段即崩溃，游戏无法启动），从未发布。改发 **0.3.0**——与底座版本号等长（5 字符），字节替换恢复安全；build.py 已加"变长禁补丁 + 成品 jar javap 全量验证"双保险。

- **修复所有"删除/覆盖类"编辑的同步窗口**（现场实锤：0.2.9 环境下重贴 130 元件蜂群板，进世界约 1 分钟即断连）。0.2.7 起客户端对编辑采用乐观应用：板上立即显示结果，而 op 流要晚一拍才到达服务器。这一拍里服务器仍在跑旧板并 tick 出逐元件状态帧——客户端已经没有那些元件了，ProjectRed 的兜底逻辑用 subID=0 裸建它们，运行中序列门的第一帧 key>10 状态帧即触发 `IllegalArgumentException: Invalid gate subID: 0` 断连（0.2.9 修复的是"服务器永不跟随"的另一个毒源，本版修复"服务器慢一拍"的这个）。
- 修复方式：**两阶段提交**。删除/覆盖/扩板类编辑现在被"暂存"——客户端先回滚到编辑前的板、只发删除 op，服务器的删除回显把两端收敛到中间态；约 0.4 秒后再应用编辑后的板并回放新建 op。全程不存在"服务器在发帧、客户端却没有该元件"的时刻。纯新增类编辑保持即时生效（服务器不会为自己没有的元件发帧，该方向天然安全）。
- 暂存期间（约 0.4 秒）GUI 会短暂拒绝下一步粘贴/删除/撤销并提示"上一步还在同步"；被暂存的编辑在视觉上会先回显旧板再跳到新板。这是消除断连的必要代价。
- 其余：撤销历史完整保留（两阶段不会重置撤销栈）；同步失败的缓冲重置防御同样覆盖暂存路径。

## 0.2.9 (2026-09-30) — 修复"Invalid gate subID: 0"硬断连

- **修复未插蓝图时的同步失效**（现场实锤：单人存档一晚被踢三次）。工作台上没有蓝图时，服务器会**静默丢弃**整板 desc 包（`TileICWorkbench.read` case 5 的 else 分支）。0.2.8 在此场景仍然发送整板 desc：客户端本地已应用编辑（如清空/撤销），服务器却继续跑着旧板子——服务器的逐元件状态流此后在客户端全部"找不到元件"，ProjectRed 的兜底逻辑用 subID=0 裸建序列门，运行中序列门的第一帧 key>10 状态帧即触发 `IllegalArgumentException: Invalid gate subID: 0`，客户端被直接断连。
- 修复方式：无蓝图时改用**逐元件回放**同步（case 2 删除的回显对已删除的客户端是幂等的、case 1 建元件从不回显、线材经 OpWire 回显重建）——两端收敛，且没有任何路径能"在客户端背后删元件"。
- **扩板保护**：粘贴需要扩板时，若工作台上没有蓝图则拒绝执行并给出提示（扩板只能经由整板 desc 传输，而无蓝图时服务器必然丢弃它——强行扩板会让服务器与客户端尺寸永久错位，后续落点在服务器板外的元件会触发 assertCoords 中断整个 IC 流）。

## 0.2.8 (2026-09-30) — 首次公开发布 / Initial public release

- 粘贴保护：复制会跳过集束线缆，粘贴不覆盖集束线缆所在格并给出计数提示（经由剪贴板同步集束线缆会触发 ProjectRed 的 null-signal bug，详见 README 已知问题）。
- 安全同步（三层）：内容增量逐元件回放 → 无电缆时整板 desc → 有电缆+有蓝图时先摘电缆发"无电缆整板 desc"、再经 PR 自己的 OpWire 让服务器重建线缆（onAdded 重算信号，永不产生 null 信号）。
- 同步失败防御：失败时重置 tile 的半写流缓冲并打印完整堆栈，绝不向服务器发送残流；opId 越界防御。
- 构建体系：签名桩 + 一键构建脚本 `build.py`，内置关键调用点断言（防止签名桩与运行时 jar 不符——这类错误在内部测试版本中曾导致现场崩溃）。

## 发布前历史（2026-09-27 ~ 09-29）

0.2.8 之前的版本未公开发布。期间依次完成：初始版本与功能底座（蓝图面板、框选复制/剪切/粘贴、撤销/重做、中键拾取）；框选跳过集束线缆；已连线集束线缆不再阻止保存蓝图（全零空闲信号为合法状态）；以及一套绕开 ProjectRed null-signal bug 的安全同步协议（初版曾存在两个签名桩与运行时不符的缺陷，在 0.2.8 中连同防御机制一并解决）。


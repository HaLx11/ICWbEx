# 构建 ICWbEx

ICWbEx 是一个 Minecraft 1.7.10 的纯客户端模组，扩展 ProjectRed 的集成电路工作台。
本文说明如何从源码构建、验证与发布。

---

## 项目结构

```
src/icwbe/          源码（10 个类）
stubs/              编译期签名桩
libs/               构建底座 ICWbEx-0.2.6.jar
build.py            一键构建脚本
out/                构建产物
```

`src/` 里**不包含** `ICWbEx.java`、`GuiBlueprint.java`、`Share.java`、`Theme.java`。
这四个类以编译好的形式存在于底座 jar 中，`build.py` 只替换 `REPLACE` / `ADD` 列表里列出的类。
需要修改它们时，先从底座 jar 反编译回源码，再把它加入 `REPLACE`。

---

## 环境要求

| 项 | 要求 |
|---|---|
| JDK | `javac` 在 PATH 中，支持 `--release 8`（JDK 9 ~ 25） |
| Python | 3.6 及以上，仅使用标准库 |
| 产物字节码 | 必须是 Java 8（major 52） |

**不需要** Forge/MCP 开发环境，也不需要反混淆 Minecraft。

---

## 构建原理

1.7.10 模组通常需要完整的开发环境，本项目走了另一条路：直接在源码里以 SRG 名
（如 `func_74762_e`）引用 Minecraft / ProjectRed / CodeChickenLib 的内部方法，
编译时用 `stubs/` 下的**签名桩**顶替真实类。桩只有方法签名、没有实现，
运行时链接的是游戏 jar 里的真实类。

好处是构建依赖极轻；代价是**桩的签名必须与运行时 jar 完全一致**，
否则编译能通过、运行时会抛 `IncompatibleClassChangeError` 或 `NoSuchMethodError`。

---

## 构建

```bash
python build.py
```

脚本依次完成：

1. 编译 `stubs/` 下的签名桩（排除 `stubs/icwbe/GuiBlueprint.java`）；
2. 编译 `src/` 与那个影子桩（它引用真实的 `GuiICWbEx`，必须同批编译，但产物不进 jar）；
3. 对编译结果做调用点断言，检查关键的 SRG 名称与描述符；
4. 以 `libs/ICWbEx-0.2.6.jar` 为底座，替换 `REPLACE` 中的类、追加 `ADD` 中的类，
   改写版本串与 `mcmod.info`，再逐类 `javap` 校验；
5. 检查被替换类的内部类（`Foo$1`、`Foo$2` …）是否都已打包。

产物：`out/ICWbEx-<版本>.jar`。
构建过程中出现 `源值 8 已过时` 一类的 javac 警告属正常现象。

---

## 版本号约束

`build.py` 顶部的 `NEW_VER` **必须恰好 5 个字符**（如 `0.4.8`），与底座版本 `0.2.6` 等长。

原因是版本串以字节级补丁的方式写进 `ICWbEx.class` 的常量池：长度变化会破坏常量池结构，
FML 在识别模组阶段就会崩溃。`build.py` 对此有拦截。

发版时同步修改：

- `build.py` 的 `NEW_VER` 与 `MCINFO_DESCRIPTION`
- `src/icwbe/Lang.java` 中 `help.title` 的版本号

---

## 验证

### 离线回归

```bash
python tools/verify_growth.py        # 扩版几何，109 个用例
python tools/verify_helplayout.py    # 帮助页布局与状态行预算，125 个用例
```

两者都用**真实源码**和**成品 jar 内的字节码**各跑一遍。修改 `Grow`、`HelpLayout`、
`StatusLine` 后应当先跑它们，不必进游戏。

脚本内的 replay 逻辑需要与实现保持同步：被测逻辑改了而脚本没改，测试就会失效。

### 游戏内

将 jar 放入实例的 `mods/` 目录后启动游戏，在 IC 工作台中逐项验证：
蓝图保存/载入/分享串、框选复制粘贴、自动扩版、擦除预览、撤销重做、中键拾取、`H` 帮助页。

---

## 部署

```bash
python tools/deploy.py
```

脚本会检查游戏是否在运行（运行中则拒绝），替换 jar，校验 md5，
把旧版本改名为 `.bak`，并确认 `mods/` 里只剩一个 ICWbEx jar。

---

## 发布

```bash
git fetch origin
git rebase origin/main
git push origin main
git tag -a v<版本>
git push origin refs/tags/v<版本>
python tools/push_github.py
```

`push_github.py` 通过 Git Data API 创建 Release 并上传 jar。

---

## 开发注意事项

### 签名桩

- **class 还是 interface** 以 `javap -p` 查看运行时 jar 为准，不要依据注释或旧源码判断。
- **方法名与描述符** 以 Forge 官方映射为准：`deobfuscation_data-1.7.10.lzma` 解压后是
  obf→SRG 的 CSV（不含 MCP 名），语义需要靠描述符消歧。更直接的办法是对照 ProjectRed
  自身的字节码（`javap -c`），那是最终事实。
- 新增桩或新增调用点后，把对应检查加入 `build.py` 的 `assert_call_sites()`，
  让不一致在构建期暴露。

### NBT 字段类型

`IntegratedCircuit.save` 把 `sw` / `sh` 与每个零件的 `id` / `xpos` / `ypos` 都写成
**NBT Byte**，必须用 `func_74771_c`（getByte）读取。

`NBTTagCompound` 的 getter 在类型不匹配时**不抛异常，静默返回 0**。
读错类型不会立即出错，而是产生错误数据（例如所有零件都被解析到 `(0,0)`）。
`build.py` 对此有反向断言：成品中出现 `func_74762_e` 读取这些字段即构建失败。

### 接管底座 jar 中的类

底座 jar 里的类没有对应源码，接管时需要**逐方法比对行为**，而不只是签名。
把新旧两份 class 用 `javap -c -p` 导出指令序列，去掉偏移量与常量池序号后比较，
确认只有预期的方法存在差异。

0.4.9 起还接管了入口类 `icwbe/ICWbEx.class`（只为在 `onLoadComplete` 里多挂一个 tick
handler）。它同样适用这一条：原文只有 `MODID`/`NAME`/`VERSION` 三个常量和
`onLoadComplete`，`@Mod` 属性必须逐字保留 —— `acceptableRemoteVersions="*"` 一旦丢了，
客户端就进不了没装本模组的服务器。`build.py` 会对自编译的新条目做版本串等长替换
（原文里的 `"0.2.6"` 就是留给它的），只改了底座条目的旧实现会报
`stale version bytes`。

### ProjectRed IC 同步协议

- 服务端 `TileICWorkbench.read(in, key)`：`case 4` 为 op 流，`case 5` 为整板描述包
  （未插 IC 蓝图时服务端直接丢弃），`case 1/2/3` 分别为 hasBP、零件描述、零件流。
- `sendNewICToServer` 使用独立的 `writeStream(5)` 通道，与 icStream 缓冲无关。
- `tile.getICStreamOf(key)` 在调用瞬间就会把 key 字节写入缓冲区，且服务器每 tick flush。
  因此"取流后中途失败"必须重置缓冲，否则残留的 key 字节会被当成 opId 解析并导致 NPE。
- CircuitOp 共 61 个（ordinal 0..60），`getOperation` 没有空值判断，
  自产 opId 必须限制在 0..255。
- `OpWire.writeOp` 为 4 字节 `[x0][y0][x1][y1]`；服务端逐格放置，占用格与边界格静默跳过。

### 计时器运行态（pelapsed）

`Timer`（顺序门 subID 18）与 `StateCell`（数组门 subID 21）都经 `TTimerGateLogic` 持久化三个
键：`pmax`（设定值）、`pelapsed`（从起点到现在的 tick 数，**只有运行时才 >= 0**）、
`tsave`（墙钟兜底）。`Timer.load` 的规矩是
`if (pelapsed >= 0) pointer_start = getTotalTime() - pelapsed;`，而 `getTotalTime()` 在
"`network()` 非空、`network().getWorld()` 为 null"时会回退到
`MultipartSaveLoad.loadingWorld().getWorldTime()` —— 那个静态字段只在**从存档载入世界**
期间非 null，物品放置时是 null，于是直接 NPE（0.4.9 修的就是这个）。

因此凡是我们写出去的板（`.icbp` 文件、剪贴板、零件 desc、整板推送），**运行中的计时器
一律写成 `pelapsed = -1`**，只保留 `pmax` / `tsave`。三点务必记住：

- 按 NBT **键名**判断，不要按 id/subID：`StateCell` 经 `TExtraStateLogic` 转调同一 trait，
  同样带 `pelapsed`；而且 `getLong` 读**缺失**的键返回 `0`，`0` 也满足 `>= 0`。
- `Sequencer`（subID 19）只写 `pmax` / `tsave`，`load` 不碰 `getTotalTime()`，天然安全。
- 客户端与服务端对同一份芯片表现不同（服务端那一刻 `network() == null`，直接返回
  `tsave`），所以"服务端没事、客户端崩"是正常现象，不能据此认为芯片是好的。

### 同步层设计

`Sync` 与 `Replay` 采用两阶段提交，原因是 ProjectRed 会为每个元件持续发送状态帧：
如果某一帧到达时客户端对应格子已被清空，PR 会用 subID 0 重建该元件，
随后一个带状态的门发出 `key > 10` 帧，触发 `Invalid gate subID: 0` 掉线。

几条因此成立的规则：

- 危险改动先回滚客户端板、只发删除，待两侧收敛后再应用目标板并重建；
- 第二阶段不再重复发送删除（服务端会回显删除，但不会回显 case-1 新建）；
- 差分只比较"身份"字段（`subID` / `orient` / `max` / `inc` / `dec`、颜色、开关），
  运行期字段（计数器值、计时器进度、连接图）一律忽略；
- 删除或替换前，先把仍会发状态帧的门停泊为惰性线材；
- 看护逻辑只在确认是回滚回声时修复，不能依据"元件数量不一致"判断。

### 电路板合法性

`Clipboard.stamp` 使用 `IntegratedCircuit.setPart` 直接写入，绕过原版的放置检查，
因此"放上去了"不等于"合法"。原版规则位于 `CircuitOp$` / `OpGate` / `OpIOGate`：

| 判定 | 定义 | 约束对象 |
|---|---|---|
| `isOnBorder` | `x==0 \|\| y==0 \|\| x==w-1 \|\| y==h-1` | —— |
| `isOnEdge` | 四个角 | —— |
| `OpGate.canPlace` | `!isOnBorder` | 门不能碰最外圈（四角也算） |
| `OpIOGate.canPlace` | `isOnBorder && !isOnEdge` | IO 必须在最外圈、且不在四角 |
| `OpWire` / `SimplePlacementOp` | 无 `canPlace` | 线材、火把、拉杆、按钮可以贴边 |

补充：

- **IO 的位置就是它的身份**：`IOGateICPart.getIOSide()` 直接返回 `rotation()`，
  没有独立字段。板子尺寸变化后，原本贴边的 IO 会落到内部，蓝图立即不合法。
  搬迁 IO 用 `removePart` + `setPart`（`setPart_do` 会重新绑定坐标），不要直接改字段。
- **尺寸上限 64×64**：`NewICNode.maxBoardSize = Size(4,4)`，单位是 16 格。
  `size_$eq` 本身不做校验，上限需要在扩版逻辑中自行保证。
- **扩版只能向右、向下**：`size_$eq` 只替换尺寸，不平移内容。贴在左边界或上边界的门
  无法通过扩版修正。
- **剪贴板包含 IO 时不扩版**，粘贴框会吸附到能让所有 IO 落在边框上的位置；
  找不到合法落点时拒绝粘贴并说明原因。
- **粘贴不写入最外圈**（四角同样计入），只有 IO 例外。因此靠边铺线需要手动放置。
- PR 对"不合法"没有反馈：`refreshErrors` 只收集 `IErrorCircuitPart`，
  而模组内唯一实现是 `WireICPart` 的网络告警。边界上的门、板中间的 IO 都不会报错，
  运行期仍按 `rotation` 参与运算——这类问题不会崩溃，只会静默出错。

---

## 反编译参考

1. 从实例的 `mods/ProjRed-*.jar` 解出目标 `.class`；
2. 用 `zipfile` 重新打包成小 jar（CFR 不能直接读目录）；
3. `java -jar cfr.jar <打包>.jar --outputdir <out>`。

---

## 许可

MIT。ProjectRed 与 MrTJPCore 归其各自作者所有；本项目不包含、不修改它们的代码。

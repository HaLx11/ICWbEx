# 构建 ICWbEx

先说清楚这个项目的特殊之处：**ICWbEx 没有 dev 环境**。它是 1.7.10 的纯客户端模组，直接以 SRG 名（`func_74762_e` 这类）引用 Minecraft / ProjectRed / CodeChickenLib 的内部 API，构建时用一套**签名桩**（`stubs/`）代替真实类——桩只有方法签名、没有实现，运行时链接的是游戏 jar 里的真类。所以：

- 不需要 Forge/MCP 开发环境，不需要反混淆 Minecraft；
- 代价是：**桩签名必须和运行时 jar 逐字节一致**，否则就是 0.2.7 的下场（见下文"铁律"）。

## 前置

- JDK：`javac` 在 PATH 里即可（需要支持 `--release 8`，JDK 9~25 都行；产物必须是 Java 8 字节码 / major 52）。
- Python 3.6+。

## 一键构建

```bash
python build.py        # 产出 out/ICWbEx-0.2.8.jar
```

脚本做四件事：

1. **编译桩**：`stubs/` 下全部 `.java`（排除 `stubs/icwbe/GuiBlueprint.java`）→ `build/stubsout`；
2. **编译源码**：`src/` + 那个 GuiBlueprint 影子桩（它引用真实的 `GuiICWbEx`，必须和 src 同批；编出的 `.class` 不进 jar，运行时用底座 jar 里的原版）；
3. **调用点断言**（防 0.2.7 复发，详见下文）：`Replay` 对 CCL `MCDataOutput` 的调用必须全部是 `invokeinterface`、NBT `getInteger` 必须是 `func_74762_e:(Ljava/lang/String;)I`、`Sync` 的流重置 TraitSetter 签名必须精确；
4. **组装 jar**：`libs/ICWbEx-0.2.6.jar` 作底座，替换 7 个更新类 + 追加 `Replay.class`，把版本字节 `0.2.6 → 0.2.8`，重写 `mcmod.info`。

## 改桩的铁律（0.2.7 翻车实录，务必读）

0.2.7 曾带着两个错误桩发布，实测"第一次加载集束线缆全没了、再加载一次服务器 NPE 踢人"：

1. `MCDataOutput` 桩写成了 abstract class——**真实是 interface**（CodeChickenCore 1.4.22 内嵌的 CodeChickenLib）。invokevirtual 调接口方法 → `IncompatibleClassChangeError`。
2. NBT 桩把 `func_74771_c` 当 `getInteger(String)I`——**它真是 `getByte(String)B`**。`getInteger = func_74762_e (Ljava/lang/String;)I`（`NBTTagCompound` 唯一的 `(String)I` 方法）。

规则：

- **class 还是 interface**：`javap -p` 运行时 jar 实证，别信任何注释（0.2.7 的桩注释里就写着"verified as abstract class"，是错的）。
- **名字 + 描述符**：用 Forge universal jar 里的官方映射核对：`libraries/net/minecraftforge/forge/1.7.10-10.13.4.1614-1.7.10/deobfuscation_data-1.7.10.lzma`（`python -c "import lzma; open('srg.csv','wb').write(lzma.decompress(open(<jar内条目>,'rb').read(), format=lzma.FORMAT_ALONE))"`）。注意该 CSV 是 obf→SRG，**不含 MCP 名**——查语义要靠描述符消歧，或对照 ProjectRed 自己的字节码（`javap -c` PR 的类，抄它的调用名:描述符，那是地面真值）。
- **编译后必断言**：`build.py` 第 3 步已内建关键断言；改了新桩后，把新调用点也加进 `build.py` 的 `assert_call_sites()`。
- 运行时 NBT getter 对**类型不符的键返回默认值 0**（不是抛异常），所以读错类型不会立刻炸，而是悄悄产生错误数据——比对描述符是唯一可靠的手段。

## ProjectRed IC 同步协议备忘（CFR 反编译实证）

- 服务器 `TileICWorkbench.read(in, key)`：`case 4` = op 流（`readICStream`，循环读到 0xFF，只捕获 IndexOutOfBoundsException）；`case 5` = 整板 desc（**无 BP 时服务器直接丢弃**；有 BP 才 readDesc + echo 给观察者）；`case 1/2/3` = hasBP 布尔 / part desc / part 流。
- `sendNewICToServer` 走独立的 `writeStream(5)` 通道，与 icStream 缓冲无关。
- **`tile.getICStreamOf(key)` 被调用的瞬间就把 key 字节写进客户端 tile 的持久缓冲**（在任何 payload 之前），且 `TileICWorkbench.update()/updateClient()` **每 tick flush**。所以"先拿流再写内容"的代码中途炸掉，会在缓冲里留下孤立的 key 字节，最迟一个 tick 后发给服务器 → 服务器把 0xFF 当 opId → `CircuitOpDefs$.apply(255)` 返回 null → `getOp()` NPE。`Sync.toServer` 的 catch 里用 TraitSetter 把两个缓冲置 null 来防这个（`mrtjp$projectred$fabrication$NetWorldCircuit$$icStream_$eq`，签名 `(Lcodechicken/lib/packet/PacketCustom;)V` 必须逐字节精确）。
- CircuitOp 共 61 个（ordinal 0..60），`CircuitOp$.getOperation` **无判空**，自产 opId 必须限制在 0..255。
- `OpWire.writeOp` = 4 字节 `[x0][y0][x1][y1]`；服务器 `readOp` 逐格放置，占用格/边界格静默跳过。

## 如何自己反编译 PR 作参考

1. 从 GTNH 实例的 `mods/ProjRed-*.jar` 解出目标 `.class`；
2. 用 python `zipfile` 打成小 jar（CFR 不直接吃目录）；
3. `java -jar cfr.jar <打包>.jar --outputdir <out>`（CFR 0.152 实测可用）。

## 版本发布

- 改 `build.py` 顶部 `NEW_VER` 与 `MCINFO_DESCRIPTION`，以及 `src/icwbe/Lang.java` 里 `help.title` 的版本号；
- 底座固定是 `libs/ICWbEx-0.2.6.jar`（0.2.8 之前最后稳定的完整 jar），不要升级底座除非你清楚要重验哪些旧类；
- 产物命名 `ICWbEx-<版本>.jar`，旧的 mods 版本改名 `.bak` 保留。

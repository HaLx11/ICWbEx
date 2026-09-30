# Changelog

## 0.2.8 (2026-09-30)

**修复 0.2.7 的两个现场回归**（症状：第一次加载含集束线缆的电路后线缆全部消失并被写进存档；第二次加载服务器在 `CircuitOpDefs.apply` 处 NPE 踢人）：

- `MCDataOutput` 签名桩由 abstract class 改为 interface（真实 CCL 类型就是 interface）。0.2.7 的所有 wire-op 写入都被编译成 `invokevirtual` → `IncompatibleClassChangeError`，线缆重建 op 从未到达服务器。
- NBT 桩中 `func_74771_c` 实为 `getByte(String)B`；`getInteger` 的正确 SRG 名是 `func_74762_e (Ljava/lang/String;)I`。0.2.7 的 6 处 `getInteger` 调用全部 `NoSuchMethodError`。
- `Sync.toServer` 失败时重置 tile 的 icStream/partStream 缓冲（公开 TraitSetter 置 null）——此前半写的流会被 tile 每 tick 的自动 flush 发给服务器，服务器把孤立 key/0xFF 当 opId 解析。
- 失败日志改为打印完整堆栈；`writeWireOp` 增加 opId 0..255 越界防御。

## 0.2.7 (2026-09-29)

- 粘贴保护：复制忽略集束线缆；粘贴不覆盖集束线缆所在格并给出计数提示。
- 同步根治（三层）：内容增量逐元件回放 → 无电缆时整板 desc → 有电缆+有蓝图时先摘电缆发"无电缆整板 desc"、再经 PR 自己的 OpWire 让服务器重建线缆（onAdded 重算信号，永不产生 null 信号）。
- 已知问题：两个编译桩与运行时不符，见 0.2.8。

## 0.2.6 (2026-09-29)

- 已连线的集束线缆不再阻止保存蓝图：全零（空闲）信号是合法状态；仅拒绝损坏的信号数据（长度 ≠16）。

## 0.2.5 (2026-09-29)

- 框选无视集束线缆（它们无法在复制粘贴中存活），并给出提示。

## 0.2.1 (2026-09-28)

- 底座：蓝图面板（保存/加载/删除命名 `.icbp`、分享串导入导出）、框选复制/剪切/粘贴、撤销/重做、中键拾取、缩放。

## 0.2.0 / 0.2.2 ~ 0.2.4 (2026-09-27 ~ 09-29)

- 0.2.0：从 0.1.0 的首次整理发布。
- 0.2.2（coremod 方案）/ 0.2.3（协议回放初版）/ 0.2.4：内部迭代，均已弃用，不出现在发布列表。

## 0.1.0 (2026-09-27)

- 初始版本。

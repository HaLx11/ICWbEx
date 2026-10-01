# ICWbEx — IC Workbench, Extended

纯客户端的 Minecraft 1.7.10（GTNH）模组：给 ProjectRed 的 **IC 工作台** 加上蓝图面板、框选复制粘贴、自动扩版、撤销重做和中键拾取。**服务器不需要安装本模组**——所有操作都走 ProjectRed 自带的 IC 同步协议，不修改 ProjectRed 的 jar，也不注册自定义网络通道。

> Client-side quality-of-life add-on for ProjectRed's Integrated Circuit Workbench (Minecraft 1.7.10 / GTNH). Blueprint panel, marquee copy/paste, automatic board growth, undo/redo, middle-click pick. The server does **not** need this mod: everything goes through ProjectRed's own IC sync protocol, and the ProjectRed jar is never touched.

## 功能 / Features

- **蓝图面板**：把当前 IC 保存为命名的 `.icbp` 文件（`.minecraft/blueprints/`），随时加载、删除；支持把整块电路压成"分享串"发给别人导入。Blueprint panel (save/load/delete named layouts, share codes).
- **框选与剪贴板**：Ctrl+拖拽框选（Shift 追加），Ctrl+C / X / V 复制、剪切、粘贴；粘贴时自动保护集束线缆所在的格子。Marquee select, copy/cut/paste, bundled-cable cells protected on paste.
- **自动扩版**：把内容粘到板外时自动把蓝图扩大（以 16 格为单位，上限 64×64），并维护边界上的出入口。见下节。Auto board growth in whole 16×16 plates, capped at 64×64.
- **擦除预览**：选中原版擦除工具后框选，会红色标出"松手就会删掉"的元件并显示计数。Red preview of what an erase will remove.
- **撤销 / 重做**：Ctrl+Z / Ctrl+Y，与服务器同步安全。Undo/redo.
- **中键拾取**：对着元件按中键直接拿起它。Middle-click pick-part.
- **操作说明页**：`H` 打开，分块排版、可滚轮翻页。In-game help page (`H`), scrollable.

## 自动扩版 / Board growth

把内容粘到蓝图边界之外时，ICWbEx 会自动把蓝图扩大一档，**不需要手动改尺寸**（原版"新建 IC"对话框会清空板子，救不了有内容的板）。

When a paste lands outside the board, ICWbEx grows it automatically.

简述规则：

- **以 16×16 板块为单位**整体扩大（16 → 32 → 48 → 64），**上限 64×64**，不再逐格扩展。
- 触发条件只有一条：**这次粘贴会做出不合法的板** —— 内容贴出板外，或**门**落在右/下边框上
  （原版规定门不能碰边框）。线材、火把、拉杆、按钮落在边框**不触发**。
- **只在往右、往下扩**。左边和上边没法靠扩版补（板子再大，`x==0` 还是最外圈），这是原版几何决定的。
- **边界上的出入口会跟着搬到新边界**，不会因为板子变大而落到板子中间。
- **剪贴板里带出入口时不扩版**，粘贴框会**自动吸附到边界**，让出入口落在正确的位置上；
  如果怎么摆都放不下（比如出入口不在内容边缘、或成品电路四条边都有出入口），会直接拒绝这次粘贴并说明原因。

⚠️ 两点取舍：**粘贴不写入最外一圈边框**（四角也算），只有出入口例外 —— 这是为了保证边框上的元件不会和出入口打架；
因此**靠边铺线请手动放置**。另外，含出入口且有内容的板子目前没有扩版手段（这是有意的规则冲突取舍）。

## 安装 / Install

1. GTNH 1.7.10 实例（Java 17+ 启动）。
2. 把 `ICWbEx-x.x.x.jar` 放进 `.minecraft/mods/`。仅客户端；服务器不装。
3. 需要 ProjectRed（在 `ProjRed-4.12.44-GTNH` 上验证）+ MrTJPCore。

## 用法 / Usage

在 IC 工作台 GUI 里：`H` 打开操作说明，`Esc` 逐层退出。快捷键总表：

| 操作                     | 按键                            |
| ---------------------- | ----------------------------- |
| 放置 / 打开元件设置 / 拾取       | 左键 / 右键 / 中键                  |
| 框选（追加）                 | 按住 Ctrl 拖动（Shift 追加）          |
| 复制 / 剪切 / 粘贴 / 删除 / 全选 | Ctrl+C / X / V / Del / Ctrl+A |
| 撤销 / 重做                | Ctrl+Z / Ctrl+Y               |
| 蓝图面板                   | 右上角「蓝图」按钮                     |

## 已知问题

- **集束线缆不支持复制**：框选与复制会自动跳过集束线缆，粘贴也不会覆盖它们所在的格子。原因是经由剪贴板/整板描述包同步集束线缆会触发 ProjectRed 的 null-signal bug（空闲的全零信号在服务端被解析成 null 并导致崩溃），因此只能刻意绕开。若上游修复后，这一限制有望解除。
- **日志里的 client part stream couldnt find part 是正常现象**：由 ProjectRed 自己打印，出现在"某个元件刚被删除、而服务器同一 tick 仍在为它发状态帧"的瞬间。只要后面没有紧跟 Invalid gate subID: 0，它就是无害的协议噪音。

## 开发方式 / Development

本项目的代码由 **AI 辅助构建**。构建脚本（`build.py`）中内置的调用点断言与 [BUILDING.md](BUILDING.md) 中的协议备忘，均来自真实崩溃问题的排查结论——后来者修 bug 前建议先读它。

The code in this project was built with the help of an AI coding assistant. The build-time call-site assertions and the protocol notes in BUILDING.md are distilled from real crash investigations - worth a read before touching the stubs.

## 许可

[MIT](LICENSE)。ProjectRed 与 MrTJPCore 归其各自作者所有；本模组不包含、不修改它们的任何代码。

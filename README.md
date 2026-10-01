# ICWbEx — IC Workbench, Extended

纯客户端的 Minecraft 1.7.10（GTNH）模组：给 ProjectRed 的 **IC 工作台** 加上蓝图面板、框选复制粘贴、撤销重做和中键拾取。**服务器不需要安装本模组**——所有操作都走 ProjectRed 自带的 IC 同步协议，不修改 ProjectRed 的 jar，也不注册自定义网络通道。

> Client-side quality-of-life add-on for ProjectRed's Integrated Circuit Workbench (Minecraft 1.7.10 / GTNH). Blueprint panel, marquee copy/paste, undo/redo, middle-click pick. The server does **not** need this mod: everything goes through ProjectRed's own IC sync protocol, and the ProjectRed jar is never touched.

## 功能 / Features

- **蓝图面板**：把当前 IC 保存为命名的 `.icbp` 文件（`.minecraft/blueprints/`），随时加载、删除；支持把整块电路压成"分享串"发给别人导入。Blueprint panel (save/load/delete named layouts, share codes).
- **框选与剪贴板**：Ctrl+拖拽框选（Shift 追加），Ctrl+C / X / V 复制、剪切、粘贴；粘贴时自动保护集束线缆所在的格子。Marquee select, copy/cut/paste, bundled-cable cells protected on paste.
- **撤销 / 重做**：Ctrl+Z / Ctrl+Y，与服务器同步安全。Undo/redo.
- **中键拾取**：对着元件按中键直接拿起它。Middle-click pick-part.

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

# ICWbEx — IC Workbench, Extended

纯客户端的 Minecraft 1.7.10（GTNH）模组：给 ProjectRed 的 **IC 工作台** 加上蓝图面板、框选复制粘贴、撤销重做和中键拾取。**服务器不需要安装本模组**——所有操作都走 ProjectRed 自带的 IC 同步协议，不修改 ProjectRed 的 jar，也不注册自定义网络通道。

> Client-side quality-of-life add-on for ProjectRed's Integrated Circuit Workbench (Minecraft 1.7.10 / GTNH). Blueprint panel, marquee copy/paste, undo/redo, middle-click pick. The server does **not** need this mod: everything goes through ProjectRed's own IC sync protocol, and the ProjectRed jar is never touched.

## 功能 / Features

- **蓝图面板**：把当前 IC 保存为命名的 `.icbp` 文件（`.minecraft/blueprints/`），随时加载、删除；支持把整块电路压成"分享串"发给别人导入。Blueprint panel (save/load/delete named layouts, share codes).
- **框选与剪贴板**：Ctrl+拖拽框选（Shift 追加），Ctrl+C / X / V 复制、剪切、粘贴；粘贴时自动保护集束线缆所在的格子。Marquee select, copy/cut/paste, bundled-cable cells protected on paste.
- **撤销 / 重做**：Ctrl+Z / Ctrl+Y，与服务器同步安全。Undo/redo.
- **中键拾取**：对着元件按中键直接拿起它。Middle-click pick-part.
- **安全同步（0.2.8）**：全零信号的集束线缆不再在整板描述包里被服务端解析成 null（旧版会 NPE）；任何同步失败都会重置半写的流缓冲并打印完整堆栈，绝不污染服务器。

## 安装 / Install

1. GTNH 1.7.10 实例（Java 17+ 启动）。
2. 把 `ICWbEx-0.2.8.jar` 放进 `.minecraft/mods/`。仅客户端；服务器不装。
3. 需要 ProjectRed（在 `ProjRed-4.12.44-GTNH` 上验证）+ MrTJPCore。

## 用法 / Usage

在 IC 工作台 GUI 里：`H` 打开操作说明，`Esc` 逐层退出。快捷键总表：

| 操作 | 按键 |
|---|---|
| 放置 / 打开元件设置 / 拾取 | 左键 / 右键 / 中键 |
| 框选（追加） | 按住 Ctrl 拖动（Shift 追加） |
| 复制 / 剪切 / 粘贴 / 删除 / 全选 | Ctrl+C / X / V / Del / Ctrl+A |
| 撤销 / 重做 | Ctrl+Z / Ctrl+Y |
| 蓝图面板 | 右上角「蓝图」按钮 |

## 已知问题

- **0.2.7 期间（2026-09-29 前后）加载过含集束线缆的电路的存档**，其 IC 可能已被保存成"无线缆"状态——这是 0.2.7 的同步回归造成的存档级丢失，0.2.8 修复后需要重新贴一次蓝图恢复。0.2.8 不会再发生。

## 构建 / Building

见 [BUILDING.md](BUILDING.md)。一句话版本：

```bash
python build.py        # 需要 JDK(9+) 与 Python 3，产出 out/ICWbEx-0.2.8.jar
```

## 许可

[MIT](LICENSE)。ProjectRed 与 MrTJPCore 归其各自作者所有；本模组不包含、不修改它们的任何代码。

---
title: 安装
---

# 安装

## 客户端安装

1. 前往 [Releases](https://github.com/Conflux-Union/conflux-map/releases) 页面（或 Modrinth / CurseForge）下载对应 Minecraft 版本的 Conflux Map JAR 文件。
2. 确保已安装对应版本的 [Fabric API](https://modrinth.com/mod/fabric-api)。
3. 将这两个 JAR 文件一同放入游戏目录的 `mods/` 文件夹中即可启动。

!!! tip "可选增强：MaliLib"
    [MaliLib](https://modrinth.com/mod/malilib) 为可选依赖。安装后：

    - 按键绑定将迁移至 MaliLib 的热键设置界面，支持**多键组合**；
    - 在 Minecraft `1.21.1` 及以上版本中，Conflux Map 会自动出现在 MaliLib 的 `A+C` 设置切换界面中。

## 服务端安装（可选）

小地图、世界地图、路径点、绘图与导出功能**仅依赖客户端**即可在单人游戏或任意服务器中运行。如需解锁**全服实时地图校正、共享路径点、区块加载等级与浏览器网页地图**，请按服务端类型安装伴随组件：

- **Fabric 服务端**：将对应版本的 Conflux Map JAR 放入服务端 `mods/` 目录。
- **Paper 服务端**：将独立的 `confluxmap-paper-<version>.jar` 放入服务端 `plugins/` 目录（支持 Paper `1.21.1` 至 `26.3`）。

部署与配置详情参见 [服务端组件 (Fabric)](../technical/server-companion.md) 与 [Paper 组件](../technical/paper-companion.md)。

## 支持的版本

| 构建版本 | 可加载的 Minecraft 版本 | Fabric（客户端/服务端） | Paper 插件 |
|---|---|:---:|:---:|
| `1.17.1` | 1.17.1 | ✓ | — |
| `1.18.2` | 1.18.2 | ✓ | — |
| `1.20.1` | 1.20.1 | ✓ | — |
| `1.20.6` | 1.20.5、1.20.6 | ✓ | — |
| `1.21.1` | 1.21、1.21.1 | ✓ | ✓ |
| `1.21.3` | 1.21.2、1.21.3 | ✓ | ✓ |
| `1.21.4` | 1.21.4 | ✓ | ✓ |
| `1.21.5` | 1.21.5 | ✓ | ✓ |
| `1.21.8` | 1.21.6、1.21.7、1.21.8 | ✓ | ✓ |
| `1.21.9` | 1.21.9、1.21.10 | ✓ | ✓ |
| `1.21.11` | 1.21.11 | ✓ | ✓ |
| `26.1.2` | 26.1、26.1.1、26.1.2 | ✓ | ✓ |
| `26.2` | 26.2 | ✓ | ✓ |
| `26.3` | 26.3 | ✓ | ✓ |

自行从源码构建的方法参见 [参与开发](../technical/developing.md)。

---

相关页面：[快速上手](quickstart.md) · [常见问题与兼容性](../faq.md)

---
title: 共享路径点
---

# 共享路径点

共享路径点需要安装[服务端组件](../technical/server-companion.md)，默认启用。普通玩家可以发布全服可见的公共路径点并管理自己发布的条目；管理员可以管理所有条目。

!!! tip "无客户端模组也能用"
    未安装 Conflux Map 的原版客户端玩家，也可直接通过 `/confluxmap waypoints` 系列命令查看与操作同一份公共路径点列表。而聊天坐标分享功能无需服务端组件，在任何服务器上均可使用。

## 常用命令参考

| 命令 | 说明 |
|---|---|
| `/confluxmap waypoints add <name>` | 在当前位置发布一条公共路径点 |
| `/confluxmap waypoints list [page]` | 分页查看公共路径点（每条附带 Xaero 聊天格式，便于一键导入） |
| `/confluxmap waypoints edit <id> <name>` | 使用列表显示的短 ID 重命名路径点 |
| `/confluxmap waypoints move <id>` | 使用短 ID 移动路径点位置 |
| `/confluxmap waypoints delete <id>` | 使用短 ID 删除路径点 |
| `/confluxmap waypoints group list` | 列出所有公共路径点集 |
| `/confluxmap waypoints group create <name>` | 创建公共路径点集 |
| `/confluxmap waypoints group rename <id> <name>` | 重命名公共路径点集 |
| `/confluxmap waypoints group delete <id>` | 删除公共路径点集 |
| `/confluxmap waypoints group assign <waypoint> <group\|none>` | 将路径点移入指定分组，或移回未分组 |
| `/confluxmap waypoints disable \| enable \| status` | 管理员开关或查看共享路径点功能状态 |

!!! info "客户端可视化操作"
    已安装 Conflux Map 的玩家无需记忆命令，可直接在路径点界面的「**公共**」分页中新建、重命名、删除路径点集，或勾选条目后批量移动。

## 路径点照片

发布本地路径点时，可在确认页从相册选取最多 4 张照片；已保存的公共路径点可通过「上传路径点图片」追加照片。照片在选取前只保存在提交者本机，管理员可在「设置 → 服务端配置 → 审核路径点照片」中预览并决定每条提交。审核通过后，任何人在[网页地图](web-map.md)中点击该路径点即可查看照片。

每个世界与每位玩家可发布的数量上限可在 `config/confluxmap/server.json` 中调整。

---

相关页面：[路径点](waypoints.md) · [网络协议](../technical/protocol.md)

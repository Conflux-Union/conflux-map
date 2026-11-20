---
title: 共享路径点
---

# 共享路径点

共享路径点需要[服务端组件](../technical/server-companion.md),默认启用。
普通玩家可以发布全员可见的路径点,并管理自己发布的条目;管理员可以管理所有条目。

未安装 Conflux Map 的玩家也可通过命令操作同一份列表:

- `/confluxmap waypoints add <name>`:在当前位置发布路径点
- `/confluxmap waypoints list [page]`:分页查看,每条附带 Xaero 聊天格式,
  便于一键导入
- `/confluxmap waypoints edit <id> <name>` / `move <id>` / `delete <id>`:
  使用列表显示的短 ID 修改、移动或删除
- `/confluxmap waypoints group list` / `create <name>` /
  `rename <id> <name>` / `delete <id>`:管理路径点集;安装 Conflux Map 的
  客户端可直接在路径点界面的"公共"页新建、重命名、删除路径点集
- `/confluxmap waypoints group assign <waypoint> <group|none>`:把条目移入
  某个路径点集,或移回未分组;安装 Conflux Map 的客户端可在"公共"页勾选条目后
  批量移动
- 管理员可通过 `/confluxmap waypoints disable`、`enable`、`status`
  控制该功能

每个世界与每位玩家可发布的数量上限可在 `config/confluxmap/server.json` 中调整。

聊天坐标分享无需服务端组件,可在任何服务器上使用。

参见:[路径点](waypoints.md)、[网络协议](../technical/protocol.md)

---
title: 服务端组件(Fabric)
---

# 服务端组件

安装组件后,全服共用同一份实时地图与同一份路径点列表。

- Fabric 服务端:将对应版本的 JAR 放入 `mods/` 目录。
- Paper 服务端:将 `confluxmap-paper-<version>.jar` 放入 `plugins/` 目录,
  支持 Paper 1.21.1 至 26.3——参见
  [Paper 组件](../technical/paper-companion.md)。

客户端与服务端可以分别升级:两侧预测算法一致时采用体积较小的差分同步,
不一致时回退至完整数据同步,旧协议客户端始终可以获得基础地图服务。

运行 [quadra-gen](https://github.com/Fallen-Breath/quadra-gen) 的服务器会被
自动支持:安装组件后,预测地图会按其四个象限的世界样式(自然地形、玻璃超平坦、
群系虚空、虚空)分别计算,而不是整图按自然地形绘制。单人世界中 quadra-gen
接管世界生成时同样生效。

组件还提供一个独立的网页地图,可在浏览器中查看已探索地形、预测地形与共享
路径点。玩家位置、名称、维度与界面语言均可配置;玩家可在游戏内执行
`/confluxmap webmap hide` 将自己从网页地图中隐藏,`show` 恢复显示。

## 配置

所有共享内容由 `config/confluxmap/server.json` 控制:

- `enabled` 为总开关;`checkForUpdates` 在服务端启动时于控制台提示新版本。
- `shareSeed` 将世界种子发送至客户端,供预测群系与结构使用。默认关闭,
  因为共享后种子会暴露给所有玩家;`allowBiomeMap` 与
  `allowStructureSearch` 分别控制群系图层与结构查找。
- `shareCorrections` 将服务端掌握的真实地形发送至客户端,用于修正预测结果。
- `fullSyncEnabled` 允许管理员执行 `/confluxmap fullsync`,把当前维度全部
  已生成区域一次性同步到自己的客户端(`/confluxmap fullsync cancel` 可中止)。
  该操作会暴露整张已生成地图,因此默认关闭,且需要管理员权限与 Fabric 组件。
- `shareChunkLoadState` 公开服务端保持加载的区块,默认关闭,
  以减少玩家活动与农场位置的暴露。
- `allowEntityRadar` 默认开启,向客户端发送所有在线玩家的实时位置;关闭后
  位置流停止,客户端实体雷达不可用。
- `allowMinimap`、`allowWorldMap`、`allowWaypoints`、`allowPrediction`
  可逐项关闭客户端功能:HUD 小地图、全屏世界地图(含地图导出、标注与地图
  标记)、本地路径点、种子预测图层。全部默认开启;功能被关闭时,兼容的客户端
  会隐藏该功能,设置界面也会显示"此服务器已禁用"提示。与所有客户端限制一样,
  这只对配合的客户端生效。
- `restrictionMessage` 设置玩家尝试打开受限地图或切换受限小地图时显示的纯文本
  说明。留空时使用客户端语言对应的默认提示;消息最多保留 256 个 UTF-8 字节。
  第三方服务端插件无需安装 companion,按[网络协议](../technical/protocol.md)
  文档化的协议即可直接下发以上全部限制。
- `shareWaypoints` 启用共享路径点列表,默认开启;
  `allowNonOperatorSharedWaypointManagement` 默认允许普通玩家管理自己发布的条目。
- `webMap.*` 控制网页地图,默认开启:监听 `127.0.0.1:8123`,不显示玩家位置。
  如需公开访问,应置于保留原始 `Host` 请求头的 HTTPS 反向代理之后;
  `sharePlayers` 控制是否包含玩家位置。

每位玩家的流量限制与带宽预算位于同一配置文件。玩家可在游戏内执行
`/confluxmap performance` 查看当前连接的同步统计。Paper 特有的安装与数据存储
细节参见 [Paper 组件](../technical/paper-companion.md)。

## 网页标题与图标

在 `server.json` 的 `webMap` 中设置 `"title": "Conflux Map"` 和
`"favicon": "favicon.png"`。图标只填写文件名,并放在 `server.json` 同一目录
(通常为 `config/confluxmap/`),支持 PNG、ICO,最大 1 MiB;不支持路径或网络
地址。标题为空时使用 `Conflux Map`,图标为空时保持原有无自定义图标行为。
图标读取失败会记录警告,不影响地图启动。修改后重启服务端;图标的浏览器缓存
为五分钟。该配置只修改浏览器标签页标题,地图内的 `Conflux Map` 标识保持不变。

参见:[共享路径点](../features/shared-waypoints.md)、
[多服务器数据管理](../features/multi-server.md)

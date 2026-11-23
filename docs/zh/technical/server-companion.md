---
title: 服务端组件(Fabric)
---

# 服务端组件

安装服务端组件后，整个服务器可以共享实时地形校正、公共路径点列表以及内置的浏览器网页地图。

## 安装方式

- **Fabric 服务端**：将对应 Minecraft 版本的 Conflux Map JAR 放入服务端 `mods/` 目录。
- **Paper 服务端**：将 `confluxmap-paper-<version>.jar` 放入 `plugins/` 目录（支持 Paper `1.21.1` 至 `26.3`，详见 [Paper 组件](../technical/paper-companion.md)）。

!!! info "跨版本兼容与同步机制"
    客户端与服务端可以分别独立升级：两侧预测算法一致时采用体积较小的**差分同步**，不一致时自动回退至**完整数据同步**，旧协议客户端始终可以获得基础地图服务。

## 特殊世界生成器支持

运行 [quadra-gen](https://github.com/Fallen-Breath/quadra-gen) 的服务器会被自动识别：安装组件后，预测地图会按其四个象限的世界样式（自然地形、玻璃超平坦、群系虚空、虚空）分别计算，而不是整图按自然地形绘制。单人世界中由 `quadra-gen` 接管世界生成时同样生效。

## 配置文件：`config/confluxmap/server.json`

### 1. 基础、种子与地形同步

| 配置项 | 默认值 / 说明 |
|---|---|
| `enabled` | 服务端组件总开关 |
| `checkForUpdates` | 服务端启动时是否在控制台检查并提示新版本 |
| `shareSeed` | **默认关闭**。将世界种子发送至客户端用于群系与结构预测（开启后种子会暴露给所有玩家） |
| `allowBiomeMap` | 控制客户端是否允许使用群系图层 |
| `allowStructureSearch` | 控制客户端是否允许使用结构搜索 |
| `shareCorrections` | 将服务端掌握的真实地形发送至客户端，用于修正预测结果 |
| `fullSyncEnabled` | **默认关闭**（需 Fabric 组件）。允许管理员执行 `/confluxmap fullsync` 将当前维度全部已生成区域一次性同步到自己的客户端（`/confluxmap fullsync cancel` 可中止） |
| `shareChunkLoadState` | **默认关闭**。公开服务端保持加载的区块，默认关闭以减少玩家活动与农场位置的暴露 |

### 2. 客户端功能限制与雷达

| 配置项 | 默认值 / 说明 |
|---|---|
| `allowEntityRadar` | **默认开启**。向客户端广播所有在线玩家的实时位置；关闭后位置流停止，客户端实体雷达不可用 |
| `allowMinimap` | **默认开启**。关闭时配合客户端将隐藏 HUD 小地图 |
| `allowWorldMap` | **默认开启**。关闭时配合客户端将禁用全屏世界地图（含导出、标注与标记） |
| `allowWaypoints` | **默认开启**。关闭时配合客户端将禁用本地路径点 |
| `allowPrediction` | **默认开启**。关闭时配合客户端将禁用种子预测图层 |
| `restrictionMessage` | 玩家尝试打开受限功能时显示的纯文本提示；留空则使用客户端语言默认提示（最多保留 256 个 UTF-8 字节） |

!!! note "第三方插件下发限制"
    上述客户端限制仅对配合的客户端生效。第三方服务端插件无需安装 companion，只需按照 [网络协议](../technical/protocol.md) 下发对应限制消息即可。

### 3. 共享路径点与性能统计

- `shareWaypoints`：启用共享路径点列表（默认开启）。
- `allowNonOperatorSharedWaypointManagement`：是否允许普通玩家管理自己发布的条目（默认开启）。
- 每位玩家的流量限制与带宽预算亦在同一配置文件中调整；玩家可在游戏内执行 `/confluxmap performance` 查看当前连接的同步统计。

## 内置网页地图 (`webMap.*`)

组件内置了独立的 HTTP 网页地图，可在浏览器中查看已探索地形、预测地形与共享路径点：

- **默认监听**：`127.0.0.1:8123`，默认开启且不显示玩家位置（由 `sharePlayers` 控制）。
- **公网访问**：如需公开访问，请将其置于保留原始 `Host` 请求头的 HTTPS 反向代理之后。
- **隐私隐藏**：玩家可在游戏内执行 `/confluxmap webmap hide` 将自己从网页地图中隐藏，执行 `/confluxmap webmap show` 恢复显示。

### 自定义网页标题与图标

在 `server.json` 的 `webMap` 节点中设置：

```json
{
  "webMap": {
    "title": "Conflux Map",
    "favicon": "favicon.png"
  }
}
```

- **图标位置与格式**：`favicon` 仅填写文件名，并将图片放在 `server.json` 同目录（通常为 `config/confluxmap/`）；支持 PNG 与 ICO 格式，最大 **1 MiB**（不支持子路径或网络 URL）。
- **回退与生效**：`title` 留空则使用 `Conflux Map`；`favicon` 留空保持默认无自定义图标行为；图标读取失败会记录警告而不影响地图启动。修改后需重启服务端，图标的浏览器缓存为 5 分钟。该配置仅修改浏览器标签页标题，地图内的 `Conflux Map` 标识保持不变。

---

相关页面：[Paper 组件](../technical/paper-companion.md) · [共享路径点](../features/shared-waypoints.md) · [多服务器数据管理](../features/multi-server.md)

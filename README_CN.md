# Conflux Map

[English](README.md) | **简体中文**

[![CurseForge 下载量](https://img.shields.io/curseforge/dt/1663891?logo=curseforge&label=CurseForge&color=f16436)](https://www.curseforge.com/minecraft/mc-mods/conflux-map) [![MC百科浏览量](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fr.jina.ai%2Fhttps%3A%2F%2Fwww.mcmod.cn%2Fclass%2F30075.html&search=%28%5B0-9%5D%2B%29%5Cn%5Cn%E6%80%BB%E6%B5%8F%E8%A7%88&replace=%241&label=MC%E7%99%BE%E7%A7%91%E6%B5%8F%E8%A7%88%E9%87%8F&color=3f85c6&cacheSeconds=86400&logo=data%3Aimage%2Fpng%3Bbase64%2CiVBORw0KGgoAAAANSUhEUgAAAA4AAAAOCAYAAAAfSC3RAAAACXBIWXMAAAABAAAAAQBPJcTWAAAAIGNIUk0AAHomAACAhAAA%2BgAAAIDoAAB1MAAA6mAAADqYAAAXcJy6UTwAAAJNSURBVHichZLbTxNREMb3DzMxJiYiRaAgtMUHY8AYNbYgvJiIChZK1EgkqJH4ojGaKOFWKWpLuy2FCEQuArts2a690BZKK7D0EiPdzzmLr%2Bgmv8wl850zM2e5bn8dR3QSO4T2b0xJu8%2FQ0j%2FbyjFRO4H%2F0eU34WHAgGa3Hc281MSEcUfAVOoJmEsOf71exCzFoDzFzLfg0ZQBt3yOQ6MzXLLykQUS1qv3vOdw23MGdr5GL77PG%2FW4w1tJuVryT%2BOyqwtnR2StekygG%2BUNrtNXrY5Lz7C85cXzuRu4M1mOgfkWrGz5MSz0onf6IuYTnxFI5BFMqFpbQMFVrxzi7k4aVOXnEtg3G3ei7dMJLKY8eryc8mF9J4hkAfgSzWJIzmhm1zqsvjAJvQZVysxhOxdBOhfF4%2BlLyBRSyOZFTG0moewd4MniJk5%2B%2BA6jU9BqP4qw8SSk1tTI7iqC0UFs7kuQs0uI7omYiU1gOnWI0G4BvQsJnBpcQdWYoJWPrNGMSohr95Sp6VwMnvArTIbf6i2%2BXpMwLKexvJPHt%2B0D%2FNgv4uXqFoY2MlrXXBzX2Iy0UXUmNoL3qz14%2BrURQlZFk1tB31IS79bTuO4NYzGdw1o2D2W%2FqL0R07jiIeHRc1Shmy%2BD1d2PilERpnERbJYap4jzZA3UXsWojsZ8G69ssB8g8iBQWbJ5XhzWjcfQMCHBQpvTmTiyFyjH8mR%2F00EaLWeec%2FiNN63uATCRxSWArfs42EF%2Ft9rAdfBNnNkltRKy2SUWqeDXMRRIKJCwkYTcHxyPO1PVoOVTAAAAAElFTkSuQmCC)](https://www.mcmod.cn/class/30075.html)

Conflux Map 是一款 Fabric 平台的小地图与世界地图模组。客户端可独立运行：小地图、全屏世界地图、群系图层、路径点、结构查找、地图绘图与 PNG 导出均集成于同一个 JAR。若服务器安装配套的服务端组件，整个服务器还可以共享同一份实时地图、一套公共路径点，以及一个通过浏览器访问的网页地图。

## 支持的版本

| 构建       | 可加载的版本           | Fabric | Paper 插件 |
|------------|-----------------------|:------:|:----------:|
| `1.17.1`   | 1.17.1                | ✓      | —          |
| `1.18.2`   | 1.18.2                | ✓      | —          |
| `1.20.1`   | 1.20.1                | ✓      | —          |
| `1.21.1`   | 1.21、1.21.1           | ✓      | ✓          |
| `1.21.3`   | 1.21.2、1.21.3         | ✓      | ✓          |
| `1.21.4`   | 1.21.4                | ✓      | ✓          |
| `1.21.5`   | 1.21.5                | ✓      | ✓          |
| `1.21.8`   | 1.21.6、1.21.7、1.21.8 | ✓      | ✓          |
| `1.21.9`   | 1.21.9、1.21.10        | ✓      | ✓          |
| `1.21.11`  | 1.21.11               | ✓      | ✓          |
| `26.1.2`   | 26.1、26.1.1、26.1.2   | ✓      | ✓          |
| `26.2`     | 26.2                  | ✓      | ✓          |

## 安装

从 [Releases](../../releases) 页面下载对应 Minecraft 版本的 JAR，与 [Fabric API](https://modrinth.com/mod/fabric-api) 一同放入 `mods/` 目录。自行构建的方法参见[构建](#构建)。

[MaliLib](https://modrinth.com/mod/malilib) 为可选依赖。安装后按键支持多键组合，Conflux Map 也会出现在 MaliLib 的 A+C 设置切换界面中。

小地图、世界地图、路径点、绘图与导出功能仅依赖客户端。全服地图同步、公共路径点、区块加载等级与网页地图需要服务端组件，参见[服务端组件](#服务端组件)。

## 功能

### 小地图

- 提供方形与圆形外观，位置、大小和旋转均可自由调整。
- 坐标、当前群系和当前图层可按需显示。
- 自动避让原版 HUD 元素，并可显示近期移动足迹。

### 世界地图

- 全屏地图支持连续缩放、平滑拖动与区块网格，缩放以光标为中心。
- 提供地图、群系、加载等级三种图层。加载等级具有「状态分级」与「精确等级」两种显示精度。主世界分为地表、当前洞穴与固定高度三类，下界分为当前层、基岩顶层与基岩下方，末地使用适配虚空环境的背景；当前显示的高度标注在地图上。
- 地图光照跟随原版亮度设置、游戏内昼夜与附近的方块光照。
- 右键地图可创建路径点或分享坐标；服务器允许时亦可直接传送。

### 地图补全

获取世界种子后，主世界、下界基岩顶层与末地会立即显示预测地形。随着探索推进，预测结果逐步被真实地图替换；安装服务端组件后，服务端还会下发真实地形数据主动校正。按 `P` 可在「全部预测 / 仅已生成 / 仅已探索」之间切换，按 `F9` 强制刷新。客户端也可以配置本地种子，生成仅对自己可见的预测地图。

### 结构查找

主世界、下界与末地的原版结构均有独立图标和分类开关；村庄群系、堡垒遗迹布局、僵尸村庄、带船末地城等变种使用单独的名称与图标。可按名称搜索最近的结构，也可指定中心、半径、数量与变种筛选，列出指定区域内的候选位置并直接保存为路径点。右键结构图标，位置菜单会在该结构的精确生成点打开。

### 路径点

- 支持名称、颜色与分组；列表支持搜索、筛选与批量移动。
- 每个维度自动记录死亡点，默认保留 5 个，可在 0 至 50 之间调整。
- 路径点在世界内显示为光柱、名称与距离；视野外的路径点通过屏幕边缘指示器提示方向，显示距离上限可调。
- 主世界与下界的路径点默认按 1:8 的传送门坐标换算跨维度显示，可在设置中关闭；末地路径点仅显示在末地。
- 在聊天中分享坐标前可预览 Conflux Map 与 Xaero 两种格式，其他玩家分享的坐标可一键导入。
- 路径点支持从 Xaero's Minimap 与 VoxelMap 一键导入，重复项自动跳过，原文件保持不变。

### 实体雷达

玩家、敌对生物、友善生物与其他实体各有独立开关，默认全部显示。安装服务端组件后，所有在线玩家在超出原版追踪距离后仍然可见，浏览其他维度时亦可查看对应维度的玩家。在全屏地图上右键一名玩家，可在地图与游戏世界中持续高亮追踪；对方切换维度后，其最后位置会短暂保留为半透明标记。小地图默认显示按生物生成的头像与按物品生成的图标，也可切换为按类别着色的小点，按住玩家列表键时临时展开；全屏地图始终显示详细图标。

### 绘图与导出

- 支持在地图上绘制直线、图形、自由笔迹与文字标签；绘制完成后可选中、移动、改色与删除，并支持撤销与重做。绘图内容随世界保存，亦可显示在小地图上。
- 任意区域均可导出为指定分辨率的 PNG：可输入矩形两角的坐标，或直接在地图上框选范围。导出任务在后台运行，提供大小预估、进度显示与取消操作，完成后可复制到剪贴板或直接打开输出目录。

### 多服务器数据管理

- 同一地址可能对应多个世界，例如代理网络。地图数据按世界分别保存；子世界界面支持新建、重命名与清除识别记录，可将旧的同种子地图缓存合并至当前世界，并迁移旧记录中的路径点。
- 同一服务器拥有多个地址时，可将地址关联，共用地图缓存、路径点与本地种子。安装服务端组件后，服务端会直接告知客户端哪些地址指向同一世界。

### 显示与外观

- 小地图、路径点、实体雷达、图层与信息栏均有独立的显示设置，改动即时生效。
- 工具栏图标与小地图边框可通过普通资源包替换；Xaero 的 UI 资源包中的兼容素材可继续使用，支持的路径与限制参见 [UI 资源包说明](docs/reference-specs/ui-resource-packs.md)。
- 可选的更新检查会在聊天中提示新版本。累计游玩数小时后会出现一次问卷邀请，提示中可关闭后续通知。

## 按键绑定

所有按键均可在原版控制设置的「Conflux Map」分类下修改。安装 MaliLib 后，按键迁移至其热键界面并支持多键组合；1.21.1 及以上版本还会出现在 A+C 设置切换界面中。

| 默认按键 | 功能 |
|---|---|
| `H` | 开关小地图 |
| `]` / `[` | 小地图放大 / 缩小 |
| `M` | 打开全屏地图 |
| `Y` | 循环切换自动、地表/顶层、当前高度和配置的固定高度图层 |
| `U` | 打开路径点列表 |
| `B` | 在当前位置新建路径点 |
| `J` | 显示或隐藏本地路径点 |
| `,` | 打开设置 |
| `P` | 切换地图补全范围（全部 / 仅已生成 / 仅已探索） |
| `F9` | 刷新地图补全瓦片 |

## 共享路径点

共享路径点需要服务端组件，默认启用。普通玩家可以发布全员可见的路径点，并管理自己发布的条目；管理员可以管理所有条目，并可将任意路径点设为标记。标记后的路径点归入「服务器标记」一栏；设置与取消标记、删除任意路径点仅限管理员执行，未标记的路径点由发布者自行删除。

未安装 Conflux Map 的玩家也可通过命令操作同一份列表：

- `/confluxmap waypoints add <name>`：在当前位置发布路径点
- `/confluxmap waypoints list [page]`：分页查看，每条附带 Xaero 聊天格式，便于一键导入
- `/confluxmap waypoints edit <id> <name>` / `move <id>` / `delete <id>`：使用列表显示的短 ID 修改、移动或删除
- Paper 端额外提供 `lock <id>` 与 `unlock <id>`，用于设置与取消标记
- 管理员可通过 `/confluxmap waypoints disable`、`enable`、`status` 控制该功能

每个世界与每位玩家可发布的数量上限可在 `config/confluxmap/server.json` 中调整。

聊天坐标分享无需服务端组件，可在任何服务器上使用。

## 服务端组件

安装组件后，全服共用同一份实时地图与同一份路径点列表。

- Fabric 服务端：将对应版本的 JAR 放入 `mods/` 目录。
- Paper 服务端：将 `confluxmap-paper-<version>.jar` 放入 `plugins/` 目录，支持 Paper 1.21.1 至 26.2。

客户端与服务端可以分别升级：两侧预测算法一致时采用体积较小的差分同步，不一致时回退至完整数据同步，旧协议客户端始终可以获得基础地图服务。

组件还提供一个独立的网页地图，可在浏览器中查看已探索地形、预测地形与共享路径点。玩家位置、名称、维度与界面语言均可配置；玩家可在游戏内执行 `/confluxmap webmap hide` 将自己从网页地图中隐藏，`show` 恢复显示。

所有共享内容由 `config/confluxmap/server.json` 控制：

- `enabled` 为总开关；`checkForUpdates` 在服务端启动时于控制台提示新版本。
- `shareSeed` 将世界种子发送至客户端，供预测群系与结构使用；`allowBiomeMap` 与 `allowStructureSearch` 分别控制群系图层与结构查找。
- `shareCorrections` 将服务端掌握的真实地形发送至客户端，用于修正预测结果。
- `shareChunkLoadState` 公开服务端保持加载的区块，默认关闭，以减少玩家活动与农场位置的暴露。
- `allowEntityRadar` 默认开启，向客户端发送所有在线玩家的实时位置；关闭后位置流停止，客户端实体雷达不可用。
- `shareWaypoints` 启用共享路径点列表，默认开启；`allowNonOperatorSharedWaypointManagement` 默认允许普通玩家管理自己发布的条目。
- `webMap.*` 控制网页地图，默认开启：监听 `127.0.0.1:8123`，不显示玩家位置。如需公开访问，应置于保留原始 `Host` 请求头的 HTTPS 反向代理之后；`sharePlayers` 控制是否包含玩家位置。

每位玩家的流量限制与带宽预算位于同一配置文件。玩家可在游戏内执行 `/confluxmap performance` 查看当前连接的同步统计。Paper 特有的安装与数据存储细节参见 [`docs/paper-companion.md`](docs/paper-companion.md)。

## 构建

需要 JDK 21 或更高版本。Minecraft、映射表、Fabric API，以及 26.x 构建所需的 JDK 25 工具链均由 Gradle 按需下载。

```sh
./gradlew :1.21.11:build
./gradlew :paper:build
./gradlew :paper:runServer
```

`1.21.11` 可替换为上表中的任意版本。构建产物分别输出至 `versions/<版本>/build/libs/` 与 `paper/build/libs/`。`:paper:runServer` 会下载本地 Paper 1.21.1 开发服务器并安装刚构建的插件；首次运行需在 `paper/run/eula.txt` 中接受 EULA，然后重新执行该任务。

## 许可证

GPL-3.0，详见 [`LICENSE`](LICENSE)。第三方组件与参考来源记录于 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。

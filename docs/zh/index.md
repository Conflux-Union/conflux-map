---
title: 首页
---

# Conflux Map

Conflux Map 是一款面向 **Fabric** 平台的小地图与世界地图模组。客户端即可独立运行全套核心功能；在服务器安装配套的服务端组件后，可进一步获得全服实时地形校正、共享路径点与内置网页地图。

## 快速导航

<div class="grid cards" markdown>

-   :material-compass-outline: **新手玩家**

    ---

    仅需单个 JAR 即可在单人或任意服务器使用。从安装到 5 分钟上手核心操作：

    - [安装指南](getting-started/install.md)
    - [快速上手](getting-started/quickstart.md)
    - [按键绑定速查](reference/keybinds.md)

-   :material-server-network: **服务器服主**

    ---

    支持 **Fabric 服务端** 与 **Paper 插件（1.21.1–26.3）**，提供共享地图、公共路径点与权限管控：

    - [服务端组件 (Fabric)](technical/server-companion.md)
    - [Paper 伴随插件](technical/paper-companion.md)
    - [共享路径点命令](features/shared-waypoints.md)

-   :material-tune: **配置与迁移**

    ---

    查找全部设置项说明、常见问题解答，或从 Xaero / VoxelMap 迁移历史数据：

    - [配置参考](config/index.md)
    - [常见问题与兼容性](faq.md)
    - [多服务器数据管理](features/multi-server.md)

-   :material-code-braces: **模组与插件开发者**

    ---

    查看客户端公共 API、插件消息协议、预测质量基准与源码构建方法：

    - [公共 API](technical/api.md)
    - [网络协议](technical/protocol.md)
    - [参与开发](technical/developing.md)

</div>

## 功能目录

### 探索与地图渲染

| 功能模块 | 核心能力 |
|---|---|
| [小地图](features/minimap.md) | 方形/圆形外观、自定义位置与旋转、自动避让原版 HUD |
| [世界地图](features/world-map.md) | 光标中心缩放、地图/群系/区块加载等级图层、分维度高度视图 |
| [地图补全](features/autofill-prediction.md) | 由世界种子预测未探索地形（支持 GPU 加速），探索后实时替换 |
| [结构与群系搜索](features/structure-biome-search.md) | 查找并标记原版结构（含变种）与群系，支持范围筛选 |
| [箱子战利品预览](features/chest-loot-preview.md) | 预览并显示原版结构内的箱子战利品内容 |
| [传送门标记](features/portal-markers.md) | 记录已见过的下界传送门、末地传送门与折跃门，支持区块高亮 |

### 标记、协同与实用工具

| 功能模块 | 核心能力 |
|---|---|
| [路径点](features/waypoints.md) | 分组管理、自动死亡点、1:8 跨维度显示、Xaero/VoxelMap 数据一键迁移 |
| [共享路径点](features/shared-waypoints.md) | 全服公共列表，支持 GUI 与 `/confluxmap waypoints` 命令操作 |
| [实体雷达](features/entity-radar.md) | 玩家与生物雷达、精细过滤与单实体类型覆盖 |
| [绘图与导出](features/drawings-export.md) | 地图手绘标注、多段测距、框选后台导出 PNG、Xaero 地图数据一键迁移 |
| [显示与外观](features/appearance.md) | 界面动画、原版与 Xaero UI 资源包主题兼容 |
| [多服务器数据管理](features/multi-server.md) | Velocity 多子世界独立存档、同种子合并、多地址关联 |

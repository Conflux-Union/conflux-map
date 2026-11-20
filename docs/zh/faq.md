---
title: 常见问题与兼容性
---

# 常见问题与兼容性

**模组需要服务端安装什么吗?**
不需要。小地图、世界地图、路径点、绘图与导出全部在客户端完成。安装
[服务端组件](technical/server-companion.md)后会额外获得实时网页地图、
共享路径点、真实地形校正与区块加载等级图。

**支持哪些 Minecraft 版本?**
每个版本线一个构建,覆盖 1.17.1 至 26.3——参见
[支持的版本表](getting-started/install.md#支持的版本)。Paper 服务端自
1.21.1 起通过独立插件支持。

**必须安装 MaliLib 吗?**
可选。安装后按键支持多键组合,Conflux Map 会出现在 MaliLib 的 A+C
设置切换界面;不安装则使用原版控制设置。

**能从 Xaero 或 VoxelMap 迁移数据吗?**
可以。路径点支持从 Xaero's Minimap 与 VoxelMap 一键导入,重复项自动跳过、
原文件不变。Xaero 世界地图记录的地形可在地图屏幕的操作工具中导入,
按维度合并到尚无本模组捕获的位置——参见[绘图与导出](features/drawings-export.md)。

**服务端可以禁用部分功能吗?**
可以——通过服务端组件,或任何按[网络协议](technical/protocol.md)实现的
第三方插件,服务端可对配合的客户端关闭小地图、世界地图、路径点、预测或
实体雷达。未安装组件的服务端不会施加任何限制。

**可以更换地图 UI 皮肤吗?**
工具栏图标与小地图边框遵循普通 Minecraft 资源包,Xaero 的 UI 资源包中的
兼容素材可直接使用。支持的路径与限制参见
[UI 资源包说明](https://github.com/Conflux-Union/conflux-map/blob/master/docs/reference-specs/ui-resource-packs.md)。

**为什么有些结构有战利品预览,有些没有?**
预览只能枚举世界生成器确定性放置的箱子——支持列表见
[箱子战利品预览](features/chest-loot-preview.md)。被数据包修改的战利品表
不做预测。

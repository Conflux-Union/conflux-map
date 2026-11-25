---
title: 配置参考
---

# 配置参考

以下选项均可在游戏内设置界面调整(`Mod Menu → Conflux Map → 设置`,或配置快捷键)。本页由模组自身的翻译文件生成;暂无说明的条目仅显示界面标签。


## fullmap

| Option | Key | Description |
| --- | --- | --- |
| 区块网格 | `confluxmap.config.fullmap.chunk_grid` | — |
| 区块网格不透明度 | `confluxmap.config.fullmap.chunk_grid_opacity` | — |
| 从已保存的区块加载地图 | `confluxmap.config.fullmap.mca_backfill` | — |

## interface

| Option | Key | Description |
| --- | --- | --- |
| 动画速度 | `confluxmap.config.interface.animation_speed` | — |
| 界面过渡动画 | `confluxmap.config.interface.animations` | — |
| 控件风格 | `confluxmap.config.interface.control_style` | — |

## layers

| Option | Key | Description |
| --- | --- | --- |
| 洞穴固定高度 | `confluxmap.config.layers.cave_slice_y` | — |
| 下界固定高度 | `confluxmap.config.layers.nether_slice_y` | — |
| 地图图层 | `confluxmap.config.layers.override` | Values: 自动, 地表, 主世界洞穴 / 下界顶层, 配置的固定高度. |

## map

| Option | Key | Description |
| --- | --- | --- |
| 显示下界顶层基岩厚度 | `confluxmap.config.map.bedrock_thickness_shading` | — |
| 地图颜色风格 | `confluxmap.config.map.color_style` | — |
| 动态光照 | `confluxmap.config.map.dynamic_lighting` | — |
| 覆盖范围显示方式 | `confluxmap.config.map.loaded_range_style` | — |
| 真实地图范围高亮 | `confluxmap.config.map.loaded_range_visible` | — |
| 最大缩放倍率 | `confluxmap.config.map.max_zoom` | — |
| 最小缩放倍率 | `confluxmap.config.map.min_zoom` | — |
| 图片拖动与旋转 | `confluxmap.config.map.photo_gestures` | — |
| 地图图片 | `confluxmap.config.map.photos` | — |
| 地图画质等级 | `confluxmap.config.map.quality` | — |
| 地图对应位置显示截图 | `confluxmap.config.map.screenshots` | — |
| 预测及空白区域亮度 | `confluxmap.config.map.unloaded_brightness` | 100% 为原亮度，0% 为全暗。真实地形保持原亮度。 |
| 虚空颜色 | `confluxmap.config.map.void_color` | — |
| 对应路径点显示图片 | `confluxmap.config.map.waypoint_photos` | — |

## minimap

| Option | Key | Description |
| --- | --- | --- |
| 显示小地图标注 | `confluxmap.config.minimap.annotations` | — |
| 背景不透明度 | `confluxmap.config.minimap.background_opacity` | — |
| 小地图 | `confluxmap.config.minimap.enabled` | — |
| 自动避让 HUD | `confluxmap.config.minimap.hud_avoidance` | — |
| 调整小地图位置…… | `confluxmap.config.minimap.position` | — |
| 跟随视角旋转 | `confluxmap.config.minimap.rotate` | — |
| 形状 | `confluxmap.config.minimap.shape` | Values: 圆形, 方形. |
| 显示生物群系 | `confluxmap.config.minimap.show_biome` | — |
| 显示坐标 | `confluxmap.config.minimap.show_coordinates` | — |
| 显示图层标签 | `confluxmap.config.minimap.show_layer_label` | — |
| 大小 | `confluxmap.config.minimap.size` | — |
| 缩放 | `confluxmap.config.minimap.zoom` | — |

## performance

| Option | Key | Description |
| --- | --- | --- |
| 已加载地图范围 | `confluxmap.config.performance.gpu_tile_cache_limit` | — |
| 动态重采间隔：%s tick | `confluxmap.config.performance.recapture_holdoff` | — |
| 地图更新速度 | `confluxmap.config.performance.snapshot_budget` | — |
| 启动时检查更新 | `confluxmap.config.performance.update_check` | — |
| 工作线程数量 | `confluxmap.config.performance.worker_thread_count` | — |
| 工作线程 | `confluxmap.config.performance.worker_threads` | 在下次启动游戏时生效。 |

## player_marker

| Option | Key | Description |
| --- | --- | --- |
| 玩家箭头 | `confluxmap.config.player_marker.style` | — |

## player_trail

| Option | Key | Description |
| --- | --- | --- |
| 足迹红点大小 | `confluxmap.config.player_trail.dot_size` | — |
| 足迹保留时间 | `confluxmap.config.player_trail.duration` | — |
| 显示足迹 | `confluxmap.config.player_trail.enabled` | — |

## portals

| Option | Key | Description |
| --- | --- | --- |
| 传送门区块高亮 | `confluxmap.config.portals.chunk_highlight` | — |
| 传送门标记 | `confluxmap.config.portals.enabled` | — |
| 传送门高亮颜色 | `confluxmap.config.portals.highlight_color` | — |
| 缩放到 %s 以下时隐藏传送门图标 | `confluxmap.config.portals.icon_detail_limit` | — |
| 传送门图标不透明度 | `confluxmap.config.portals.icon_opacity` | — |
| 传送门图标大小 | `confluxmap.config.portals.icon_size` | — |
| 末地传送门图标 | `confluxmap.config.portals.icon_texture.end` | 自定义图标纹理的资源路径，例如 minecraft:textures/block/crying_obsidian.png（命名空间可省略）。留空使用内置图标；纹理不存在时回退为内置图标。 |
| 末地折跃门图标 | `confluxmap.config.portals.icon_texture.gateway` | 自定义图标纹理的资源路径，例如 minecraft:textures/block/crying_obsidian.png（命名空间可省略）。留空使用内置图标；纹理不存在时回退为内置图标。 |
| 下界传送门图标 | `confluxmap.config.portals.icon_texture.nether` | 自定义图标纹理的资源路径，例如 minecraft:textures/block/crying_obsidian.png（命名空间可省略）。留空使用内置图标；纹理不存在时回退为内置图标。 |
| 传送门图标 | `confluxmap.config.portals.show_icons` | — |

## prediction

| Option | Key | Description |
| --- | --- | --- |
| 预测后端 | `confluxmap.config.prediction.backend` | Values: AUTO（默认，实测一次后选用更快的一方）, CPU, GPU（无显卡时自动回退）. |
| 地图更新延迟：%s 毫秒 | `confluxmap.config.prediction.debounce` | — |
| 地图补全 | `confluxmap.config.prediction.enabled` | — |
| 配置本地种子 | `confluxmap.config.prediction.manual_seed` | — |
| 使用服务器地图改善补全 | `confluxmap.config.prediction.network_sync` | — |
| 显示可能存在的结构 | `confluxmap.config.prediction.show_structures` | — |
| 缩放到 %s 以下时隐藏结构图标 | `confluxmap.config.prediction.structure_icon_detail_limit` | — |
| 补全显示范围 | `confluxmap.config.prediction.view_mode` | Values: 全部区域, 仅已生成区域, 关闭（仅已探索区域）. |

## radar

| Option | Key | Description |
| --- | --- | --- |
| 小地图常显玩家名称 | `confluxmap.config.radar.always_show_player_names` | — |
| 默认显示 | `confluxmap.config.radar.display_mode` | — |
| 雷达 | `confluxmap.config.radar.enabled` | — |
| 管理实体显示…… | `confluxmap.config.radar.entity_visibility` | — |
| 描边粗细 | `confluxmap.config.radar.icon_outline_thickness` | — |
| 雷达图标大小 | `confluxmap.config.radar.icon_size` | — |
| 雷达目标数量上限 | `confluxmap.config.radar.max_entities` | — |
| 跨维度高亮记忆时长 | `confluxmap.config.radar.player_highlight_ghost_duration` | — |
| 雷达图标描边 | `confluxmap.config.radar.player_icon_outline` | — |
| 显示敌对生物 | `confluxmap.config.radar.show_hostile` | — |
| 显示掉落物及其他实体 | `confluxmap.config.radar.show_other` | — |
| 显示友善生物 | `confluxmap.config.radar.show_passive` | — |
| 显示玩家名称 | `confluxmap.config.radar.show_player_names` | — |
| 显示玩家 | `confluxmap.config.radar.show_players` | — |

## waypoints

| Option | Key | Description |
| --- | --- | --- |
| 路径点光柱 | `confluxmap.config.waypoints.beams_enabled` | — |
| 保留的死亡点数量 | `confluxmap.config.waypoints.death_points_kept` | — |
| 屏幕外路径点指示器 | `confluxmap.config.waypoints.edge_indicators` | — |
| 跨维度显示高亮位置 | `confluxmap.config.waypoints.highlight_cross_dimension` | — |
| 高亮时其他路径点透明度 | `confluxmap.config.waypoints.highlight_dim_opacity` | — |
| 世界内路径点标签不透明度 | `confluxmap.config.waypoints.label_opacity` | — |
| 标签大小 | `confluxmap.config.waypoints.label_scale` | — |
| 路径点标签 | `confluxmap.config.waypoints.labels_enabled` | — |
| 路径点列表显示维度 | `confluxmap.config.waypoints.list_default_dimension` | — |
| 路径点列表初始标签页 | `confluxmap.config.waypoints.list_default_tab` | — |
| 路径点显示距离 | `confluxmap.config.waypoints.render_distance` | — |
| 显示同种子世界路径点 | `confluxmap.config.waypoints.show_cross_world` | — |
| 显示本地路径点 | `confluxmap.config.waypoints.show_local` | — |
| 显示共享路径点 | `confluxmap.config.waypoints.show_shared` | — |
| 传送后关闭地图 | `confluxmap.config.waypoints.teleport_close_map` | — |
| 自定义传送指令 | `confluxmap.config.waypoints.teleport_command` | — |

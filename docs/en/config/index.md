---
title: Configuration
---

# Configuration

Every option below is available in the in-game settings screen (`Mod Menu → Conflux Map → Settings`, or the config hotkey). This page is generated from the mod's own translations; entries without a description yet only show their on-screen label.


## fullmap

| Option | Key | Description |
| --- | --- | --- |
| Chunk Grid | `confluxmap.config.fullmap.chunk_grid` | — |
| Chunk Grid Opacity | `confluxmap.config.fullmap.chunk_grid_opacity` | — |
| Load Map From Saved Chunks | `confluxmap.config.fullmap.mca_backfill` | — |

## interface

| Option | Key | Description |
| --- | --- | --- |
| Animation Speed | `confluxmap.config.interface.animation_speed` | — |
| Interface Animations | `confluxmap.config.interface.animations` | — |
| Control Style | `confluxmap.config.interface.control_style` | — |

## layers

| Option | Key | Description |
| --- | --- | --- |
| Fixed Cave Height | `confluxmap.config.layers.cave_slice_y` | — |
| Fixed Nether Height | `confluxmap.config.layers.nether_slice_y` | — |
| Map Layer | `confluxmap.config.layers.override` | Values: Auto, Surface, Overworld Cave / Nether Roof, Configured Fixed Height. |

## map

| Option | Key | Description |
| --- | --- | --- |
| Show Nether Ceiling Bedrock Thickness | `confluxmap.config.map.bedrock_thickness_shading` | — |
| Map Color Style | `confluxmap.config.map.color_style` | — |
| Dynamic Lighting | `confluxmap.config.map.dynamic_lighting` | — |
| Map Quality | `confluxmap.config.map.quality` | — |
| Void Color | `confluxmap.config.map.void_color` | — |

## minimap

| Option | Key | Description |
| --- | --- | --- |
| Show Minimap Annotations | `confluxmap.config.minimap.annotations` | — |
| Background Opacity | `confluxmap.config.minimap.background_opacity` | — |
| Minimap | `confluxmap.config.minimap.enabled` | — |
| Avoid HUD Overlap | `confluxmap.config.minimap.hud_avoidance` | — |
| Adjust Minimap Position... | `confluxmap.config.minimap.position` | — |
| Rotate With Player | `confluxmap.config.minimap.rotate` | — |
| Shape | `confluxmap.config.minimap.shape` | Values: Circle, Square. |
| Show Biome | `confluxmap.config.minimap.show_biome` | — |
| Show Coordinates | `confluxmap.config.minimap.show_coordinates` | — |
| Show Layer Label | `confluxmap.config.minimap.show_layer_label` | — |
| Size | `confluxmap.config.minimap.size` | — |
| Zoom | `confluxmap.config.minimap.zoom` | — |

## performance

| Option | Key | Description |
| --- | --- | --- |
| Loaded Map Area | `confluxmap.config.performance.gpu_tile_cache_limit` | — |
| Live Recapture Interval: %s ticks | `confluxmap.config.performance.recapture_holdoff` | — |
| Map Update Speed | `confluxmap.config.performance.snapshot_budget` | — |
| Check for Updates on Launch | `confluxmap.config.performance.update_check` | — |
| Worker Thread Count | `confluxmap.config.performance.worker_thread_count` | — |
| Worker Threads | `confluxmap.config.performance.worker_threads` | Applies the next time the game starts. |

## player_marker

| Option | Key | Description |
| --- | --- | --- |
| Player Marker | `confluxmap.config.player_marker.style` | — |

## player_trail

| Option | Key | Description |
| --- | --- | --- |
| Trail Dot Size | `confluxmap.config.player_trail.dot_size` | — |
| Trail Duration | `confluxmap.config.player_trail.duration` | — |
| Player Trail | `confluxmap.config.player_trail.enabled` | — |

## portals

| Option | Key | Description |
| --- | --- | --- |
| Portal Chunk Highlight | `confluxmap.config.portals.chunk_highlight` | — |
| Portal Markers | `confluxmap.config.portals.enabled` | — |
| Portal Highlight Color | `confluxmap.config.portals.highlight_color` | — |
| Hide Portal Icons Below Zoom | `confluxmap.config.portals.icon_detail_limit` | — |
| Portal Icon Opacity | `confluxmap.config.portals.icon_opacity` | — |
| Portal Icon Size | `confluxmap.config.portals.icon_size` | — |
| End Portal Icon | `confluxmap.config.portals.icon_texture.end` | Custom icon texture as a resource location, e.g. minecraft:textures/block/crying_obsidian.png (namespace optional). Leave empty for the built-in icon; a texture no loaded pack provides falls back to the built-in one. |
| End Gateway Icon | `confluxmap.config.portals.icon_texture.gateway` | Custom icon texture as a resource location, e.g. minecraft:textures/block/crying_obsidian.png (namespace optional). Leave empty for the built-in icon; a texture no loaded pack provides falls back to the built-in one. |
| Nether Portal Icon | `confluxmap.config.portals.icon_texture.nether` | Custom icon texture as a resource location, e.g. minecraft:textures/block/crying_obsidian.png (namespace optional). Leave empty for the built-in icon; a texture no loaded pack provides falls back to the built-in one. |
| Portal Icons | `confluxmap.config.portals.show_icons` | — |

## prediction

| Option | Key | Description |
| --- | --- | --- |
| Prediction Backend | `confluxmap.config.prediction.backend` | Values: AUTO (default; measures once and serves the faster side), CPU, GPU (falls back to CPU). |
| Map Update Delay: %s ms | `confluxmap.config.prediction.debounce` | — |
| Map Autofill | `confluxmap.config.prediction.enabled` | — |
| Configure Local Seed | `confluxmap.config.prediction.manual_seed` | — |
| Improve Autofill with Server Map | `confluxmap.config.prediction.network_sync` | — |
| Show Possible Structures | `confluxmap.config.prediction.show_structures` | — |
| Hide Structure Icons Below Zoom | `confluxmap.config.prediction.structure_icon_detail_limit` | — |
| Autofill Area | `confluxmap.config.prediction.view_mode` | Values: Everywhere, Generated Only, Off (Explored Only). |

## radar

| Option | Key | Description |
| --- | --- | --- |
| Always Show Names on Minimap | `confluxmap.config.radar.always_show_player_names` | — |
| Default Display | `confluxmap.config.radar.display_mode` | — |
| Radar | `confluxmap.config.radar.enabled` | — |
| Manage Entity Visibility... | `confluxmap.config.radar.entity_visibility` | — |
| Outline Thickness | `confluxmap.config.radar.icon_outline_thickness` | — |
| Radar Icon Size | `confluxmap.config.radar.icon_size` | — |
| Radar Target Limit | `confluxmap.config.radar.max_entities` | — |
| Cross-dimension Highlight Memory | `confluxmap.config.radar.player_highlight_ghost_duration` | — |
| Radar Icon Outline | `confluxmap.config.radar.player_icon_outline` | — |
| Show Hostile Mobs | `confluxmap.config.radar.show_hostile` | — |
| Show Items and Other Entities | `confluxmap.config.radar.show_other` | — |
| Show Friendly Mobs | `confluxmap.config.radar.show_passive` | — |
| Show Player Names | `confluxmap.config.radar.show_player_names` | — |
| Show Players | `confluxmap.config.radar.show_players` | — |

## waypoints

| Option | Key | Description |
| --- | --- | --- |
| Waypoint Beams | `confluxmap.config.waypoints.beams_enabled` | — |
| Saved Death Waypoints | `confluxmap.config.waypoints.death_points_kept` | — |
| Off-screen Waypoint Indicators | `confluxmap.config.waypoints.edge_indicators` | — |
| Show Highlight Across Dimensions | `confluxmap.config.waypoints.highlight_cross_dimension` | — |
| Other Waypoint Opacity During Highlight | `confluxmap.config.waypoints.highlight_dim_opacity` | — |
| In-World Waypoint Label Opacity | `confluxmap.config.waypoints.label_opacity` | — |
| Label Size | `confluxmap.config.waypoints.label_scale` | — |
| Waypoint Labels | `confluxmap.config.waypoints.labels_enabled` | — |
| Waypoint List Dimensions | `confluxmap.config.waypoints.list_default_dimension` | — |
| Waypoint List Opening Tab | `confluxmap.config.waypoints.list_default_tab` | — |
| Waypoint Visibility Distance | `confluxmap.config.waypoints.render_distance` | — |
| Show Same-Seed World Waypoints | `confluxmap.config.waypoints.show_cross_world` | — |
| Show Local Waypoints | `confluxmap.config.waypoints.show_local` | — |
| Show Shared Waypoints | `confluxmap.config.waypoints.show_shared` | — |
| Close Map After Teleport | `confluxmap.config.waypoints.teleport_close_map` | — |
| Customize Teleport Command | `confluxmap.config.waypoints.teleport_command` | — |

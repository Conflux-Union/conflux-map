---
title: Home
---

# Conflux Map

Conflux Map is a **Fabric** minimap and world map mod. The client runs on its own with the full feature set; installing the optional server companion adds server-wide real-time terrain corrections, shared waypoints, and a built-in web map.

## Quick Navigation

<div class="grid cards" markdown>

-   :material-compass-outline: **Players**

    ---

    Works on singleplayer or any server with a single JAR. Get started in minutes:

    - [Installation](getting-started/install.md)
    - [Quick start](getting-started/quickstart.md)
    - [Keybind reference](reference/keybinds.md)

-   :material-server-network: **Server Admins**

    ---

    Supports **Fabric servers** and **Paper plugins (1.21.1–26.3)** with shared maps, public waypoints, and permissions:

    - [Server companion (Fabric)](technical/server-companion.md)
    - [Paper companion](technical/paper-companion.md)
    - [Shared waypoint commands](features/shared-waypoints.md)
    - [Web map](features/web-map.md)

-   :material-tune: **Configuration & Migration**

    ---

    Browse all settings, read the FAQ, or migrate existing data from Xaero / VoxelMap:

    - [Configuration reference](config/index.md)
    - [FAQ & compatibility](faq.md)
    - [Multi-server data](features/multi-server.md)

-   :material-code-braces: **Developers**

    ---

    Explore the client-side API, server sync protocol, prediction benchmarks, and build setup:

    - [Public API](technical/api.md)
    - [Network protocol](technical/protocol.md)
    - [Developing](technical/developing.md)

</div>

## Features

### Exploration & Mapping

| Feature | Highlights |
|---|---|
| [Minimap](features/minimap.md) | Square or round frame, custom placement and rotation, automatic vanilla HUD avoidance |
| [World map](features/world-map.md) | Cursor-centered zoom, coordinate jump, real-coverage highlight, map/biome/load-level layers |
| [Autofill & prediction](features/autofill-prediction.md) | Seed-based terrain prediction (GPU-accelerated where available), replaced live as you explore |
| [Structure & biome search](features/structure-biome-search.md) | Find and pin vanilla structures (with variants) and biomes across dimensions |
| [Chest loot preview](features/chest-loot-preview.md) | Preview and display chest loot contents for supported vanilla structures |
| [Portal markers](features/portal-markers.md) | Track activated nether portals, end portals, and gateways you have seen |

### Waypoints, Collaboration & Tools

| Feature | Highlights |
|---|---|
| [Waypoints](features/waypoints.md) | Favorites, notes & tags, photos, death points, 1:8 cross-dimension display, one-click Xaero/VoxelMap migration |
| [Shared waypoints](features/shared-waypoints.md) | Server-wide public list with photos, GUI and `/confluxmap waypoints` command controls |
| [Web map](features/web-map.md) | Built-in browser map with multi-server switching, player & waypoint search, and web chat |
| [Entity radar](features/entity-radar.md) | Players and mobs, fine-grained filtering, per-entity-type overrides |
| [Drawings & export](features/drawings-export.md) | Map annotations, measure tool, background PNG export, one-click Xaero map data migration |
| [Appearance](features/appearance.md) | UI animations, vanilla & Xaero resource-pack theming |
| [Multi-server data](features/multi-server.md) | Velocity sub-world isolation, same-seed merging, linked addresses |

# Conflux Map

**English** | [简体中文](README_CN.md)

[![CurseForge downloads](https://img.shields.io/curseforge/dt/1663891?logo=curseforge&label=CurseForge&color=f16436)](https://www.curseforge.com/minecraft/mc-mods/conflux-map) [![MC百科浏览量](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fwww.mcmod.cn%2Fclass%2F30075.html&search=%3Cp%20class%3D%22n%22%3E%28%5B0-9%5D%2B%29%3C%2Fp%3E%3Cp%20class%3D%22t%22%3E%E6%80%BB%E6%B5%8F%E8%A7%88%3C%2Fp%3E&replace=%241&label=MC%E7%99%BE%E7%A7%91%E6%B5%8F%E8%A7%88%E9%87%8F&color=3f85c6&cacheSeconds=86400&logo=data%3Aimage%2Fpng%3Bbase64%2CiVBORw0KGgoAAAANSUhEUgAAAA4AAAAOCAYAAAAfSC3RAAAACXBIWXMAAAAAAAAAAQCEeRdzAAACfklEQVR4nHVS7UtTURy%2BHyoCP5TQfxN9DMt0vrSXu0VfAvsQCBmGVL5MdDrLyiB6IYgWFJSUM%2B%2Fm3K7bdTqzmQudd%2FfmnLqXtmmb07vmbsR8OndEH8IOPDz8OM9zzvM7v0NVVB6m2t7WdNz20ZsmTv2zb0ojHwRlb8BnyNwYrjJVVB6iFFPbXb8BvR41iOC%2FME3RMHPVMH7sRKPFZqIGZnWJPk5bMk%2FTpf4pbVmksNlLo9%2BrI7WWsB6D0zVo527t0wxfop2xbcrEaSSjW4V2tgo9XENZrHA7ewZGdy16OTU62NO44riJ6pEgzjMr0Nt4mepxN%2B5axSEEUhN46m9B52Q1Hs81YyFlByM8wZ1pGjNRK7h4BlziB0zzUajH%2BCJldNVLQtYHZS0kHWh1nMTcBlOuV1OL8Kes%2BC4DzPo2RtcyaHKtQGMTZarb1SAtZlxI70SRlsMY8jYhKUWwuScgEF9DIFfAy%2BUkTg0vof5DCDqbCL2dGI1OcmNuBrMRBpH8Z4S3lpCQluBed%2BBLKodP2V94EVSMQWLkoRrlQSvGrgmVFM0T4epr2MPPyhHfr4iwkFvC2QLYb3nEJRmPFlN4JW7hfiAJmgmRqGz9rjf2Bu%2F4QTzwXYKYS6PZHUP%2FfBwjkSwus2H403kI23sI78gYj%2B5AN7ZcJOPQ5rs9dejjzuIqe49ECUJL%2BtAwAhrGBKgJn7PyqCURawjqyn0KMvkAmpiZq91vmXxY0trXoR8nc7ILygP8hWH8K0GZ92lbqGRwRjLKl7t23fecnLxGmhb%2BQDwQOiaEi54Y6izuLupo5TFKZfG0XnBGNshpRSIoEOz9gwKJVySaBDF1HDl%2BgvoNorIyyNSoGRkAAAAASUVORK5CYII%3D)](https://www.mcmod.cn/class/30075.html)

Conflux Map is a Fabric minimap and world map mod. The client runs on its own: the minimap, fullscreen world map, biome layer, waypoints, structure finder, map drawing, and PNG export are all contained in a single JAR. Installing the server companion adds a server-wide live map, a shared waypoint list, and a web map accessible from a browser.

## Supported versions

| Build      | Loads on                | Fabric | Paper plugin |
|------------|-------------------------|:------:|:------------:|
| `1.17.1`   | 1.17.1                  | ✓      | —            |
| `1.18.2`   | 1.18.2                  | ✓      | —            |
| `1.20.1`   | 1.20.1                  | ✓      | —            |
| `1.21.1`   | 1.21, 1.21.1            | ✓      | ✓            |
| `1.21.3`   | 1.21.2, 1.21.3          | ✓      | ✓            |
| `1.21.4`   | 1.21.4                  | ✓      | ✓            |
| `1.21.5`   | 1.21.5                  | ✓      | ✓            |
| `1.21.8`   | 1.21.6, 1.21.7, 1.21.8  | ✓      | ✓            |
| `1.21.9`   | 1.21.9, 1.21.10         | ✓      | ✓            |
| `1.21.11`  | 1.21.11                 | ✓      | ✓            |
| `26.1.2`   | 26.1, 26.1.1, 26.1.2    | ✓      | ✓            |
| `26.2`     | 26.2                   | ✓      | ✓            |

## Installation

Download the JAR for your Minecraft version from the [Releases](../../releases) page and place it in the `mods/` folder together with [Fabric API](https://modrinth.com/mod/fabric-api). To build the mod yourself, see [Building](#building).

[MaliLib](https://modrinth.com/mod/malilib) is optional. When installed, keybindings support multi-key combinations, and Conflux Map appears in MaliLib's A+C settings switcher.

The minimap, world map, waypoints, drawing, and export require only the client. Server-wide map synchronization, shared waypoints, the chunk load-level map, and the web map require the server companion; see [Server companion](#server-companion).

## Features

### Minimap

- Square or round frame, with free placement, scaling, and rotation.
- Coordinates, the current biome, and the active layer can be shown on demand.
- Automatically avoids vanilla HUD elements and can display a trail of recent movement.

### World map

- Continuous zoom, smooth panning, and a chunk grid, with zooming centered on the cursor.
- Three layers: map, biome, and load level. Load level offers two display precisions, status bands and exact levels. The Overworld provides surface, current-cave, and fixed-height layers; the Nether provides current-level, bedrock-roof, and below-bedrock layers; the End uses a void-adapted background. The height currently displayed is labeled on the map.
- Map lighting follows the vanilla brightness setting, the in-game time of day, and nearby block light.
- Right-click the map to create a waypoint or share coordinates; a teleport is offered where the server permits it.

### Map autofill

Once the world seed is available, the Overworld, Nether roof, and End immediately display predicted terrain. Exploration progressively replaces the prediction with the real map, and a server with the companion installed also sends real-terrain corrections. Press `P` to cycle everywhere / generated-only / explored-only, and `F9` to force a refresh. A local seed can also be configured to produce a prediction map visible only to the local player.

### Structure finder

Vanilla structures in the Overworld, Nether, and End each have a distinct icon and a category toggle; supported variants such as village biomes, bastion layouts, zombie villages, and End Cities with ships use separate names and icons. The nearest structure can be found by name, or a center, radius, result count, and variant filter can be specified to list candidates within an area and save any of them as a waypoint. Right-clicking a structure icon opens the location menu at its exact generation point.

### Waypoints

- Names, colors, and sets; the list supports search, filtering, and batch moving.
- Each dimension records death points automatically: five by default, adjustable from 0 to 50.
- Waypoints appear in the world as beams, names, and distances; waypoints outside the view are indicated at the screen edge, with a configurable distance limit.
- Overworld and Nether waypoints are shown in both dimensions using the 1:8 portal coordinate ratio by default, which can be disabled in settings; End waypoints are shown only in the End.
- Coordinates shared in chat can be previewed in both Conflux Map and Xaero formats before sending, and coordinates shared by other players can be imported with one click.
- Waypoints can be imported from Xaero's Minimap and VoxelMap with one click; duplicates are skipped and the original files remain unchanged.

### Entity radar

Players, hostile mobs, friendly mobs, and other entities each have an independent toggle, and all are shown by default. On a server with the companion installed, every online player remains visible beyond the vanilla tracking distance and while browsing other dimensions. Right-clicking a player on the fullscreen map highlights them on the map and in the world; after the player changes dimensions, the last position remains briefly as a translucent marker. The minimap shows generated entity portraits and item icons by default, or compact category-colored dots that expand while the player-list key is held; the fullscreen map always uses detailed icons.

### Drawing and export

- Lines, shapes, freehand paths, and text labels can be drawn on the map. Finished drawings can be selected, moved, recolored, and deleted, with undo and redo. Drawings are kept with the world and can also be displayed on the minimap.
- Any area can be exported as a PNG at a chosen resolution by entering two corner coordinates or selecting a rectangle on the map. The export runs in the background with a size estimate, progress display, and cancellation, and the result can be copied to the clipboard or opened in its output directory.

### Multi-server data management

- A single address can lead to several worlds, for example behind a proxy network. Map data is stored per world; the sub-world screen can create, rename, and clear identification records, merge an older same-seed map cache into the current world, and move waypoints from an older record.
- When one server is reachable through several addresses, those addresses can be linked to share the map cache, waypoints, and local seed. A server with the companion installed also informs the client directly which addresses point to the same world.

### Display and appearance

- The minimap, waypoints, entity radar, layers, and info lines each provide independent display settings, and every change takes effect immediately.
- Toolbar icons and minimap frames can be replaced through normal Minecraft resource packs; compatible assets from existing Xaero UI packs continue to work. Supported paths and limits are documented in [UI resource packs](docs/reference-specs/ui-resource-packs.md).
- An optional update check announces new versions in chat. After several hours of play, a one-time survey invitation may appear, and the prompt lets you disable further notices.

## Keybinds

All bindings can be changed in Minecraft's controls screen under "Conflux Map". With MaliLib installed, bindings move to its hotkey screen and support multi-key combinations; on 1.21.1 and newer, Conflux Map also appears in MaliLib's A+C settings switcher.

| Default key | Action |
|---|---|
| `H` | Toggle the minimap |
| `]` / `[` | Minimap zoom in / out |
| `M` | Open the fullscreen map |
| `Y` | Cycle automatic, surface/roof, current-height, and configured fixed-height layers |
| `U` | Open the waypoint list |
| `B` | New waypoint at your position |
| `J` | Toggle local waypoints |
| `,` | Open settings |
| `P` | Cycle map autofill (everywhere / generated-only / explored-only) |
| `F9` | Refresh map autofill tiles |

## Shared waypoints

Shared waypoints require the server companion and are enabled by default. Ordinary players can publish waypoints visible to everyone and manage the entries they published; operators can manage every entry and can mark any waypoint. Marked waypoints move into the "server marks" section; marking, unmarking, and deleting any waypoint are reserved to operators, while unmarked waypoints are deleted by their publisher.

Players without Conflux Map can work with the same list through commands:

- `/confluxmap waypoints add <name>` publishes a waypoint at the player's current position
- `/confluxmap waypoints list [page]` lists entries with pagination, each including an Xaero chat format for one-click import
- `/confluxmap waypoints edit <id> <name>` / `move <id>` / `delete <id>` rename, move, or delete an entry using the short ID shown in the list
- Paper additionally provides `lock <id>` and `unlock <id>` for marking and unmarking
- Operators can control the feature with `/confluxmap waypoints disable`, `enable`, and `status`

Per-world and per-player limits are configured in `config/confluxmap/server.json`.

Chat coordinate sharing requires no server companion and works on any server.

## Server companion

With the companion installed, the whole server shares one live map and one waypoint list.

- Fabric server: place the matching JAR into `mods/`.
- Paper server: place `confluxmap-paper-<version>.jar` into `plugins/` (Paper 1.21.1 through 26.2).

Client and server versions can be upgraded independently: matching prediction algorithms use compact differential updates, differing algorithms fall back to full-data updates, and older-protocol clients always retain the basic map service.

The companion also serves a standalone web map that shows explored terrain, predicted terrain, and shared waypoints in a browser. Player positions, names, dimensions, and the interface language are all configurable; players can run `/confluxmap webmap hide` in game to hide themselves from the web map, and `show` to return.

All companion-shared content is controlled in `config/confluxmap/server.json`:

- `enabled` is the master switch; `checkForUpdates` announces a newer version in the server console at startup.
- `shareSeed` sends the world seed to clients so they can predict biomes and structures; `allowBiomeMap` and `allowStructureSearch` control the biome layer and the structure finder separately.
- `shareCorrections` sends real-terrain data from the server to correct predicted maps.
- `shareChunkLoadState` exposes the chunks the server keeps loaded. It is disabled by default to reduce exposure of player activity and farm locations.
- `allowEntityRadar` defaults to `true` and shares every online player's live position with compatible clients; disabling it stops the position stream and turns the client radar off.
- `shareWaypoints` enables the shared waypoint list and defaults to `true`; `allowNonOperatorSharedWaypointManagement` defaults to letting ordinary players manage the entries they published.
- `webMap.*` controls the web map and defaults to enabled: it listens on `127.0.0.1:8123` and hides player positions. For public access, place it behind an HTTPS reverse proxy that preserves the original `Host` header; `sharePlayers` controls whether player positions are included.

Per-player rate limits and bandwidth budgets are stored in the same configuration file. Players can run `/confluxmap performance` in game to see the sync statistics for their own connection. Paper-specific installation and storage details are documented in [`docs/paper-companion.md`](docs/paper-companion.md).

## Building

Requires JDK 21 or newer. Gradle downloads Minecraft, the mappings, Fabric API, and the JDK 25 toolchain the 26.x builds target, on demand.

```sh
./gradlew :1.21.11:build
./gradlew :paper:build
./gradlew :paper:runServer
```

Swap `1.21.11` for any version from the table above. Jars are written to `versions/<minecraft-version>/build/libs/` and the standalone Paper plugin to `paper/build/libs/`. `:paper:runServer` downloads a local Paper 1.21.1 development server and installs the freshly built plugin; accept the Minecraft EULA in `paper/run/eula.txt` and run the task again.

## License

GPL-3.0, see [`LICENSE`](LICENSE). Third-party components and behavior references are listed in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

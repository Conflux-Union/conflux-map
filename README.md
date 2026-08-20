# Conflux Map

**English** | [简体中文](README-CN.md)

Conflux Map is a Fabric minimap and world map mod for multiplayer games. It combines a minimap, world map, biome map, chunk-ticket map, structure finder, and waypoint synchronization in one client JAR, with each full-server map synchronization requiring only a few hundred bytes to a few hundred KB.

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

Download the JAR for your Minecraft version from [Releases](../../releases), or build it yourself as described in [Building](#building). Put it in the `mods/` directory together with [Fabric API](https://modrinth.com/mod/fabric-api). [MaliLib](https://modrinth.com/mod/malilib) is optional; after installation, it provides key combinations and places Conflux Map in its A+C settings switcher.

The client provides local maps, waypoints, drawings, and map export as soon as it is installed. Installing the server companion adds server-wide map synchronization, public waypoints, terrain corrections, the chunk-ticket map, and the web map. See [Server companion](#server-companion) for details.

## Features

### Core capabilities

- **Full-map synchronization:** Predicted maps, authoritative maps, tiled storage, and incremental updates provide low-bandwidth server-wide map synchronization, with each sync kept to a few hundred bytes to a few hundred KB.
- **Public waypoints:** The server can maintain a shared waypoint catalog for farms, builds, and other important locations, visible to every Conflux Map client.
- **Web map:** The server companion can provide a standalone browser map showing explored terrain, predicted terrain, and shared waypoints. Operators can also configure player positions, names, dimensions, and interface language.

### Map autofill and world exploration

- **Map autofill:** Once the world seed is available, the Overworld, Nether roof, and End can immediately display predicted terrain. Exploration and server corrections progressively replace the prediction with the real map. Press `P` to cycle through all predictions, generated areas only, and explored areas only; a local seed can also be configured for a private prediction map.
- **Map layers:** The fullscreen map provides Map, Biome, and Load Level modes. Load Level mode offers Status Bands and Exact Levels. The Overworld distinguishes surface and cave layers, the Nether provides current-level and bedrock-roof layers, and the End uses a void-adapted background. The surface layer also applies shading based on time of day and nearby block light.
- **Structure finder:** Vanilla structures in the Overworld, Nether, and End have individual icons, category toggles, and a master toggle. Search by name for the nearest structure, or specify a center, radius, and result count to list candidates in an area and save any candidate directly as a waypoint.
- **Sub-world management:** When several servers sit behind the same address, including proxy networks, map data is stored separately for each world. The sub-world screen creates, renames, and clears identification records, merges an older same-seed map cache into the current world, and moves waypoints from an older record.
- **Server address association:** Multiple addresses for one server can share the same map cache, waypoint set, and local seed. The server address controls allow those relationships to be managed directly, and the companion can identify addresses that point to the same world.

### Minimap and world map

- **Minimap HUD:** Choose a square or round frame, adjust its scale, rotate it with the player view, and place it freely. Coordinates, the current biome, and the active layer can be shown as needed, and the HUD automatically adjusts its position around vanilla HUD elements.
- **Fullscreen world map:** The fullscreen map supports continuous zoom, a chunk grid, smooth panning, and cursor-centered zooming. Right-clicking creates a waypoint, shares coordinates, or performs a teleport when available.

### Waypoints and map markers

- **Local waypoints and death points:** Waypoints support names, colors, and sets. The list supports search, set filtering, and batch selection and movement. Each dimension keeps death points, with five entries by default and a configurable range of 0 to 50.
- **In-world markers:** Waypoints can appear as beams, names, and distances. Edge indicators point toward waypoints outside the view, display distance limits are configurable, and Overworld and Nether waypoints can be shown across dimensions using the 1:8 portal coordinate ratio.
- **Chat sharing and cross-mod import:** Chat coordinates support previews and one-click import in both Conflux Map and Xaero formats. Waypoints can be imported from Xaero's Minimap and VoxelMap with one click; duplicates are skipped and the original files remain unchanged.

### Map drawing and export

- **Map drawing tools:** Draw lines, shapes, freehand paths, and text labels. Completed drawings can be selected, moved, recolored, deleted, erased, undone, and redone; they can be kept with the world and shown on the minimap.
- **Recent movement trail:** The minimap and fullscreen map can both display a recent movement trail, with configurable duration, dot size, and visibility.
- **Custom-range PNG export:** Export any map area as a PNG at a chosen resolution by entering two corner coordinates or selecting a rectangle on the map. Drawings can be included, and the exporter provides a size estimate, background processing, progress reporting, cancellation, clipboard copying, and an option to open the output directory.

### Entity radar and interface settings

- **Entity radar:** Players, mobs, dropped items, vehicles, and projectiles have independent controls. Entity icons are generated from the corresponding mob model or item appearance, crowded targets are grouped automatically, and player markers remain separate.
- **Display customization:** The minimap, waypoints, entity radar, map layers, and information lines all provide detailed display settings. Client settings apply immediately, and sliders accept directly entered values.
- **MaliLib key combinations:** With MaliLib installed, Conflux Map supports multi-key combinations and can be managed through the A+C configuration screen.
- **Update notices:** An optional startup check announces new versions in chat. After several hours of play, the mod can also send a feedback-survey invitation; the prompt provides controls for managing future notices.

## Keybinds

All bindings can be changed in Minecraft's Controls screen under "Conflux Map". With MaliLib installed, bindings move to its hotkey screen and support multi-key combinations; on 1.21.1 and newer, Conflux Map also appears in MaliLib's A+C settings switcher. On 1.17.1, 1.18.2, and 1.20.1, the mod connects automatically when the installed MaliLib provides the corresponding registry interface; otherwise, one vanilla shortcut remains for opening the hotkey screen.

| Default key | Action |
|---|---|
| `H` | Toggle the minimap |
| `]` / `[` | Minimap zoom in / out |
| `M` | Open the fullscreen map |
| `Y` | Cycle the manual layer override |
| `U` | Open the waypoint list |
| `B` | New waypoint at your position |
| `J` | Toggle local waypoints |
| `,` | Open settings |
| `P` | Cycle map autofill (everywhere / generated-only / explored-only) |
| `F9` | Refresh map autofill tiles |

## Waypoint usage

Waypoints are shown in the dimension where they were created by default. An optional setting shows Overworld and Nether waypoints in both dimensions; End waypoints remain in the End.

Shared waypoints require the server companion and are disabled by default. A level-2 operator can use `/confluxmap waypoints enable` to enable them, with `disable` and `status` providing the corresponding operations. By default, only operators may upload, edit, move, or delete shared waypoints. Setting `allowNonOperatorSharedWaypointManagement` to `true` lets ordinary players upload waypoints and manage only the entries they published; operators can always manage every entry. Players without Conflux Map can use `/confluxmap waypoints list [page]`, `add`, `edit`, `move`, and `delete` under the same server-authoritative rules. List entries include Xaero's chat-share format for one-click import. Per-world and per-player limits are configurable in `config/confluxmap/server.json`.

Chat coordinate sharing is available on any server without the companion. Before sending, the client previews the outgoing Conflux Map and Xaero messages; coordinates shared by other players can be imported with one click.

## Server companion

Installing the companion lets the entire server share one live map and one waypoint list. Install the matching Fabric mod JAR in a Fabric server's `mods/` directory, or install `confluxmap-paper-<version>.jar` in a Paper server's `plugins/` directory (Paper 1.21.1 through 26.2). Client and server versions can be upgraded independently: matching prediction algorithms use compact differential updates, different algorithms use complete data updates, and older protocols continue to provide the basic map service.

All companion-shared content is controlled in `config/confluxmap/server.json`:

- `enabled` is the master switch; `checkForUpdates` announces a newer version in the server console at startup.
- `shareSeed` sends the world seed to clients so they can predict biomes and structures; `allowBiomeMap` and `allowStructureSearch` control the biome map and structure finder independently.
- `shareChunkLoadState` exposes the chunks currently kept loaded by the server. It is disabled by default to reduce exposure of player activity and farm locations.
- `allowEntityRadar` controls entity-position display in the client Conflux Map minimap.
- `shareCorrections` sends real-terrain data to clients for correcting predicted maps.
- `shareWaypoints` enables the shared waypoint list.
- `allowNonOperatorSharedWaypointManagement` lets ordinary players upload, edit, move, and delete only their own shared waypoints; it defaults to `false`.
- `webMap.*` controls the web map described in [Features](#features). The default port is `8123`, the default bind address is `127.0.0.1`, and `sharePlayers` controls whether player positions are included. Public access should use an HTTPS reverse proxy that preserves the original `Host` header.

These settings allow server operators to manage privacy, bandwidth, and anti-cheat policies. Entity data continues to be sent to clients through normal game behavior; `allowEntityRadar` only disables entity-position display in the client Conflux Map minimap.

Per-player rate limits and bandwidth budgets are stored in the same configuration file. Players can run `/confluxmap performance` in game to view statistics for their current connection. Paper-specific installation, terrain access, and storage details are available in [`docs/paper-companion.md`](docs/paper-companion.md).

## Building

Requires JDK 21 or newer (Gradle downloads Minecraft, mappings, Fabric API, and the JDK 25
toolchain the 26.x builds target, on demand).

```sh
./gradlew :1.21.11:build
./gradlew :paper:build
./gradlew :paper:runServer
```

Swap `1.21.11` for any version from the table above; jars land in
`versions/<minecraft-version>/build/libs/` and the standalone Paper plugin in
`paper/build/libs/`. `:paper:runServer` downloads a local Paper 1.21.1 development server and
installs the freshly built plugin; accept the Minecraft EULA in `paper/run/eula.txt` and run the
task again.

## License

GPL-3.0, see [`LICENSE`](LICENSE). Third-party components and behavior references are listed in
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

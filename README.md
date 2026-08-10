# Conflux Map

**English** | [简体中文](README-CN.md)

Conflux Map is a Fabric client-side minimap and world map

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
| `26.2`     | 26.2                    | ✓      | ✓            |

## Highlights

- **Full-map sync and public waypoints.** Eliminate blank map tiles. New players
  can see the live server-wide map as soon as they join, and find coordinates
  for farms and builds through public waypoints instead of adding each one manually.
- **Extremely low bandwidth usage.** A predicted map plus an authoritative map,
  tiled storage, incremental updates, and more compact data structures keep each
  map sync between a few hundred bytes and a few hundred kilobytes.
- **One client JAR for the complete map experience.** There is no need to install
  separate mods for a minimap, world map, biome preview, chunk-load-state map,
  map sync, and waypoint sync. One Conflux Map client JAR covers most of the
  features players normally assemble from multiple map mods.

## Features

- **Minimap HUD:** Choose a square or round frame, a size from 64 to 256 px,
  four zoom levels, free placement, and rotation with the player's view. Info lines
  can show coordinates, biome, and active layer. HUD avoidance keeps vanilla status
  effects and the scoreboard clear of the minimap, while inventory screens and
  JEI/REI overlays are also taken into account.
- **Fullscreen map:** Multi-resolution tiles provide continuous zoom, panning, a
  chunk grid, and cursor-anchored scaling. Right-click anywhere to create a waypoint,
  share coordinates, or teleport when permission is available.
- **Three base layers:** Switch between normal terrain, stable biome colors, and
  server chunk-load state. Chunk loading can be shown as readable bands or exact
  ticket levels.
- **Dimension layers:** The Overworld automatically switches between surface and
  cave views, the Nether provides current-level and bedrock-roof views, and the End
  uses a background designed for the void. Surface maps also respond to time of day
  and local lighting.
- **Map autofill:** When the world seed is available, unexplored Overworld, Nether roof, and End
  areas immediately show predicted biomes and terrain. The Nether roof uses a fixed-height biome
  proxy, while the Overworld also approximates trees. Captured terrain and server corrections
  progressively replace the prediction, and Superflat worlds are supported as well.
- **Structure filters and search:** Every vanilla structure set available in the
  running game version is covered across the Overworld, Nether, and End. Each type
  has its own icon and visibility toggle, predicted and server-confirmed positions
  use different frames, and search jumps directly to the nearest candidate. Scrollbars
  make long filter and result lists explicit, and the zoom level below which structure
  icons are hidden is configurable.
- **Map autofill coverage:** Press `P` to switch between everywhere,
  server-generated areas, and off. Turning autofill off provides a purely
  authoritative map limited to explored areas.
- **Per-server sub-worlds:** Terrain fingerprints keep worlds behind the same
  server address, including proxy networks, in separate map records. Sub-worlds can
  be created, renamed, and unbound from the fullscreen map. If a companion is later
  installed on an existing server, old waypoints and same-seed map data remain
  available for an explicit, confirmed migration instead of moving automatically.
- **Public waypoints and chat sharing:** A server can maintain a public waypoint list
  for every connected Conflux Map client. Chat coordinates are available on every
  server. Before sending, you can preview both Conflux Map and Xaero formats;
  received coordinates can be imported with one click.
- **Local waypoints and death points:** Waypoints support names, colors, sets, and
  visibility controls, while each dimension keeps the five most recent death points.
  The Overworld and Nether can optionally show linked waypoints with the 8:1 coordinate
  conversion applied.
- **In-world waypoint markers:** Waypoints can appear as beams, names, and distances.
  Edge indicators point toward targets outside the minimap, and map markers show
  whether a waypoint is above or below the player. Display ranges are configurable.
- **Waypoint set management:** Sets can be created, renamed, and deleted. Multiple
  waypoints, or every result in the current filter, can be moved to another set in
  one operation.
- **Import from other mods:** Migrate Xaero's Minimap and VoxelMap waypoints in one
  click. Duplicate coordinates are skipped automatically, and the original waypoint
  files remain unchanged.
- **Map drawing:** Draw lines, circles, rectangles, freehand paths, and text labels,
  with an adjustable eraser. Shapes can be moved, recolored, deleted, undone, and
  redone. Each drawing can last until disconnect or persist by world and dimension,
  and drawings can also appear on the minimap.
- **Recent player trail:** The last 1 to 120 seconds of movement can be drawn as dots
  on both maps. Trail duration, dot size, and visibility are configurable.
- **Entity radar:** Players, hostile mobs, passive and neutral mobs, dropped items,
  vehicles, and projectiles have separate controls. Markers use matching entity heads
  or item forms. Every living entity gets a borderless portrait rendered from its own
  current model, so newer vanilla mobs and modded mobs need no bundled art, and every
  portrait is normalized to the same icon size. While a portrait is still baking or
  cannot be produced, the category-shaped dot stays visible instead of dropping the
  entity. Crowded targets collapse into counted clusters, and players always remain separate.
- **PNG export:** Select any rectangular map area or enter its coordinates, then
  export at 1 to 16 blocks per pixel. The exporter estimates the output size, can
  include map drawings, runs in the background, reports progress, and supports
  cancellation.
- **Optional server components:** Fabric servers use the matching mod JAR, while
  Paper versions 1.21.1 through 26.2 use the standalone plugin. Operators can centrally
  control the world seed, real-terrain corrections, public waypoints, chunk-load
  state, and entity-radar permission.
- **Update checks:** The optional startup check provides a download link in chat and
  a map-screen badge when a new version is available.
- **In-game settings:** Client settings apply immediately, and every slider also
  accepts a directly typed value. With MaliLib installed, controls can use key
  combinations and Conflux Map appears in the A+C configuration screen.

## Keybinds

Without MaliLib, all bindings remain configurable under Minecraft's Controls
screen in the "Conflux Map" category. When MaliLib is installed, the gameplay
bindings move to MaliLib's hotkey interface (including multi-key
combinations), and Conflux Map is registered in MaliLib's A+C config switcher
on Minecraft 1.21.1 and newer. On Minecraft 1.17.1, 1.18.2, and 1.20.1,
Conflux Map detects the same registry when the installed MaliLib provides it;
otherwise it keeps one optional vanilla shortcut for opening its hotkey screen.

| Default key | Action |
|---|---|
| `H` | Toggle the minimap |
| `]` / `[` | Minimap zoom in / out |
| `M` | Open the fullscreen world map |
| `Y` | Cycle the manual layer override |
| `U` | Open the waypoint list |
| `B` | New waypoint at your position |
| `J` | Toggle local waypoints |
| `,` | Open the settings screen |
| `P` | Cycle map autofill area (everywhere / generated-only / off) |
| `F9` | Refresh map autofill tiles |

## Waypoint management

Waypoints are rendered in the dimension where they were created. An optional
setting (off by default) additionally shows Overworld and Nether waypoints
from the linked dimension with the 8:1 portal coordinate conversion applied
on display; End waypoints always stay confined to the End. The waypoint list
supports local set creation, renaming, cascading deletion, and multi-select
or current-filter select-all batch moves between sets.

Waypoints can also show in the world itself, as a light beam with the name
and distance floating above it. Each part can be turned off on its own, and
you can set how far away they still show. An on-screen waypoint list overlay
is not implemented yet.

## Shared waypoints

Shared waypoints require a compatible Conflux Map jar on the server and are disabled by
default. A level-2 operator can use `/confluxmap waypoints enable`, `disable`,
or `status`; the setting is persisted in `config/confluxmap/server.json`.
When the feature is disabled or unavailable, shared waypoint buttons, tabs,
sharing choices, and settings are not shown on the client.
Shared points are stored in the world directory, are visible to all connected
mod clients, and cannot be edited after publication. An unlocked point can be
deleted only by its publisher; operators can lock, unlock, or delete any
point. The defaults are 512 points per world, 64 per publisher, and 30
mutations per player per minute. Operators can tune
`maxSharedWaypointsPerWorld`, `maxSharedWaypointsPerPlayer`, and
`sharedWaypointMutationsPerMinute` in the same server config.

Chat sharing remains available on every server. Each send previews the exact
outgoing messages (Conflux Map and Xaero formats) before confirmation.
Recognized Conflux Map or labelled `X/Y/Z` messages expose a click-to-import
action that opens the local waypoint editor before anything is saved.

## Server companion policy

Install the matching Fabric mod jar in a Fabric server's `mods/` directory, or install
`confluxmap-paper-<version>.jar` in a Paper server's `plugins/` directory. The Paper artifact
targets the Paper API only; Folia, Spigot, and CraftBukkit are not supported. The client remains
the normal version-specific Fabric jar. See [Paper companion](docs/paper-companion.md) for the
runtime and storage details.

Client and server versions do not need to be identical. v0.1.0 is the supported
compatibility floor: matching prediction profiles keep the smallest residual patches,
different current profiles fall back to complete absolute patches, and an unknown older
protocol disables only map correction sync. Shared waypoints independently negotiate their
highest common minor version. A disabled sync setting remains visible in the client with the
server or compatibility reason, and the saved client preference is not changed.

Everything the companion shares is controlled in `config/confluxmap/server.json`:

- `shareSeed` (default `false`) includes the world seed in the client
  handshake. With it on, `allowBiomeMap` and `allowStructureSearch` (both
  default `true`) independently gate the fullscreen biome layer and the
  structure layer / nearest-candidate search on cooperating clients after a
  server restart.
- `shareChunkLoadState` (default `false`) exposes which chunks the server
  currently keeps loaded. It defaults off because loaded chunks reveal player
  activity and long-running farms.
- `allowEntityRadar` (default `true`) controls whether cooperating clients
  may scan and render their entity radar.
- `shareCorrections` (default `true`) serves real-terrain map corrections.
- `shareWaypoints` (default `false`) enables the shared waypoint catalog.
- `webMap.enabled` (default `false`) starts the bundled 2D browser map. It binds to
  `127.0.0.1:8123` by default and should be published through an HTTPS reverse proxy;
  direct non-loopback binding is rejected unless `webMap.allowInsecureRemote=true`.
- `webMap.sharePlayers` (default `false`) adds the optional two-second player radar.
  Players can persistently opt out with `/confluxmap webmap hide` and opt back in with
  `/confluxmap webmap show`. Spectator and invisible players are shown translucent when sharing
  is enabled.

These settings are client policy, not anti-cheat. Once a server shares its
seed, a modified client or an external tool can derive the same biome and
structure data independently, and a modified client can always run its own
radar.

Per-player rate limits and bandwidth budgets for map sync
(`maxTilesPerRequest`, `maxPendingTilesPerPlayer`,
`maxBytesPerSecondPerPlayer`, `minRequestIntervalMs`,
`maxChunkSummariesPerSecond`) live in the same file. Any player can run
`/confluxmap performance` in game to see timing and volume stats for their
own connection's map sync.

The browser uses one binary WebSocket for region pages, invalidations, and optional player radar.
It requests the existing compressed region-summary protocol in batches of at most eight regions,
keeps revision identifiers in IndexedDB, revalidates them once per browser session, and then
refreshes only regions invalidated by the server. Static Leaflet assets and the cubiomes WASM
predictor are bundled in the jar, so normal page loads do not use a CDN.

When `shareSeed=true` and `allowBiomeMap=true`, the manifest explicitly publishes the seed and
enables the browser's “all regions” biome prediction for supported dimensions; generated server
regions are drawn over that local prediction. An anonymous browser that can predict from the seed can also read that seed,
so this setting is a disclosure policy, not a way to keep the seed secret. With seed sharing off,
only “generated regions” is available. The web zoom range, default scale, and 1.26 zoom step match
the in-game fullscreen map (`0.25` to `16` blocks per screen pixel).

## Building

Requires JDK 21 or newer. Everything else — Minecraft, mappings, Fabric API,
and the JDK 25 toolchain the 26.x builds target — is downloaded by Gradle on
demand.

```sh
./gradlew :1.21.11:build
./gradlew :paper:build
./gradlew :paper:runServer
```

Replace `1.21.11` with any supported build above. The jar is written to
`versions/<minecraft-version>/build/libs/`. The standalone Paper plugin is written to
`paper/build/libs/`. `:paper:runServer` downloads and starts a local Paper 1.21.1 development
server with the freshly built plugin installed. On the first run, review the Minecraft EULA,
set `eula=true` in `paper/run/eula.txt`, and run the task again.

## License

GPL-3.0 — see [`LICENSE`](LICENSE). Third-party components and behavior
references are listed in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

# Conflux Map

**English** | [简体中文](README-CN.md)

Conflux Map is a Fabric client-side minimap and world map

| Build      | Loads on               |
|------------|------------------------|
| `1.17.1`   | 1.17.1                 |
| `1.21.1`   | 1.21, 1.21.1           |
| `1.21.3`   | 1.21.2, 1.21.3         |
| `1.21.4`   | 1.21.4                 |
| `1.21.5`   | 1.21.5                 |
| `1.21.8`   | 1.21.6, 1.21.7, 1.21.8 |
| `1.21.9`   | 1.21.9, 1.21.10        |
| `1.21.11`  | 1.21.11                |
| `26.1.2`   | 26.1, 26.1.1, 26.1.2   |
| `26.2`     | 26.2                   |

## Highlights

- **The map remembers where you've been.** Explored terrain is cached on disk
  per world, server, dimension, and layer, so coming back never means starting
  from a blank map.
- **Unexplored doesn't mean empty.** A seed-based preview sketches biomes,
  terrain height, and trees for areas you haven't visited, and marks possible
  structure locations you can search by name. Real terrain draws over the
  guess as you explore.
- **One jar, client first.** Everything works in singleplayer with zero setup.
  Drop the same jar on a server and it can additionally serve real-terrain
  corrections, shared waypoints, and chunk-load-level overlays, each gated
  behind an explicit operator opt-in.

## Features

- **Minimap HUD** — square or round frame, any size from 64 to 256 px, four
  zoom levels, optional rotation with your facing. Placement is free-form:
  open the placement screen from the settings and drag the map wherever you
  want it (configs saved with the old four-corner option migrate on first
  launch). The info lines show coordinates, the current biome, and the active
  map layer, and the surface layer can darken at night and in unlit areas.
- **Fullscreen world map** — pan and zoom with cursor-anchored zooming over
  multi-resolution tiles, with an optional chunk grid. Right-clicking a spot
  opens a small menu: set a waypoint there, share the coordinates in chat, or
  teleport there if you have the permission.
- **Three fullscreen base layers** — normal terrain, biome colors, and server
  chunk load levels. The biome layer paints each biome in one stable color.
  The load-level layer shows which chunks the server currently keeps loaded,
  either as readable bands or as exact ticket levels, and only appears when
  the server companion explicitly allows it.
- **Cave / Nether / End layers** — automatic underground detection in the
  Overworld; Nether current-layer / ceiling / manual-Y-slice modes; End
  void-background rendering.
- **Waypoints and death points** — create, edit, color, organize into sets, and
  toggle; death points are automatic (the last five per dimension are kept);
  strict per-dimension rendering; edge-of-minimap direction indicators for
  out-of-range waypoints.
- **In-world waypoint markers** — an optional light beam at each waypoint and a
  floating name and distance above it. Each can be turned off on its own, and
  you can set how far away they still show.
- **Waypoint set management** — create, rename, and delete local sets; select
  multiple points (or every point in the current filter) and move them to
  another set in one operation. Deleting a set permanently deletes every
  waypoint it contains.
- **Import from other mods** — bring your existing Xaero's Minimap and VoxelMap
  waypoints for the current world over in one click. Locations you already
  have are skipped, and your original files are left untouched.
- **Map drawings** — a private annotation layer on the fullscreen map: lines,
  circles, rectangles, a freehand brush, and an eraser, in any color, with an
  optional text label per shape. Shapes can be moved, recolored, and deleted,
  with undo/redo, and each shape is either kept until disconnect or persisted
  per world and dimension. Drawings can also show on the minimap HUD if you
  want them there.
- **Shared and chat-shared coordinates** — an optional server-owned shared
  waypoint catalog; on servers without the companion, coordinates shared in
  chat can still be imported with a click.
- **Entity radar** — players, hostile mobs, passive mobs, and everything else
  (dropped items, vehicles, projectiles) as separate toggles, plus optional
  player names and a shared entity cap. Icons use real entity heads and item
  forms at an adjustable size; crowded markers collapse into counted clusters,
  with players always kept separate. A server can disallow the radar for
  cooperating clients, in which case the settings page says so instead of
  pretending the toggles work.
- **Seed preview** — panning into unexplored Overworld or End terrain shows an
  instant seed-based guess at the biomes, terrain height, and trees, which the
  real map draws over as you explore it. Superflat worlds are recognized and
  previewed too. Backed by a bundled
  [cubiomes](https://github.com/Cubitect/cubiomes) native build.
- **Structure layer and search** — every vanilla structure set available in
  the running game version is covered across the Overworld, Nether, and End.
  Each type gets a recognizable icon drawn from the game's own item and block
  textures, a per-type visibility filter (remembered per game version and
  dimension), and distinct frames for seed-derived candidates versus
  server-confirmed locations. A search box jumps to the nearest candidate.
  Variants that share one placement set, such as village styles and warm/cold
  ocean ruins, are grouped together.
- **Seed preview area modes** — cycle with `P`: *everywhere* (default),
  *generated-only* (preview masked to chunks the server generated), or
  *visited-only* (pure captured map, no preview).
- **Optional server companion** — the same jar can run a server-side companion
  that returns compact per-column corrections against the real world; the
  seed, shared waypoint catalog, chunk load levels, and radar permission are
  shared only when the operator opts in.
- **Update check** — an optional check on startup that tells you in chat when
  a newer version is out, with a download link, plus a badge on the map
  screen.
- **Settings screen** — everything above is exposed in-game and takes effect
  immediately, no restart. Full English and Simplified Chinese localization.

## Keybinds

Without MaliLib, all bindings remain configurable under Minecraft's Controls
screen in the "Conflux Map" category. When MaliLib is installed, the gameplay
bindings move to MaliLib's hotkey interface (including multi-key
combinations), and Conflux Map is registered in MaliLib's A+C config switcher
on Minecraft 1.21.1 and newer. Minecraft 1.17.1 keeps one optional vanilla
shortcut because its MaliLib version predates the config-screen registry API.

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
| `P` | Cycle seed preview area (everywhere / generated-only / visited-only) |
| `F9` | Refresh seed preview tiles |

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

Shared waypoints require the same jar on the server and are disabled by
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

## Building

Requires JDK 21 or newer. Everything else — Minecraft, mappings, Fabric API,
and the JDK 25 toolchain the 26.x builds target — is downloaded by Gradle on
demand.

```sh
./gradlew :1.21.11:build
```

Replace `1.21.11` with any supported build above. The jar is written to
`versions/<minecraft-version>/build/libs/`.

## License

GPL-3.0 — see [`LICENSE`](LICENSE). Third-party components and behavior
references are listed in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

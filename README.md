# Conflux Map

**English** | [简体中文](README-CN.md)

Conflux Map is a Fabric client-side minimap and world map for Minecraft 1.17.1.
It runs standalone against any server — vanilla or modded, no server-side
component required. Behavior is inspired by established minimap mods, but every
line of code here is written from scratch; see
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) for attribution.

## Features

- **HUD minimap** — always-on corner overlay, square or circular, 4 corners,
  4 sizes, 4 zoom levels, optional player-facing rotation, coordinates and
  biome line.
- **Fullscreen world map** — pan/zoom map with multi-resolution tiles,
  cursor-anchored zoom, right-click to drop a waypoint.
- **Cave / Nether / End layers** — automatic underground detection in the
  Overworld with hysteresis so it doesn't flicker at the boundary; Nether
  current-layer / ceiling / manual-Y-slice modes; End void-background
  rendering.
- **Waypoints and death points** — create, edit, color, organize into sets, and
  toggle; automatic death-point markers; strict per-dimension rendering; and
  edge-of-minimap direction indicators for out-of-range waypoints. On both the
  fullscreen map and minimap, markers use the first character of the name in
  white over the player-selected background color, differing only in size.
- **Waypoint set management** — create, rename, and delete local sets; select
  multiple points (or every point in the current filter) and move them to another set in one
  operation. Deleting a set permanently deletes every waypoint it contains.
- **Public and chat-shared coordinates** — an optional server-owned public
  waypoint catalog with operator locks, plus preview-before-send chat sharing
  and click-to-import coordinate messages on servers without the companion.
  Public controls are hidden unless the connected server explicitly enables
  the public waypoint feature.
- **Entity radar** — hostile / passive / player / other classification, each
  with its own toggle, range, and entity cap.
- **Disk cache** — explored terrain persists per world / server / dimension /
  layer, so revisiting a world shows the map you already drew instead of a
  blank.
- **Seed-predicted underlay** — panning into unexplored Overworld or End
  terrain shows an instant seed-based guess (biomes, terrain height, 1.17
  tree candidates) that real captured tiles draw over as they load. Backed
  by a bundled [cubiomes](https://github.com/Cubitect/cubiomes) native build.
- **Structure candidates** — villages, ocean monuments, woodland mansions,
  outposts, ruined portals, and end cities show as semi-transparent markers
  until real data confirms or rules them out.
- **Prediction modes** — cycle with `P`: *everywhere* (default), *generated-
  only* (underlay masked to chunks the server generated), or *visited-only*
  (pure captured map, no prediction).
- **Optional server companion** — the same jar can run a server-side companion
  that returns compact per-column corrections against the real world; the seed
  and public waypoint catalog are shared only when the operator opts in.
- **Settings screen** — everything above is exposed in-game and takes effect
  immediately, no restart. Full English and Simplified Chinese localization.

## Keybinds

All rebindable under Minecraft's Controls screen, in the "Conflux Map"
category.

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
| `P` | Cycle prediction mode (everywhere / generated-only / visited-only) |
| `F9` | Reload prediction tiles |

## Waypoint management

Waypoints are rendered only in the dimension where they were created. The
Overworld, Nether, and End never project or convert one another's waypoint
markers. The waypoint list supports local set creation, renaming, cascading
deletion, and multi-select or current-filter select-all batch moves between sets.

A waypoint HUD overlay is not implemented yet. An immutable, read-only
waypoint data interface is reserved for a future HUD without exposing store
mutation operations.

## Public waypoints

Public waypoints require the same jar on the server and are disabled by
default. A level-2 operator can use `/confluxmap waypoints enable`, `disable`,
or `status`; the setting is persisted in `config/confluxmap/server.json`.
When the feature is disabled or unavailable, public waypoint buttons, tabs,
sharing choices, and settings are not shown on the client.
Public points are stored in the world directory, are visible to all connected
mod clients, and cannot be edited after publication. Any player can delete an
unlocked point; only operators can lock, unlock, or delete locked points.
The defaults are 512 points per world, 64 per publisher, and 30 mutations per
player per minute. Operators can tune `maxSharedWaypointsPerWorld`,
`maxSharedWaypointsPerPlayer`, and `sharedWaypointMutationsPerMinute` in the
same server config.

Chat sharing remains available on every server. Each send shows its name,
dimension, coordinates, and audience before confirmation. Recognized Conflux
Map or labelled `X/Y/Z` messages expose a click-to-import action that opens the
local waypoint editor before anything is saved.

## Building

Requires a Java 16-compatible JDK (Minecraft 1.17.1's toolchain); Loom fetches
Minecraft, mappings, and Fabric API automatically.

```sh
./gradlew :1.17.1:build
```

The jar is written to `versions/1.17.1/build/libs/`.

## License

GPL-3.0 — see [`LICENSE`](LICENSE). Third-party components and behavior
references are listed in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

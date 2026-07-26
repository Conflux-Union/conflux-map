# Conflux Map

**English** | [简体中文](README-CN.md)

Conflux Map is a Fabric client-side minimap and world map for Minecraft 1.17.1,
the whole 1.21 line, and 26.1.

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

See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) for third-party
components and attribution.

## Features

- **HUD minimap** — always-on corner overlay, square or circular, 4 corners,
  4 sizes, 4 zoom levels, optional player-facing rotation, coordinates and
  biome line.
- **Fullscreen world map** — pan/zoom map with multi-resolution tiles,
  cursor-anchored zoom, right-click to drop a waypoint.
- **Cave / Nether / End layers** — automatic underground detection in the
  Overworld; Nether current-layer / ceiling / manual-Y-slice modes; End
  void-background rendering.
- **Waypoints and death points** — create, edit, color, organize into sets, and
  toggle; automatic death-point markers; strict per-dimension rendering; and
  edge-of-minimap direction indicators for out-of-range waypoints.
- **In-world waypoint markers** — an optional light beam at each waypoint and a
  floating name and distance above it. Each can be turned off on its own, and
  you can set how far away they still show.
- **Waypoint set management** — create, rename, and delete local sets; select
  multiple points (or every point in the current filter) and move them to another set in one
  operation. Deleting a set permanently deletes every waypoint it contains.
- **Import from other mods** — bring your existing Xaero's Minimap and VoxelMap
  waypoints for the current world over in one click. Locations you already have
  are skipped, and your original files are left untouched.
- **Public and chat-shared coordinates** — an optional server-owned public
  waypoint catalog; on servers without the companion, coordinates shared in
  chat can still be imported with a click.
- **Entity radar** — hostile / passive / player / other classification, each
  with its own toggle, range, and entity cap.
- **Disk cache** — explored terrain persists per world / server / dimension /
  layer, so revisiting a world shows the map you already drew instead of a
  blank.
- **Seed prediction** — panning into unexplored Overworld or End terrain shows
  an instant seed-based guess at the biomes, terrain height, and trees, which
  the real map draws over as you explore it. Superflat worlds are recognized and
  predicted too. Backed by a bundled
  [cubiomes](https://github.com/Cubitect/cubiomes) native build.
- **Structure candidates and search** — every vanilla structure set available
  in the running game version is covered across the Overworld, Nether, and End.
  The fullscreen map shows localized candidate markers and can jump to the
  nearest candidate for a selected structure. Variants that share one placement
  set, such as village styles and warm/cold ocean ruins, are grouped together.
- **Prediction modes** — cycle with `P`: *everywhere* (default), *generated-
  only* (underlay masked to chunks the server generated), or *visited-only*
  (pure captured map, no prediction).
- **Optional server companion** — the same jar can run a server-side companion
  that returns compact per-column corrections against the real world; the seed
  and public waypoint catalog are shared only when the operator opts in.
- **Update check** — an optional check on startup that tells you in chat when a
  newer version is out, with a download link, plus a badge on the map screen.
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

Waypoints are rendered in the dimension where they were created. An optional
setting (off by default) additionally shows Overworld and Nether waypoints
from the linked dimension with the 8:1 portal coordinate conversion applied
on display; End waypoints always stay confined to the End. The waypoint list
supports local set creation, renaming, cascading deletion, and multi-select
or current-filter select-all batch moves between sets.

Waypoints can also show in the world itself, as a light beam with the name and
distance floating above it. Each part can be turned off on its own, and you can
set how far away they still show. An on-screen waypoint list overlay is not
implemented yet.

## Public waypoints

Public waypoints require the same jar on the server and are disabled by
default. A level-2 operator can use `/confluxmap waypoints enable`, `disable`,
or `status`; the setting is persisted in `config/confluxmap/server.json`.
When the feature is disabled or unavailable, public waypoint buttons, tabs,
sharing choices, and settings are not shown on the client.
Public points are stored in the world directory, are visible to all connected
mod clients, and cannot be edited after publication. An unlocked point can be
deleted only by its publisher; operators can lock, unlock, or delete any point.
The defaults are 512 points per world, 64 per publisher, and 30 mutations per
player per minute. Operators can tune `maxSharedWaypointsPerWorld`,
`maxSharedWaypointsPerPlayer`, and `sharedWaypointMutationsPerMinute` in the
same server config.

Chat sharing remains available on every server. Each send previews the exact
outgoing messages (Conflux Map and Xaero formats) before confirmation. Recognized Conflux
Map or labelled `X/Y/Z` messages expose a click-to-import action that opens the
local waypoint editor before anything is saved.

## Building

Requires JDK 21 or newer. Everything else — Minecraft, mappings, Fabric API, and the JDK 25 the
`26.1.2` build needs — is downloaded by Gradle on demand.

```sh
./gradlew :1.21.11:build
```

Replace `1.21.11` with any supported version above. The jar is written to
`versions/<minecraft-version>/build/libs/`.

## License

GPL-3.0 — see [`LICENSE`](LICENSE). Third-party components and behavior
references are listed in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

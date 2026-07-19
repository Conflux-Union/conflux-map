# Conflux Map

**English** | [简体中文](README-CN.md)

Conflux Map is a Fabric client-side minimap and world map mod for Minecraft
1.17.1. Its larger features are built from behavior specifications (see
[`docs/reference-specs/`](docs/reference-specs/README.md)) and reimplemented
from scratch in this project's own code. Third-party components and
attribution are documented in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## Features

Milestone 1 is the client-side core: everything below works standalone
against any server, vanilla or modded, with no server-side component
required.

- **HUD minimap** - always-on corner overlay, square or circular, 4 corners,
  4 sizes, 4 zoom levels, optional player-facing rotation, coordinates and
  biome info line.
- **Fullscreen world map** - pan/zoom explorable map with multi-resolution
  tiles, cursor-anchored zoom, right-click to drop a waypoint.
- **Cave / Nether / End layers** - automatic underground detection in the
  Overworld with hysteresis so it doesn't flicker at the boundary, Nether
  current-layer/ceiling/manual-Y-slice modes, End void-background rendering.
- **Waypoints and death points** - create, edit, color, group, and toggle
  waypoints; automatic death-point markers; correct 8:1 coordinate
  conversion between the Overworld and the Nether; edge-of-minimap direction
  indicators for out-of-range waypoints.
- **Entity radar** - hostile/passive/player/other classification with
  independent on/off toggles, range, and max-entity cap.
- **Disk cache** - explored terrain persists to disk per world/server per
  dimension per layer, so revisiting a world shows previously-mapped terrain
  immediately instead of a blank map.
- **In-game settings screen** - every setting above is exposed and takes
  effect immediately, no restart required (see the keybind table below).
- **Full English and Simplified Chinese localization.**

Milestone 2 adds a seed-predicted underlay and optional server correction
sync for the fullscreen map, backed by a vendored native
[cubiomes](https://github.com/Cubitect/cubiomes) build
(`cn.net.rms.confluxmap.nativepredict`) and MC-free prediction logic under
`cn.net.rms.confluxmap.core.predict`:

- **Seed-predicted underlay** - panning into unexplored Overworld or End
  terrain shows an instant seed-based guess (biomes, terrain height, and a
  synthesized tree canopy texture) that real captured tiles draw over as
  they load.
- **Structure candidate markers** - villages, ocean monuments, woodland
  mansions, outposts, ruined portals, and end cities show up as
  semi-transparent candidates until real data confirms or rules them out.
  Toggle with the `predictionShowStructures` setting.
- **Prediction view modes** - cycle with `P` between *everywhere* (underlay
  across the whole map, default), *generated-only* (underlay masked to
  chunks the server has generated; works offline from persisted
  corrections), and *visited-only* (no underlay, pure M1).
- **Optional companion sync** - the same jar runs an optional server
  companion that compares the prediction against the real world and returns
  compact per-column corrections; the seed is shared only when the operator
  opts in. Correction tiles and generated-presence persist across rejoin.

## Keybinds

All keybinds are rebindable in Minecraft's normal Controls screen, under the
"Conflux Map" category.

| Default key | Action |
|---|---|
| `H` | Toggle the minimap on/off |
| `]` | Minimap zoom in |
| `[` | Minimap zoom out |
| `M` | Open the fullscreen world map |
| `Y` | Cycle the manual map layer override |
| `U` | Open the waypoint list |
| `B` | Create a new waypoint at your current position |
| `,` | Open the settings screen |
| `P` | Cycle prediction view mode (everywhere / generated-only / visited-only) |
| `F9` | Reload prediction tiles |

## Building

Requires a JDK compatible with Minecraft 1.17.1's toolchain (Java 16); Loom
will otherwise fetch everything else (Minecraft, mappings, Fabric Loader)
automatically.

```sh
./gradlew :1.17.1:build
```

The built jar is written to `versions/1.17.1/build/libs/`. Milestone 1
targets Minecraft 1.17.1 only; later milestones add further versions under
`versions/` using the preprocessor-driven multi-version layout described in
[`docs/reference-specs/README.md`](docs/reference-specs/README.md) and this
repository's implementation plan.

## License

Conflux Map is licensed under the **GNU General Public License v3.0** - see
[`LICENSE`](LICENSE). Third-party components and behavior-reference sources
are documented in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

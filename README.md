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
- **Waypoints and death points** — create, edit, color, group, toggle;
  automatic death-point markers; correct 8:1 Overworld↔Nether conversion;
  edge-of-minimap direction indicators for out-of-range waypoints.
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
  is shared only when the operator opts in.
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
| `,` | Open the settings screen |
| `P` | Cycle prediction mode (everywhere / generated-only / visited-only) |
| `F9` | Reload prediction tiles |

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

# Conflux Map

**English** | [简体中文](README-CN.md)

Conflux Map is a Fabric minimap and world map built for multiplayer. It replaces the usual pile
of a minimap, a world map, a biome map, a chunk-load overlay, and a waypoint-sync mod with one
client jar, and it can sync a live map across a whole server for a few hundred bytes to a few
hundred kilobytes per update.

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
| `26.2`     | 26.2                    | ✓      | ✓            |

## Installation

Download the jar for your Minecraft version from [Releases](../../releases) (or build it
yourself, see [Building](#building)) and drop it into `mods/` next to
[Fabric API](https://modrinth.com/mod/fabric-api). [MaliLib](https://modrinth.com/mod/malilib) is
optional: it adds key-combo bindings and folds Conflux Map into its A+C settings switcher.

Everything in this README works single-player or on a plain vanilla server. Installing the
companion (the matching Fabric mod jar, or `confluxmap-paper-<version>.jar` on Paper) is optional
and is what turns per-player features into shared ones, see [Server companion](#server-companion).

## Features

**Live map.** A square or round minimap you can resize, rotate with your view, and place anywhere,
plus a fullscreen map with continuous zoom and a chunk grid. Right-click the fullscreen map to
drop a waypoint, share its coordinates, or teleport there. Both maps switch between normal
terrain, biome colors, and server chunk-load state, and follow the dimension you're in: surface
or cave for the Overworld, current-level or bedrock-roof for the Nether, a dedicated backdrop for
the End.

**Map autofill.** Once the world seed is known, unexplored Overworld, Nether-roof, and End tiles
show predicted terrain right away instead of staying blank. Real exploration and any corrections
from the server gradually replace the prediction. Press `P` to cycle full prediction,
generated-areas-only, and off.

**Structures.** Vanilla structures across all three dimensions get their own icons, visibility
toggles, and nearest-match search, with different markers for predicted versus confirmed
locations.

**Waypoints.** Local waypoints with names, colors, and sets; beams, names, and distances shown in
the world; the last five death points per dimension kept automatically; and one-click import from
Xaero's Minimap and VoxelMap (duplicates are skipped, the original files are left untouched). The
Overworld and Nether can optionally show each other's waypoints, converted through the 1:8 portal
ratio.

**Sub-worlds.** Servers reachable through the same address, including proxy networks, get separate
map records per world instead of one server's terrain bleeding into another's.

**Drawing and trails.** Lines, shapes, freehand paths, and text labels on the map, undoable and
either session-only or saved per world. A short recent-movement trail is also available on both
maps.

**Entity radar.** Players, mobs, dropped items, vehicles, and projectiles each have their own
toggle. Every living entity gets a portrait rendered live from its own model, so new or modded
mobs need no bundled art. Other players show on the minimap only while you hold the player-list
key (Tab by default); the fullscreen map always shows them.

**Export and updates.** Export any map area to a PNG at a chosen resolution, with a size estimate
and a cancellable background export. An optional startup check flags new releases in chat.

## Keybinds

All bindings are configurable from Minecraft's Controls screen, under "Conflux Map". With
MaliLib installed, they move to its hotkey screen instead (multi-key combos included), and
Conflux Map appears in MaliLib's A+C config switcher on 1.21.1 and newer. On 1.17.1, 1.18.2, and
1.20.1, Conflux Map picks up MaliLib's key registry when it's present, or otherwise keeps one
vanilla shortcut for opening its hotkey screen.

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
| `P` | Cycle map autofill (everywhere / generated-only / off) |
| `F9` | Refresh map autofill tiles |

## Waypoints in multiplayer

Waypoints stay in the dimension where you made them by default. An optional setting shows
Overworld and Nether waypoints in both dimensions at once; End waypoints always stay in the End.

Shared waypoints need the server companion and are off by default. A level-2 operator turns them
on with `/confluxmap waypoints enable` (`disable` and `status` also work). Once enabled, any
player can publish a point everyone sees. The publisher can delete their own point as long as
it's unlocked; only an operator can lock, unlock, or delete any point. Per-world and per-player
limits are configurable in `config/confluxmap/server.json`.

Chat coordinate sharing needs no companion and works on any server: before sending, you get a
preview of the outgoing message in both Conflux Map and Xaero formats, and coordinates shared by
other players import with one click.

## Server companion

The companion lets a whole server share one live map and one waypoint list instead of everyone
maintaining their own. Install the matching Fabric mod jar in a Fabric server's `mods/`, or
`confluxmap-paper-<version>.jar` in a Paper server's `plugins/` (Paper 1.21.1 through 26.2; Folia,
Spigot, and CraftBukkit aren't supported). Client and server versions don't need to match: a
mismatched companion falls back to larger, less efficient map updates, and an older one simply
skips correction sync, rather than either side breaking.

Everything the companion shares is controlled in `config/confluxmap/server.json`:

- `shareSeed` sends the world seed so clients can predict biomes and structures; `allowBiomeMap`
  and `allowStructureSearch` gate those two features independently.
- `shareChunkLoadState` exposes which chunks the server keeps loaded (off by default, since it can
  reveal farms and player activity).
- `allowEntityRadar` lets clients scan and render the entity radar.
- `shareCorrections` sends real-terrain corrections over the predicted map.
- `shareWaypoints` turns on the shared waypoint list.
- `webMap.*` runs an optional built-in browser map. It's off by default and bound to loopback
  only unless explicitly opened up; put it behind an HTTPS reverse proxy that preserves the
  original `Host` header if you expose it beyond your own machine.

None of this is anti-cheat: a modified client can predict terrain or scan entities on its own
regardless of these settings. They're privacy and bandwidth choices, not security ones.

Per-player rate limits and bandwidth budgets live in the same config file; run
`/confluxmap performance` in game to see your own connection's stats. Paper-specific installation,
terrain access, and storage details are in [`docs/paper-companion.md`](docs/paper-companion.md).

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

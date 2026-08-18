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

**Live map.** A square or round minimap you can resize, rotate with your view, and place anywhere
on screen; it steps aside on its own when a vanilla HUD element would overlap it, and its info
line can show your coordinates, your current biome, and the map layer in use. The fullscreen map
adds continuous zoom, a chunk grid, and a right-click menu that drops a waypoint, shares the spot
in chat, or teleports you there. Both follow the dimension you're in: surface or cave for the
Overworld, current-level or bedrock-roof for the Nether, a dedicated backdrop for the End. The
surface layer is shaded by time of day and nearby block light unless you turn that off.

**Map modes.** The fullscreen map switches its base layer between normal terrain, biome colors,
and the server's chunk-load state, the last one drawn either as four coarse bands or as exact
ticket levels. Chunk-load state needs the server companion; the minimap always draws terrain.

**Map autofill.** Once the world seed is known, unexplored Overworld, Nether-roof, and End tiles
show predicted terrain right away instead of staying blank. Real exploration and any corrections
from the server gradually replace the prediction. Press `P` to cycle predicting everywhere,
generated areas only, and explored terrain only. On a server that keeps its seed private, you can
type a seed in yourself under Settings → Map Autofill → Configure Local Seed: it stays on your
machine, is never sent to the server, and does not turn on map sync.

**Structures.** Vanilla structures across all three dimensions get their own icons, per-type
visibility toggles, and one shared on/off switch. Search by name for the nearest match, or give a
center, a radius, and a result count to list every candidate in an area and turn any of them into
a waypoint. Predicted and server-confirmed locations use different markers.

**Waypoints.** Local waypoints with names, colors, and sets; a searchable list you can filter by
set and move several entries at once; beams, names, and distances shown in the world, with
indicators at the screen edge for the ones out of view and an optional distance cutoff; death
points kept per dimension (five by default, adjustable from 0 to 50); and one-click import from
Xaero's Minimap and VoxelMap (duplicates are skipped, the original files are left untouched). The
Overworld and Nether can optionally show each other's waypoints, converted through the 1:8 portal
ratio.

**Sub-worlds.** Servers reachable through the same address, including proxy networks, get separate
map records per world instead of one server's terrain bleeding into another's. When the mod can't
tell which world you're on, it pauses recording and asks you to pick one; the same screen creates,
renames, and unbinds sub-worlds, merges an older same-seed map cache into the current world, and
moves waypoints left behind on an old record. A world the companion identifies for you is listed
under a name you give it, or a plain number, rather than its internal id.

**Server addresses.** One server reached through several addresses — a short alias, the full
hostname, a bare IP — keeps one map cache, waypoint set, and local seed instead of a separate copy
per address you typed. No companion needed: addresses that differ only in case or a trailing dot
merge on their own, and "Addresses" in the sub-world screen links or unlinks the rest by hand. An
explicit port is never assumed to be the default one, since `mc.example.com` and
`mc.example.com:25565` can reach different machines. A companion, when there is one, confirms the
relationship itself and saves you the manual link. Unlinking deletes nothing, it only splits what
gets stored from then on, and an address that already holds its own map data is never linked over.

**Drawing and trails.** Lines, shapes, freehand paths, and text labels on the map. Select and move
what you drew, recolor it, erase with a resizable eraser, and undo or redo with `Ctrl+Z` /
`Ctrl+Y`. Drawings are either kept with the world or dropped when you disconnect, and can be
mirrored onto the minimap. A short recent-movement trail is also available on both maps.

**Entity radar.** Players, mobs, dropped items, vehicles, and projectiles each have their own
toggle. Every living entity gets a portrait rendered live from its own model, so new or modded
mobs need no bundled art. Other players show on the minimap only while you hold the player-list
key (Tab by default); the fullscreen map always shows them.

**Web map.** An optional browser map served by the server companion, no game client required.
Shows the same explored tiles and terrain prediction, shared waypoints, and, if the operator turns
it on, player positions with names, plus dimension and language switches. Off by default and
loopback-only until an operator opens it up, see [Server companion](#server-companion). Players
can hide themselves from it with `/confluxmap webmap hide` (`show` to opt back in).

**Export.** Export any map area to a PNG at a chosen resolution: type the corner coordinates or
drag a box on the map, decide whether drawings are included, and check the size estimate before
starting. The export runs in the background and can be cancelled, copies the finished image to
your clipboard when it is small enough, and offers to open the output folder.

**Notices in chat.** An optional startup check flags new releases. After a few hours of play the
mod also posts a one-line invitation to a feedback survey, repeated at most once a day, with a
click to stop it for good.

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
| `P` | Cycle map autofill (everywhere / generated-only / explored-only) |
| `F9` | Refresh map autofill tiles |

## Waypoints in multiplayer

Waypoints stay in the dimension where you made them by default. An optional setting shows
Overworld and Nether waypoints in both dimensions at once; End waypoints always stay in the End.

Shared waypoints need the server companion and are off by default. A level-2 operator turns them
on with `/confluxmap waypoints enable` (`disable` and `status` also work). Once enabled, any
player can publish a point everyone sees, listed under "Shared Waypoints" in the waypoint screen.
Players without Conflux Map can use `/confluxmap waypoints add <name>` at their current position
and `/confluxmap waypoints list [page]` to browse the same public list. List entries include Xaero's
chat share format for one-click import. Operators can use the displayed short ID with `edit`,
`move`, `delete`, `lock`, and `unlock`.
The publisher can delete their own point as long as it is unmarked; only an operator can mark,
unmark, or delete any point, and marked points move to a separate "Server Markers" list. Per-world
and per-player limits are configurable in `config/confluxmap/server.json`.

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

- `enabled` is the master switch; with it off the companion answers nothing and every client falls
  back to plain single-player behavior. `checkForUpdates` prints a console notice at startup when
  a newer release exists.
- `shareSeed` sends the world seed so clients can predict biomes and structures; `allowBiomeMap`
  and `allowStructureSearch` gate those two features independently.
- `shareChunkLoadState` exposes which chunks the server keeps loaded (off by default, since it can
  reveal farms and player activity).
- `allowEntityRadar` lets clients scan and render the entity radar.
- `shareCorrections` sends real-terrain corrections over the predicted map.
- `shareWaypoints` turns on the shared waypoint list.
- `webMap.*` runs the browser map described in [Features](#features): default port `8123`,
  bound to `127.0.0.1` until explicitly opened up, and `sharePlayers` gates whether it includes
  player positions at all. Put it behind an HTTPS reverse proxy that preserves the original
  `Host` header if you expose it beyond your own machine.

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

---
title: FAQ & compatibility
---

# FAQ & compatibility

**Does the mod need anything on the server?**
No. The minimap, world map, waypoints, drawing, and export are fully
client-side. Installing the [server companion](technical/server-companion.md)
adds the live web map, shared waypoints, real-terrain corrections, and the
chunk load-level map.

**Which Minecraft versions are supported?**
One build per version line from 1.17.1 through 26.3 — see the
[supported versions table](getting-started/install.md#Supported-versions).
Paper servers are supported from 1.21.1 onward via the standalone plugin.

**Is MaliLib required?**
No, it's optional. With it, keybindings support multi-key combinations and
Conflux Map appears in MaliLib's A+C settings switcher. Without it,
keybindings live in Minecraft's controls screen.

**Can I bring my data from Xaero's or VoxelMap?**
Waypoints import from Xaero's Minimap and VoxelMap with one click; duplicates
are skipped and the original files stay untouched. Terrain recorded by
Xaero's World Map imports from the map screen's action tools, one dimension
at a time, and merges wherever Conflux Map has no capture of its own — see
[Drawings & export](features/drawings-export.md).

**Can a server disable parts of the mod?**
Yes — with the companion or any third-party plugin speaking the documented
[protocol](technical/protocol.md), a server can turn off the minimap, world
map, waypoints, prediction, or the entity radar for cooperating clients.
Servers without any companion never restrict anything.

**Can I reskin the map UI?**
Toolbar icons and minimap frames follow normal Minecraft resource packs, and
compatible assets from existing Xaero UI packs work as-is. Supported paths
and limits are documented in the
[UI resource packs spec](https://github.com/Conflux-Union/conflux-map/blob/master/docs/reference-specs/ui-resource-packs.md).

**Why do some structures show a loot preview and others don't?**
The preview can only enumerate chests the world generator places
deterministically — see the supported list in
[Chest loot preview](features/chest-loot-preview.md). Datapack-modified
loot tables are never predicted.

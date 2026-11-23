---
title: FAQ & compatibility
---

# FAQ & compatibility

## Installation & General

??? question "Does the mod require anything on the server?"
    **No.** The minimap, world map, waypoints, drawing, and PNG export all run entirely on the client.

    Installing the [server companion](technical/server-companion.md) adds the live web map, shared waypoints, real-terrain corrections, and the chunk load-level map.

??? question "Which Minecraft versions are supported?"
    One build per version line covers **1.17.1 through 26.3** — see the [supported versions table](getting-started/install.md#Supported-versions). Paper servers are supported from `1.21.1` onward via the standalone plugin.

??? question "Is MaliLib required?"
    **Optional.** Installing [MaliLib](https://modrinth.com/mod/malilib) enables multi-key combinations and adds Conflux Map to MaliLib's `A+C` settings switcher; without it, vanilla controls are used.

## Map Autofill & Chest Loot Preview

??? question "Why isn't predicted terrain showing up on a multiplayer server?"
    - **The server sends it**: set `shareSeed` to `true` in `config/confluxmap/server.json` and **restart the server**. The seed then becomes readable by modded clients.
    - **The player supplies it**: if the server has no companion installed and you know the seed, open Settings → Map Autofill → **Configure Local Seed** and enter the seed and target version for this server.

??? question "Why do some structures have a chest loot preview and others do not?"
    The preview can only enumerate chests whose **positions are fixed by the world generator and derivable from the seed** — see [Chest loot preview](features/chest-loot-preview.md) for the supported list.

    Structures such as villages, ocean ruins, woodland mansions, ancient cities, mineshafts, trial chambers, and monster rooms do not show a Loot entry. Datapack-modified loot tables are also not predicted.

## Data Migration & Appearance

??? question "Can I migrate my data from Xaero or VoxelMap?"
    **Yes.**

    - **Waypoints**: One-click import from Xaero's Minimap and VoxelMap; duplicates are skipped and original files remain untouched.
    - **World map data**: Use the import tool in the fullscreen map's action menu to merge Xaero's World Map data per dimension wherever Conflux Map has no capture yet — see [Drawings & export](features/drawings-export.md).

??? question "Can I customize the map UI with resource packs?"
    **Yes.** Toolbar icons and minimap frames follow standard Minecraft resource packs, and compatible assets from Xaero UI packs continue to work. Supported paths and limits are documented in [UI resource packs](https://github.com/Conflux-Union/conflux-map/blob/master/docs/reference-specs/ui-resource-packs.md).

## Server Controls & Web Map

??? question "Can a server disable specific client features?"
    **Yes.** Through the server companion (or any third-party plugin implementing the [network protocol](technical/protocol.md)), a server can turn off the minimap, world map, waypoints, prediction, or entity radar for cooperating clients. Servers without the companion impose no restrictions.

??? question "How should I expose the built-in web map publicly?"
    By default, the web map listens only on `127.0.0.1:8123`. For public access, place it behind an **HTTPS reverse proxy** (such as Nginx or Caddy) configured to preserve the original `Host` header.

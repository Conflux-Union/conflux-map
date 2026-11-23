# Conflux Map

**English** | [简体中文](README_CN.md) | [Wiki](https://conflux-union.github.io/conflux-map/)

[![Modrinth downloads](https://img.shields.io/modrinth/dt/conflux-map?logo=modrinth&label=Modrinth&color=1bd96a)](https://modrinth.com/mod/conflux-map) [![CurseForge downloads](https://img.shields.io/curseforge/dt/1663891?logo=curseforge&label=CurseForge&color=f16436)](https://www.curseforge.com/minecraft/mc-mods/conflux-map) [![MC百科 ID](https://img.shields.io/badge/MC%E7%99%BE%E7%A7%91_ID-30075-3f85c6?logo=data%3Aimage%2Fpng%3Bbase64%2CiVBORw0KGgoAAAANSUhEUgAAAA4AAAAOCAYAAAAfSC3RAAAACXBIWXMAAAAAAAAAAQCEeRdzAAACfklEQVR4nHVS7UtTURy%2BHyoCP5TQfxN9DMt0vrSXu0VfAvsQCBmGVL5MdDrLyiB6IYgWFJSUM%2B%2Fm3K7bdTqzmQudd%2FfmnLqXtmmb07vmbsR8OndEH8IOPDz8OM9zzvM7v0NVVB6m2t7WdNz20ZsmTv2zb0ojHwRlb8BnyNwYrjJVVB6iFFPbXb8BvR41iOC%2FME3RMHPVMH7sRKPFZqIGZnWJPk5bMk%2FTpf4pbVmksNlLo9%2BrI7WWsB6D0zVo527t0wxfop2xbcrEaSSjW4V2tgo9XENZrHA7ewZGdy16OTU62NO44riJ6pEgzjMr0Nt4mepxN%2B5axSEEUhN46m9B52Q1Hs81YyFlByM8wZ1pGjNRK7h4BlziB0zzUajH%2BCJldNVLQtYHZS0kHWh1nMTcBlOuV1OL8Kes%2BC4DzPo2RtcyaHKtQGMTZarb1SAtZlxI70SRlsMY8jYhKUWwuScgEF9DIFfAy%2BUkTg0vof5DCDqbCL2dGI1OcmNuBrMRBpH8Z4S3lpCQluBed%2BBLKodP2V94EVSMQWLkoRrlQSvGrgmVFM0T4epr2MPPyhHfr4iwkFvC2QLYb3nEJRmPFlN4JW7hfiAJmgmRqGz9rjf2Bu%2F4QTzwXYKYS6PZHUP%2FfBwjkSwus2H403kI23sI78gYj%2B5AN7ZcJOPQ5rs9dejjzuIqe49ECUJL%2BtAwAhrGBKgJn7PyqCURawjqyn0KMvkAmpiZq91vmXxY0trXoR8nc7ILygP8hWH8K0GZ92lbqGRwRjLKl7t23fecnLxGmhb%2BQDwQOiaEi54Y6izuLupo5TFKZfG0XnBGNshpRSIoEOz9gwKJVySaBDF1HDl%2BgvoNorIyyNSoGRkAAAAASUVORK5CYII%3D)](https://www.mcmod.cn/class/30075.html) [![QQ Group](https://img.shields.io/badge/QQ%20Group-362669270-eb1923?logo=qq&logoColor=white)](https://qm.qq.com/q/Qimxluy2UW)

Conflux Map is a Fabric minimap and world map mod. The client runs on its own: the minimap, fullscreen world map, biome layer, waypoints, structure and biome search, map drawing, and PNG export are all contained in a single JAR. Installing the server companion adds a server-wide live map, a shared waypoint list, and a web map accessible from a browser.

> 📖 Full documentation, keybinds, configuration reference, server companion setup, and protocol specs live on the **[Wiki](https://conflux-union.github.io/conflux-map/)**.

## Supported versions

| Build | Loads on | Fabric (Client/Server) | Paper plugin |
|---|---|:---:|:---:|
| `1.17.1` | 1.17.1 | ✓ | — |
| `1.18.2` | 1.18.2 | ✓ | — |
| `1.20.1` | 1.20.1 | ✓ | — |
| `1.20.6` | 1.20.5, 1.20.6 | ✓ | — |
| `1.21.1` | 1.21, 1.21.1 | ✓ | ✓ |
| `1.21.3` | 1.21.2, 1.21.3 | ✓ | ✓ |
| `1.21.4` | 1.21.4 | ✓ | ✓ |
| `1.21.5` | 1.21.5 | ✓ | ✓ |
| `1.21.8` | 1.21.6, 1.21.7, 1.21.8 | ✓ | ✓ |
| `1.21.9` | 1.21.9, 1.21.10 | ✓ | ✓ |
| `1.21.11` | 1.21.11 | ✓ | ✓ |
| `26.1.2` | 26.1, 26.1.1, 26.1.2 | ✓ | ✓ |
| `26.2` | 26.2 | ✓ | ✓ |
| `26.3` | 26.3 | ✓ | ✓ |

## Installation

- **Client**: Download the JAR for your Minecraft version from [Releases](../../releases) and place it in `.minecraft/mods/` alongside [Fabric API](https://modrinth.com/mod/fabric-api). [MaliLib](https://modrinth.com/mod/malilib) is optional and adds multi-key hotkeys plus the `A+C` settings switcher on 1.21.1+.
- **Server (optional)**: Place the matching JAR in `mods/` on a Fabric server, or the standalone plugin in `plugins/` on a Paper server. See the [Server companion guide](https://conflux-union.github.io/conflux-map/technical/server-companion/).

## Features

### Exploration & Mapping

- [Minimap](https://conflux-union.github.io/conflux-map/features/minimap/) — square or round, custom placement, automatic vanilla HUD avoidance
- [World map](https://conflux-union.github.io/conflux-map/features/world-map/) — map/biome/chunk load-level layers, per-dimension height views
- [Map autofill](https://conflux-union.github.io/conflux-map/features/autofill-prediction/) — seed-based terrain prediction for unexplored areas, replaced live as you explore
- [Structure & biome search](https://conflux-union.github.io/conflux-map/features/structure-biome-search/) — find and pin vanilla structures and biomes
- [Chest loot preview](https://conflux-union.github.io/conflux-map/features/chest-loot-preview/) — preview and display chest loot contents
- [Portal markers](https://conflux-union.github.io/conflux-map/features/portal-markers/) — nether portals, end portals, and gateways you have seen

### Waypoints, Collaboration & Tools

- [Waypoints](https://conflux-union.github.io/conflux-map/features/waypoints/) — sets, death points, 1:8 cross-dimension display, one-click Xaero/VoxelMap data migration
- [Shared waypoints](https://conflux-union.github.io/conflux-map/features/shared-waypoints/) — server-wide shared list, GUI and command controls
- [Entity radar](https://conflux-union.github.io/conflux-map/features/entity-radar/) — players and mobs, fine-grained filtering
- [Drawings & export](https://conflux-union.github.io/conflux-map/features/drawings-export/) — map annotations, measure tool, PNG export, one-click Xaero map data migration
- [Multi-server data](https://conflux-union.github.io/conflux-map/features/multi-server/) — Velocity sub-world isolation, same-seed merging, linked addresses
- [Appearance](https://conflux-union.github.io/conflux-map/features/appearance/) — UI animations, vanilla & Xaero resource-pack theming

See the [keybind list](https://conflux-union.github.io/conflux-map/reference/keybinds/), [configuration reference](https://conflux-union.github.io/conflux-map/config/), and [FAQ](https://conflux-union.github.io/conflux-map/faq/).

## For developers

Building from source and the developer workflow are documented in [Developing](https://conflux-union.github.io/conflux-map/technical/developing/). The experimental client API is covered in [Public API](https://conflux-union.github.io/conflux-map/technical/api/), and the sync protocol in [Network protocol](https://conflux-union.github.io/conflux-map/technical/protocol/).

## Contact

For bug reports, suggestions, or general discussion, reach us through the official channels:

- Email: [contact@cxu.org.cn](mailto:contact@cxu.org.cn)
- QQ group: [362669270](https://qm.qq.com/q/Qimxluy2UW)
- Discord: [discord.gg/eyFaJhfnV6](https://discord.gg/eyFaJhfnV6)

## License

GPL-3.0, see [`LICENSE`](LICENSE). Third-party components and behavior references are listed in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## Contributors

Contributions are welcome — see the [contributing guide](CONTRIBUTING.md) to get started.

[![Contributors](https://contrib.rocks/image?repo=Conflux-Union/conflux-map)](https://github.com/Conflux-Union/conflux-map/graphs/contributors)

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=Conflux-Union/conflux-map&type=Date)](https://star-history.com/#Conflux-Union/conflux-map&Date)

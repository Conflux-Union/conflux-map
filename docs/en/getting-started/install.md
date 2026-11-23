---
title: Installation
---

# Installation

## Client Installation

1. Download the Conflux Map JAR for your Minecraft version from [Releases](https://github.com/Conflux-Union/conflux-map/releases) (or Modrinth / CurseForge).
2. Make sure [Fabric API](https://modrinth.com/mod/fabric-api) for the same Minecraft version is installed.
3. Place both JAR files into your `.minecraft/mods/` folder and launch the game.

!!! tip "Optional Enhancement: MaliLib"
    [MaliLib](https://modrinth.com/mod/malilib) is optional. When installed:

    - Keybindings move to MaliLib's hotkey screen with **multi-key combination** support.
    - On Minecraft `1.21.1` and newer, Conflux Map appears in MaliLib's `A+C` settings switcher.

## Server Installation (Optional)

The minimap, world map, waypoints, drawing, and PNG export work **entirely client-side** in singleplayer or on any server. To enable **server-wide live map corrections, shared waypoints, chunk load-level visibility, and the built-in web map**, install the companion matching your server platform:

- **Fabric server**: Place the matching Conflux Map JAR into the server's `mods/` directory.
- **Paper server**: Place the standalone `confluxmap-paper-<version>.jar` into the server's `plugins/` directory (supports Paper `1.21.1` through `26.3`).

See [Server companion (Fabric)](../technical/server-companion.md) and [Paper companion](../technical/paper-companion.md) for setup and configuration details.

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

To build from source yourself, see [Developing](../technical/developing.md).

---

See also: [Quick start](quickstart.md) · [FAQ & compatibility](../faq.md)

---
title: Server companion (Fabric)
---

# Server companion

Installing the server companion enables server-wide real-terrain corrections, a shared waypoint list, and a built-in browser web map.

## Installation

- **Fabric server**: Place the matching Conflux Map JAR into the server's `mods/` directory.
- **Paper server**: Place `confluxmap-paper-<version>.jar` into `plugins/` (supports Paper `1.21.1` through `26.3` — see [Paper companion](../technical/paper-companion.md)).

!!! info "Independent Upgrades & Differential Sync"
    Client and server versions can be upgraded independently: matching prediction algorithms use compact differential updates, differing algorithms fall back to full-data updates, and older-protocol clients always retain the basic map service.

## Special World Generators

Servers running [quadra-gen](https://github.com/Fallen-Breath/quadra-gen) are detected automatically: with the companion installed, the predicted map follows its four per-quadrant world styles (natural terrain, flat glass, biome void, and empty void) instead of drawing natural terrain everywhere. The same applies in singleplayer when `quadra-gen` manages the world.

## Configuration: `config/confluxmap/server.json`

### 1. Core, Seed & Terrain Sync

| Option | Default / Behavior |
|---|---|
| `enabled` | Master switch for the server companion |
| `checkForUpdates` | Announces newer versions in the server console at startup |
| `shareSeed` | **Off by default.** When enabled, the seed becomes readable by the client game for biome and structure prediction |
| `allowBiomeMap` | Controls whether clients may use the biome layer |
| `allowStructureSearch` | Controls whether clients may use the structure finder |
| `shareCorrections` | Sends real-terrain data from the server to correct predicted maps |
| `fullSyncEnabled` | **Off by default** (Fabric companion required). Allows operators to run `/confluxmap fullsync` to bulk-sync all generated regions of the current dimension (`/confluxmap fullsync cancel` to abort) |
| `shareChunkLoadState` | **Off by default.** Exposes chunks the server keeps loaded (off by default to reduce exposure of player activity and farm locations) |

### 2. Client Feature Restrictions & Radar

| Option | Default / Behavior |
|---|---|
| `allowEntityRadar` | **On by default.** Shares live online player positions with compatible clients; disabling it stops the position stream |
| `allowMinimap` | **On by default.** When off, cooperating clients hide the HUD minimap |
| `allowWorldMap` | **On by default.** When off, cooperating clients disable the fullscreen world map (including export, annotations, and markers) |
| `allowWaypoints` | **On by default.** When off, cooperating clients disable local waypoints |
| `allowPrediction` | **On by default.** When off, cooperating clients disable the seed-prediction underlay |
| `restrictionMessage` | Plain-text explanation shown when a player opens a disabled feature; leave empty for localized defaults (max 256 UTF-8 bytes) |

!!! note "Third-Party Plugins"
    Like all client-side restrictions, these only bind cooperating clients. Third-party server plugins can deliver all of these restrictions without the companion by speaking the protocol in [Network protocol](../technical/protocol.md).

### 3. Shared Waypoints & Performance Stats

- `shareWaypoints`: Enables the shared waypoint list (default `true`).
- `allowNonOperatorSharedWaypointManagement`: Lets ordinary players manage entries they published (default `true`).
- Per-player rate limits and bandwidth budgets live in the same file; players can run `/confluxmap performance` in game to inspect their connection's sync statistics.

## Built-in Web Map (`webMap.*`)

The companion serves a standalone HTTP web map showing explored terrain, predicted terrain, and shared waypoints (see [Web map](../features/web-map.md) for the browsing side):

- **Default bind**: `127.0.0.1:8123`, enabled by default with player positions hidden (`sharePlayers` controls inclusion).
- **Public access**: Place it behind an HTTPS reverse proxy that preserves the original `Host` header.
- **Shared entry point**: With `sharedGateway` (on by default), a Fabric and a Paper server on the same host share one web map entry; visitors pick an instance from the **Switch server** dropdown, and `serverDisplayName` sets the name shown for this server.
- **Privacy**: Players can run `/confluxmap webmap hide` in game to hide themselves from the web map, and `/confluxmap webmap show` to return.

### Web Map Options

| Option | Default | Behavior |
|---|---|---|
| `enableChat` | Off | Shows the web chat panel |
| `showGameChat` | On | Relays game chat into the web panel |
| `allowWebChat` | Off | Allows browsers to send messages to the game |
| `allowPlayerSearch` | Off | Player search in the browser |
| `allowWaypointSearch` | On | Public-waypoint search in the browser |
| `showWaypointImages` | On | Shows waypoint photos in the browser |
| `waypointImageMode` | `APPROVAL` | Who sees waypoint photos: `APPROVAL` (operator-approved), `OP_ONLY`, `EVERYONE`, or `DISABLED` |
| `defaultMode` | `AUTO` | Initial region mode for new visitors: `AUTO`, `ALL`, or `GENERATED` |

### In-Game Server Configuration

Operators can edit these options in game under **Settings → Server** — toggles, enums, and numbers showing the live value, alongside map-sync options such as `allowBiomeMap` and `shareChunkLoadState`. Changes that can apply online take effect immediately; the remainder state that they require a server restart, and a failed save restores the previous value.

### Custom Title and Favicon

Set `"title"` and `"favicon"` inside `webMap` in `server.json`:

```json
{
  "webMap": {
    "title": "Conflux Map",
    "favicon": "favicon.png"
  }
}
```

- Place the icon beside `server.json` (usually `config/confluxmap/`) and specify only its filename. PNG and ICO files up to **1 MiB** are supported; paths and URLs are not.
- A blank title uses `Conflux Map`; a blank icon keeps the default behavior; an unreadable icon logs a warning without blocking startup.
- Restart the server after changes (browsers cache the icon for 5 minutes). This changes the browser tab title only, not the `Conflux Map` label inside the map.

---

See also: [Paper companion](../technical/paper-companion.md) · [Shared waypoints](../features/shared-waypoints.md) · [Multi-server data](../features/multi-server.md)

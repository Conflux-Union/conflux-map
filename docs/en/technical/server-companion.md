---
title: Server companion (Fabric)
---

# Server companion

With the companion installed, the whole server shares one live map and one
waypoint list.

- Fabric server: place the matching JAR into `mods/`.
- Paper server: place `confluxmap-paper-<version>.jar` into `plugins/`
  (Paper 1.21.1 through 26.3) — see
  [Paper companion](paper-companion.md).

Client and server versions can be upgraded independently: matching
prediction algorithms use compact differential updates, differing algorithms
fall back to full-data updates, and older-protocol clients always retain the
basic map service.

Servers running [quadra-gen](https://github.com/Fallen-Breath/quadra-gen)
are supported automatically: with the companion installed, the predicted map
follows its four per-quadrant world styles (natural terrain, flat glass,
biome void, and empty void) instead of drawing natural terrain everywhere.
In singleplayer, the same applies when quadra-gen manages the world.

The companion also serves a standalone web map that shows explored terrain,
predicted terrain, and shared waypoints in a browser. Player positions,
names, dimensions, and the interface language are all configurable; players
can run `/confluxmap webmap hide` in game to hide themselves from the web
map, and `show` to return.

## Configuration

All companion-shared content is controlled in `config/confluxmap/server.json`:

- `enabled` is the master switch; `checkForUpdates` announces a newer
  version in the server console at startup.
- `shareSeed` sends the world seed to clients so they can predict biomes
  and structures. It is disabled by default because sharing the seed
  exposes it to every player; `allowBiomeMap` and `allowStructureSearch`
  control the biome layer and the structure finder separately.
- `shareCorrections` sends real-terrain data from the server to correct
  predicted maps.
- `fullSyncEnabled` allows operators to run `/confluxmap fullsync`, which
  bulk-syncs every generated region of the current dimension to their own
  client (cancel with `/confluxmap fullsync cancel`). It discloses the
  entire generated map, so it is disabled by default and additionally
  requires operator permission and the Fabric companion.
- `shareChunkLoadState` exposes the chunks the server keeps loaded. It is
  disabled by default to reduce exposure of player activity and farm
  locations.
- `allowEntityRadar` defaults to `true` and shares every online player's
  live position with compatible clients; disabling it stops the position
  stream and turns the client radar off.
- `allowMinimap`, `allowWorldMap`, `allowWaypoints`, and `allowPrediction`
  let a server turn off individual client features - the HUD minimap, the
  fullscreen world map (including map export, annotations, and map
  markers), local waypoints, and the seed-prediction underlay. They all
  default to `true`; when a feature is off, compatible clients hide it and
  its settings show a "disabled by this server" notice. Like every
  client-side restriction this only binds cooperating clients.
- `restrictionMessage` sets the plain-text explanation shown when a player
  tries to open a disabled map or toggle the disabled minimap. Leave it
  empty to use the client's translated default; messages are limited to 256
  UTF-8 bytes. Third-party server plugins can deliver all of these
  restrictions without the companion by speaking the protocol documented in
  [Network protocol](protocol.md).
- `shareWaypoints` enables the shared waypoint list and defaults to `true`;
  `allowNonOperatorSharedWaypointManagement` defaults to letting ordinary
  players manage the entries they published.
- `webMap.*` controls the web map and defaults to enabled: it listens on
  `127.0.0.1:8123` and hides player positions. For public access, place it
  behind an HTTPS reverse proxy that preserves the original `Host` header;
  `sharePlayers` controls whether player positions are included.

Per-player rate limits and bandwidth budgets are stored in the same
configuration file. Players can run `/confluxmap performance` in game to see
the sync statistics for their own connection. Paper-specific installation
and storage details are documented in [Paper companion](paper-companion.md).

## Web map title and favicon

Set `"title": "Conflux Map"` and `"favicon": "favicon.png"` inside `webMap`
in `server.json`. Place the icon beside `server.json` (usually
`config/confluxmap/`) and specify only its filename. PNG and ICO files up
to 1 MiB are supported; paths and network URLs are not supported. A blank
title uses `Conflux Map`; a blank icon preserves the existing behavior
without a custom icon. An unreadable icon logs a warning without preventing
the map from starting. Restart the server after changes. Icons have a
five-minute browser cache. This changes the browser tab title, not the
`Conflux Map` label inside the map.

See also: [Shared waypoints](../features/shared-waypoints.md),
[Multi-server data](../features/multi-server.md)

# Paper companion

The standalone `confluxmap-paper` plugin provides the same public companion protocols as the
Fabric server entrypoint. One Paper artifact targets Paper 1.21.1 through 26.2. It is deliberately
separate from the version-specific Fabric client artifacts: bundling Bukkit classes into every
remapped Fabric jar would enlarge every client download and create avoidable class-loading and
compatibility surfaces.

## Installation

1. Build with `./gradlew :paper:build`, or download the Paper artifact from a release.
2. Put `confluxmap-paper-<version>.jar` in the Paper server's `plugins/` directory.
3. Keep the normal version-specific Conflux Map Fabric jar on each client.
4. Start the server once to create `config/confluxmap/server.json`, then adjust the opt-in policy.

For local development, run `./gradlew :paper:runServer`. The task builds the plugin, downloads a
Paper 1.21.1 development server, installs the new jar, and keeps its disposable state under the
ignored `paper/run/` directory. The first invocation writes `paper/run/eula.txt`; review the
Minecraft EULA, change that file to `eula=true`, and invoke the task again.

The plugin bytecode targets Java 21 and supports the Paper API only. Run the
[Java version required by Paper](https://docs.papermc.io/paper/getting-started/): Java 21 for Paper
through 1.21.11 and Java 25 for Paper 26.x. Folia, Spigot, and CraftBukkit are outside the supported
runtime contract. A proxy needs no companion plugin as long as it transparently forwards Minecraft
plugin messages.

## Compatibility and protocol

The plugin registers the existing `confluxmap:map_sync` and `confluxmap:waypoints_v1` plugin
message channels. It uses the same message codecs, negotiation rules, payload caps, policy flags,
per-player request spacing, pending-work bounds, and bandwidth token buckets as the Fabric
companion. Matching and mismatched client predictor versions therefore follow the existing
feature-level compatibility rules; the server never rejects a client solely because its mod
version string differs.

Matching predictor profiles use the bundled native baseline to send compact residual records.
Predictor mismatches, unavailable native support, custom generators, and dimensions without a
supported baseline fall back per response to authoritative absolute records. This preserves the
wire contract and visual result without relying on Paper internals or version-specific NMS
mappings.

## Terrain access

Loaded chunks are captured on the Paper main thread as immutable `ChunkSnapshot` values. Summary
work then uses the platform-neutral column seam. Unloaded chunks are read directly from the
world's `.mca` and external `.mcc` files by a bounded read-only scanner supporting GZIP, ZLIB,
uncompressed, and LZ4 Anvil payloads. Correction requests never call `getChunkAt` and therefore do
not generate terrain as a side effect.

Disk scans and patch construction run on two daemon workers. Plugin messages and all Bukkit world
access stay on the main thread. Live summaries use Fabric's demand window and two-chunk-per-tick
main-thread ceiling, further bounded by `maxChunkSummariesPerSecond`; chunk changes publish both
tile and exact-region invalidations. LOD 3/4 legacy tile requests scan progressively and return
`PARTIAL` while incomplete, so a coarse request cannot monopolize a worker or the server thread.

## Data ownership

- Policy: `<server-root>/config/confluxmap/server.json`, using the same schema as Fabric.
- Stable world ID: `<primary-world>/confluxmap/world_uuid.json`.
- Shared waypoints: `<primary-world>/confluxmap/shared_waypoints.json`.
- Web-map player opt-outs: `<primary-world>/confluxmap/webmap-hidden.txt`.
- Terrain source: each Bukkit world's resolved `region/` directory. Standard Nether and End
  `DIM-1/region` and `DIM1/region` layouts and Paper 26.x namespaced
  `dimensions/<namespace>/<dimension>/region` layouts are recognized when present.

The primary world is the first loaded normal-environment world. Dimension indexes are append-only
for the plugin lifetime so a late world load cannot change an index already sent to clients.

## Commands

- `/confluxmap performance` shows the current player's completed correction-sync averages.
- `/confluxmap webmap hide|show` controls whether that player appears on the optional public radar.
- `/confluxmap waypoints status` shows the effective shared-waypoint state and quotas.
- `/confluxmap waypoints enable` loads storage, atomically persists the setting, then advertises it.
- `/confluxmap waypoints disable` blocks mutations immediately and persists the setting.

Waypoint administration requires `confluxmap.admin`, granted to operators by default.

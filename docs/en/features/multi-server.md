---
title: Multi-server data
---

# Multi-server data

Conflux Map provides per-world isolation and address linking for Velocity networks and multi-address servers.

## Velocity & Sub-Worlds

- **Per-World Storage**: A single server address behind a Velocity proxy can host multiple sub-worlds; map data is stored separately per world.
- **Merging & Migration**: The sub-world screen lets you create, rename, and clear identification records, merge an older same-seed map cache into the current world, and migrate waypoints from older records.

## Linked Addresses

- **Manual Linking**: When one server is reachable through multiple addresses, link them to share the same map cache, waypoints, and local seed.
- **Automatic Server Identification**: When the [server companion](../technical/server-companion.md) is installed, the server informs the client directly which addresses point to the same world.

---

See also: [Waypoints](waypoints.md) · [Server companion](../technical/server-companion.md)

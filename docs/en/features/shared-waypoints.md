---
title: Shared waypoints
---

# Shared waypoints

Shared waypoints require the [server companion](../technical/server-companion.md)
and are enabled by default. Ordinary players can publish waypoints visible
to everyone and manage the entries they published; operators can manage
every entry.

Players without Conflux Map can work with the same list through commands:

- `/confluxmap waypoints add <name>` publishes a waypoint at the player's
  current position
- `/confluxmap waypoints list [page]` lists entries with pagination, each
  including an Xaero chat format for one-click import
- `/confluxmap waypoints edit <id> <name>` / `move <id>` / `delete <id>`
  rename, move, or delete an entry using the short ID shown in the list
- `/confluxmap waypoints group list` / `create <name>` / `rename <id> <name>`
  / `delete <id>` manage waypoint sets that group the shared list; clients
  with Conflux Map create, rename, and delete sets from the Public tab of
  the waypoint screen
- `/confluxmap waypoints group assign <waypoint> <group|none>` moves an
  entry into a set or back to the ungrouped list; clients with Conflux Map
  do this by selecting entries in the Public tab and using Move
- Operators can control the feature with `/confluxmap waypoints disable`,
  `enable`, and `status`

Per-world and per-player limits are configured in
`config/confluxmap/server.json`.

Chat coordinate sharing requires no server companion and works on any
server.

See also: [Waypoints](waypoints.md), [Network protocol](../technical/protocol.md)

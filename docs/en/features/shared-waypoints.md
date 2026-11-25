---
title: Shared waypoints
---

# Shared waypoints

Shared waypoints require the [server companion](../technical/server-companion.md) and are enabled by default. Ordinary players can publish waypoints visible to everyone and manage their own entries; operators can manage all entries.

!!! tip "Works Without the Client Mod"
    Players without Conflux Map installed can still view and manage the shared waypoint list using `/confluxmap waypoints` commands. Chat coordinate sharing works on any server without the companion.

## Command Reference

| Command | Description |
|---|---|
| `/confluxmap waypoints add <name>` | Publish a waypoint at your current position |
| `/confluxmap waypoints list [page]` | Paginated list (each entry includes an Xaero chat format for one-click import) |
| `/confluxmap waypoints edit <id> <name>` | Rename a waypoint using the short ID from the list |
| `/confluxmap waypoints move <id>` | Move a waypoint using its short ID |
| `/confluxmap waypoints delete <id>` | Delete a waypoint using its short ID |
| `/confluxmap waypoints group list` | List all shared waypoint sets |
| `/confluxmap waypoints group create <name>` | Create a shared waypoint set |
| `/confluxmap waypoints group rename <id> <name>` | Rename a shared waypoint set |
| `/confluxmap waypoints group delete <id>` | Delete a shared waypoint set |
| `/confluxmap waypoints group assign <waypoint> <group\|none>` | Move an entry into a set or back to ungrouped |
| `/confluxmap waypoints disable \| enable \| status` | Operator controls to toggle or inspect shared waypoints |

!!! info "GUI Management"
    Clients with Conflux Map installed can create, rename, and delete sets directly from the **Public** tab of the waypoint screen, or select entries for batch moving.

## Waypoint Photos

When publishing a local waypoint, select up to four photos from its album in the confirmation dialog; a saved public waypoint accepts photos through **Upload waypoint image**. Photos stay on the submitting player's device until selected, and operators preview and decide each submission under **Settings → Server → Review waypoint photos**. Approved photos are shown to everyone who opens the waypoint on the [web map](web-map.md).

Per-world and per-player limits are configured in `config/confluxmap/server.json`.

---

See also: [Waypoints](waypoints.md) · [Network protocol](../technical/protocol.md)

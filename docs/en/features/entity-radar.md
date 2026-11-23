---
title: Entity radar
---

# Entity radar

The entity radar displays nearby players, mobs, and item entities in real time on the map.

## Categories & Fine-Grained Filtering

- **Category Toggles**: Players, hostile mobs, friendly mobs, and other entities each have an independent toggle (all shown by default).
- **Per-Entity-Type Overrides**: From the **Manage Entity Visibility** screen in radar settings, individual entity types can be forced shown or hidden regardless of their category toggle.

## Icon Modes

- **Minimap**: Shows generated entity portraits and item icons by default, or compact category-colored dots that temporarily expand while the vanilla player-list key is held.
- **Fullscreen World Map**: Always displays detailed icons.

!!! note "Server Player Position Stream"
    When the [server companion](../technical/server-companion.md) is installed with `allowEntityRadar` enabled, clients also receive live positions of all online players for beyond-tracking-distance, cross-dimension viewing, and right-click highlight tracking. This map-wide player display is driven by the server position stream, separate from local entity-radar mob filtering.

---

See also: [Minimap](minimap.md) · [Server companion](../technical/server-companion.md)

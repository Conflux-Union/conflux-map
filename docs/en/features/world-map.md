---
title: World map
---

# World map

Press `M` to open the fullscreen world map for browsing explored terrain, predicted terrain, and map markers.

## Navigation & Interaction

- **Zoom & Pan**: Continuous zoom centered on the cursor, smooth panning, and an adjustable zoom range — both vanilla zoom limits can be set between 20% and 200% of their originals (hover the slider to see the original limit).
- **Coordinate Jump**: Enter `X`/`Z` coordinates (within ±30,000,000) and jump there while the background map stays visible.
- **Dynamic Lighting**: Map lighting follows the vanilla brightness setting, in-game time of day, and nearby block light.
- **Context Menu**: Right-click anywhere on the map to create a waypoint or share coordinates; teleportation (including cross-dimension targets) is offered when permitted by the server.

## Layers & Height Views

The world map provides three layers — **map**, **biome**, and **load level** — and you can press `Y` to cycle height views:

| Dimension / Layer | Description |
|---|---|
| **Overworld** | Surface, current-cave, and fixed-height views |
| **Nether** | Current-level, bedrock-roof, and below-bedrock views |
| **The End** | Void-adapted background |
| **Load-level layer** | Status bands or exact levels — from the server companion in multiplayer, or from the built-in server in singleplayer; the active height is labeled on the map |

## Marker Toggles & Views

- **Waypoints & Structures**: Toggle structure icons, nether portals, end gateways, and map screenshots independently. Waypoint photos get a map entry when **Show photos at waypoints** is on (see [Waypoints](waypoints.md)).
- **View**: Toggle the real-map coverage highlight, the chunk grid, and the load-level layer. Right-click and drag a box on the map to show the chunk grid temporarily.

!!! tip "Real Map Coverage"
    The coverage highlight marks terrain the client has actually seen — predicted terrain never counts as covered. Choose **Dim Prediction and Blank Areas** or a **white solid-and-dashed outline**, and dim the unexplored underlay further with **Prediction and Blank Area Brightness** (0–100%); real terrain always keeps its own brightness.

---

See also: [Autofill & prediction](autofill-prediction.md) · [Waypoints](waypoints.md) · [Drawings & export](drawings-export.md)

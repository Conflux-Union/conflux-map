---
title: Drawings & export
---

# Drawings & export

The fullscreen world map includes built-in annotation tools, a measure tool, region PNG export, and one-click Xaero map data migration.

## Map Drawings

- Draw **lines, shapes, freehand paths, and text labels** directly on the map.
- Select, move, recolor, and delete finished drawings with undo and redo support.
- Drawings are saved per world and can also be shown on the minimap.

## Measure Tool

- Left-click to place points and view both segment lengths and the running total.
- Right-click or press `Ctrl+Z` to remove the last point; points are kept per dimension until all are undone.

## Region Selection & PNG Export

- **Chunk-Aligned Selection**: Drag with the **right mouse button** on the fullscreen map to select a region snapped to chunk borders, with chunk and block counts labeled at the center.
- **Background PNG Export**: Export any area at a chosen resolution by entering corner coordinates or using the current selection. Exports run in the background with size estimates, progress, cancellation, and buttons to **copy to clipboard** or **open the output directory**.

## One-Click Xaero Map Data Migration

Open the fullscreen map's action tools and choose the import entry to merge data recorded by **Xaero's World Map**:

- Reads explored map data per dimension from Xaero's files and merges it into the local cache **only where Conflux Map has no capture yet**.
- Previously explored areas become visible even without the server companion.
- Re-importing never overwrites terrain captured by Conflux Map itself, and revisiting an area live replaces the imported copy.

---

See also: [World map](world-map.md) · [Waypoints](waypoints.md)

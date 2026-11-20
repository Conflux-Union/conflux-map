---
title: Drawings & export
---

# Drawing and export

- Lines, shapes, freehand paths, and text labels can be drawn on the map.
  Finished drawings can be selected, moved, recolored, and deleted, with
  undo and redo. Drawings are kept with the world and can also be displayed
  on the minimap.
- The measure tool places points with left-click and shows the length of
  every segment plus the running total; right-click or Ctrl+Z removes the
  last point, and points are kept per dimension until undone.
- Dragging with the right button on the fullscreen map selects a region
  snapped to chunk borders and labels its size in chunks and blocks at the
  center; the selection stays highlighted until the next input anywhere on
  the map.
- Any area can be exported as a PNG at a chosen resolution by entering two
  corner coordinates or selecting a rectangle on the map. The export runs in
  the background with a size estimate, progress display, and cancellation,
  and the result can be copied to the clipboard or opened in its output
  directory.
- Terrain data recorded by Xaero's World Map can be imported into the map:
  open the map screen's action tools and pick the import entry. Conflux Map
  reads the explored terrain from that installation's own data files, one
  dimension at a time, and merges it into the local map cache wherever no
  own capture exists yet, so previously explored terrain shows up even
  without the server companion. Re-importing never overwrites terrain
  captured by Conflux Map itself, and revisiting an imported area live
  replaces the imported copy.

See also: [World map](world-map.md), [Waypoints](waypoints.md)

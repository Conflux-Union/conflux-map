---
title: Portal markers
---

# Portal markers

Conflux Map marks **activated nether portals, end portals, and end gateways** that you have seen on both the minimap and the fullscreen world map.

## Details

- **Icons & Chunk Highlights**: Each portal displays an icon at its position with an optional translucent highlight over its chunk.
- **Customization**: Each portal kind can use any texture resource location, with configurable icon size, opacity, and highlight color in settings.
- **Cleanup**: Markers are remembered per world and per dimension; a portal dismantled elsewhere is removed once you load its chunk again.

!!! note "Detection Scope"
    Detection is purely client-side, so only portals in chunks you have loaded are recorded.

---

See also: [World map](world-map.md) · [Waypoints](waypoints.md)

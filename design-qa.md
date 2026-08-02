# Subworld Management Screen Design QA

- Source visual truth: `C:/Users/30710/Downloads/ChatGPT Image 2026年8月1日 14_25_15.png`
- Implementation screenshot: `C:/Data/Project/conflux-map-command-switch-guard/versions/1.21.1/run/screenshots/confluxmap-client-world.png`
- Viewport: native Minecraft client, 1994 x 1056 screenshot, GUI scale 2
- Source pixels: 1458 x 1086
- Implementation pixels: 1994 x 1056; compared as full-screen desktop layouts without density resampling
- State: one selected current profile with 232 persisted explored chunks in the Overworld

## Full-view comparison evidence

The source and implementation were opened together. Both use the requested three-region hierarchy: a narrow scrollable world list on the left, a wide selected-world area on the upper right, and a full-width management/action area below. The implementation intentionally uses Minecraft's native square borders, typography, button states, and translucent in-game background instead of reproducing the sketch's paper texture and rounded placeholder frame.

The selected-world area keeps profile metadata and the world map as separate bordered panels. The map is visible from persisted region history, north-up, fitted to the complete explored extent, and marks the last recorded player position at its projected location.

## Focused region comparison evidence

The right-side map and bottom controls were inspected at full screenshot resolution. Labels remain inside their panels, the two action rows do not overlap, the map footer stays distinct from the raster, and the fitted map keeps its aspect ratio with unused space around the shorter axis. The top-right server notification is transient game state outside this screen's layout and was not treated as design drift.

## Findings

No actionable P0, P1, or P2 mismatch remains.

- [P3] A predominantly snowy explored area produces a very bright thumbnail against the dark panel. This is faithful to the stored map colors; an optional future polish pass could add a subtle checker or neutral backing for transparent edges without modifying terrain colors.

## Required fidelity surfaces

- Fonts and typography: native Minecraft font, hierarchy, wrapping, truncation, and button labels are consistent with the surrounding mod UI.
- Spacing and layout rhythm: left/right proportions, panel gaps, map/footer separation, and two-row action spacing match the source hierarchy.
- Colors and visual tokens: native dark translucent panels and standard button states are used consistently; no web-style gradient or card treatment was introduced.
- Image quality and asset fidelity: the preview uses the actual persisted map color planes, not a placeholder, generated image, or re-render prerequisite.
- Copy and content: headings distinguish profile information, exploration-history map, and world management; scale, explored chunk count, dimension, and last position are visible.

## Comparison history

1. The first implementation had no native screenshot and used a tile-composition path that could report no cache while history files existed.
2. The preview was replaced with direct `.cfr` history decoding, chunk-derived explored bounds, proportional auto-fit, and projected last-position marking.
3. The revised 1.21.1 client screenshot shows the historical map and all three requested layout regions; no P0/P1/P2 visual issue remains.

## Implementation checklist

- [x] Narrow left profile list with wheel, draggable scrollbar, and keyboard paging.
- [x] Separate selected-profile information panel and actual history-map panel.
- [x] Direct persisted-region rendering with proportional exploration fit and north lock.
- [x] Full-width, two-row world management control area.
- [x] Native runtime screenshot and source/implementation comparison.

final result: passed

# Reference Specs

Behavior notes for Conflux Map's larger features — what each system does and
why, as data shapes, algorithm steps, constants, and edge cases. Written from
scratch in this project's own words; no code or identifiers from any other mod.

For behavior inspired by closed-source mods (VoxelMap, Xaero's), these notes
were produced by observing observable behavior only, then implementing
independently. See [`THIRD_PARTY_NOTICES.md`](../../THIRD_PARTY_NOTICES.md)
for that attribution. Numeric constants and thresholds are included where they
matter, because they are behavior.

## Documents

| Document | Covers |
|---|---|
| [`surface-color-sampling.md`](surface-color-sampling.md) | Turning a loaded chunk column into one map pixel — block color, biome tint, slope/water shading. |
| [`zoom-lod-ux.md`](zoom-lod-ux.md) | Minimap / world-map zoom steps, rotation, corner/size layout, multi-resolution LOD. |
| [`cave-nether-layers.md`](cave-nether-layers.md) | Cave-mode auto-detection, Nether current-layer / ceiling / Y-slice modes, End rendering. |
| [`waypoint-ux.md`](waypoint-ux.md) | Waypoint data model, cross-dimension coordinate conversion, death points, list/edit UI, indicators. |
| [`radar-icons.md`](radar-icons.md) | Entity classification, radar dot rendering, above/below elevation cues, icon mapping. |
| [`predicted-map.md`](predicted-map.md) | Seed prediction, tolerant correction diff, companion patch wire format. |

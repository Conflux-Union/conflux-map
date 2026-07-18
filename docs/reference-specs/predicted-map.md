# Predicted Map Reference

Conflux Map keeps prediction in a separate `!pred` tile plane. A real live or cached tile always
overlays it; predictions never enter the `.cfr` column cache.

## Determinism

The wire baseline is `{biomeId u8, surfaceY i16, kind u8, fluidDepth u8}`. The predictor version
is `cb:e61f90580cbd|shim:1|base:1`; palette colours are local and never sent. Forest `LAND` and
`FOLIAGE` are equivalent for diffing, height differences up to 2 blocks are tolerated (6 in
forested pixels), and fluid depth compares in buckets `0`, `1-3`, `4-9`, `10+`. A real map colour
outside the biome's expected set is retained as a correction so player builds are visible.

## Companion protocol

`confluxmap:m2` v1 uses big-endian framed messages. `MAP_VIEW_REQ` carries up to eight tile
coordinates and a cached revision. `MAP_PATCH` carries a 16x16 chunk presence bitmap and a
deflate-compressed two-level sparse mask: 32-byte coarse mask, one 32-byte fine mask per coarse
cell, then six-byte absolute column records. Server patches are capped at LOD 2; higher LODs stay
local. A missing or mismatched predictor uses absolute samples, never a false residual.

Prediction is honest about its limits: structures are candidates until verified by the companion,
and no server seed is shared unless the operator enables `shareSeed`.

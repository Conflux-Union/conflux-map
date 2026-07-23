# Predicted Map

Conflux Map keeps prediction in a separate `!pred` tile plane. A real live or cached tile always
overlays it; predictions never enter the `.cfr` column cache.

## Determinism

The wire baseline is `{biomeId u8, surfaceY i16, kind u8, fluidDepth u8}`. The predictor version
is `cb:071ca3452ea2|shim:3|base:9`; palette colours are local and never sent. Natural canopy stays
on the predicted plane instead of becoming a generated-chunk correction, so generated frontiers
cannot introduce foliage-colour seams. Other height differences up to 2 blocks are tolerated, and
fluid depth compares in buckets `0`, `1-3`, `4-9`, `10+`. A real map colour outside the biome's
expected set is retained as a correction so player builds are visible.

Predicted tile textures contain time-independent terrain colours. Dynamic day/night brightness is
applied as one render-time tint across the whole predicted plane, so composition order cannot leave
adjacent tiles at different brightness levels.

Predicted terrain layers a fixed southwest directional relief over the captured-map absolute-height
curve. At LOD0, where one texel represents one block, relief uses the same one-sided southwest
neighbor as the captured map so shading cannot bleed into the block before a terrain edge. Higher
LODs average two axial samples and one diagonal sample on each shoulder, then normalize their
difference by the LOD's blocks per pixel. A one-block-per-axis diagonal rise reaches the capped 36%
RGB brightness contrast. The one-pixel sampled margin makes the relief continuous across tile
boundaries. Void or unknown cells disable relief for any kernel that crosses them.

Rainy 1.17.1 biomes that cross vanilla's high-altitude freezing threshold render a deterministic
snow cover. Prediction uses the midpoint snow line because the baseline does not carry vanilla's
small horizontal temperature-noise offset: Y=95 for mountain/stone-shore families, Y=125 for
taiga/giant-spruce families, and Y=155 for giant-tree taiga.
Frozen-ocean and frozen-river baselines keep an ice surface at sea level instead of being flattened
through the ordinary open-water branch.
All Overworld terrain below sea level uses Vanilla's default water fill, including low ground whose
sampled biome is not itself oceanic; End terrain is explicitly excluded from this rule.

LOD0-1 canopy uses cubiomes' 1.17.1 natural tree candidates. A chunk with an unsupported vegetation
pipeline keeps the previous deterministic canopy locally; a native failure falls back for the full
tile. Higher LODs retain the aggregate canopy texture because individual tree candidates are no
longer distinguishable.
Jungle-tree candidates use a deterministic tall-canopy estimate instead of the ordinary-tree height,
so dense jungle does not collapse into a flat surface a few blocks above the predicted ground.
The terrain-feature cave mask is not applied to the surface plane, and approximate structure bounds
remain candidate markers rather than being painted as terrain.

## Companion protocol

`confluxmap:map_sync` v1 uses big-endian framed messages. `MAP_VIEW_REQ` carries up to eight tile
coordinates and a cached revision. A tile is 256 output pixels per edge and covers `2^lod` LOD-0
regions per side. `MAP_PATCH` carries a 16x16 output-cell presence bitmap (one chunk per cell at
LOD0, the union of touched chunks at higher LOD) and a
deflate-compressed two-level sparse mask: 32-byte coarse mask, one 32-byte fine mask per coarse
cell, then six-byte absolute column records. Server patches are capped at LOD 2; higher LODs stay
local. Incremental residual patches use an otherwise-invalid `UNKNOWN` column as a removal marker
when a previously corrected pixel returns to the predicted baseline. Older clients already ignore
that marker as non-authoritative terrain. A missing or mismatched predictor uses absolute samples,
never a false residual.

Prediction is honest about its limits: the v1 companion does not verify structure existence, so
structure markers remain candidates. No server seed is shared unless the operator enables
`shareSeed`.

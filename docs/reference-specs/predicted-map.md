# Predicted Map

Conflux Map keeps prediction in a separate `!pred` tile plane. A real live or cached tile always
overlays it; predictions never enter the `.cfr` column cache.

## Determinism

The wire baseline is `{biomeId u8, surfaceY i16, kind u8, fluidDepth u8}`. The predictor version
is `cb:9afc1038ea5a|shim:8|base:14`; palette colours are local and never sent. Synthetic canopy stays
on the predicted plane instead of becoming a generated-chunk correction, so generated frontiers
cannot introduce foliage-colour seams. Other height differences up to 2 blocks are tolerated, and
fluid depth compares in buckets `0`, `1-3`, `4-9`, `10+`. A real map colour outside the biome's
expected set is retained as a correction so player builds are visible.

Predicted tile textures contain time-independent terrain colours. Dynamic day/night brightness is
applied as one render-time tint across the whole predicted plane, so composition order cannot leave
adjacent tiles at different brightness levels.

Recent composed prediction tiles also form a bounded CPU mip cache. One coarse tile is rebuilt
recursively from four complete lower-LOD children with the captured map's alpha-weighted 2x2 box
filter. The cached result includes committed correction colours plus biome/surface cursor metadata;
changing any child invalidates every cached ancestor. Local deterministic prediction has no
wall-clock expiry inside a world session. Skipping companion work is stricter: every contributing
child must carry a final server validation no older than five seconds. Progressive, missing,
future-dated, or expired entries cannot suppress a request.

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
Frozen-ocean surface ice uses Vanilla's fixed temperature-noise mask; deep frozen ocean keeps its
ordinary water surface because its visible ice comes from placed iceberg features.
Every supported Overworld version uses the same overview pipeline. Cubiomes first returns a cheap
height at every output pixel. A globally aligned exact surface grid, one anchor per 8 output pixels
at LOD0 and per 16 at LOD1-4, then corrects the overview by bilinearly expanding only the
exact-minus-overview residual. This keeps the high-frequency overview detail visible at LOD3-4
instead of interpolating one raw height across a 4x4 texel square. LOD0 uses a denser 34x34 exact
grid (including the tile margin); LOD1-4 use 18x18, so close views retain more shoreline and height
precision without making LOD1 expensive merely because its tile covers more world. The anchor's
world spacing naturally grows with the LOD's blocks per pixel.

The Java layout, correction density, biome pass, fluid pass, and tests are identical for 1.17.1 and
1.21+. Only the internal cubiomes overview-height formula follows the version's own terrain generator.
Final surface biomes use the corrected heights. Fluid is categorical and resolved independently at
every output pixel. Exact anchors provide water confidence, which is bilinearly interpolated before
being combined with the corrected terrain floor and final ocean/river biome. The categorical flag
is never copied from the nearest anchor because that would quantize swamp pools and shorelines into
rectangles whose world size changes at every LOD. End terrain keeps its separate dimension-specific
height sampler.

Canopy uses one seed-deterministic overview on every Minecraft version. At LOD0-1, jittered blob
anchors form sparse crowns while dense jungle uses the inverse field as irregular clearings; at
LOD2+ canopy becomes an aggregate per-pixel texture. The predicted tile path does not enumerate
natural tree decorators per chunk: that work grows with world area rather than visible pixels and
previously made LOD1 four times more expensive than LOD0.
The terrain-feature cave mask is not applied to the surface plane, and approximate structure bounds
remain candidate markers rather than being painted as terrain.

## Structure candidates

The structure catalog follows the vanilla structure sets present in the selected cubiomes game
version. It covers Overworld, Nether, and End structures, including the ring-based stronghold
placement and the Nether-fossil placement/biome rule. Configured variants that share a placement
set are one search category: village styles, mineshaft styles, shipwreck styles, warm/cold ocean
ruins, and dimension-appropriate ruined-portal variants do not create duplicate markers.

Visible-region lookup batches placement regions and applies cubiomes' generation-viability check
before a marker is cached. Strongholds use the vanilla ring iterator instead of pretending to use
a random-spread grid. The fullscreen search asks the native locator for the nearest viable candidate
of one localized structure type within 100,000 blocks and recenters the existing map view when one
is found. Dense one-chunk placement sets are hidden at distant zoom levels but remain searchable.

The cache filename carries structure format v2 and the cubiomes game-version number because the
earlier six-entry catalog used incorrect cubiomes ordinals and structure placements can change when
a world is upgraded. Old or cross-version candidate positions are intentionally not reused.

## Companion protocol

`confluxmap:map_sync` v4 uses big-endian framed messages. `MAP_VIEW_REQ` carries up to eight tile
coordinates and the revision of each client's last committed snapshot. A tile is 256 output pixels
per edge and covers `2^lod` LOD-0 regions per side. A final `MAP_PATCH` is a complete authoritative
snapshot, not a temporal delta: applying it atomically replaces every older residual sample.

Each patch carries a 16x16 diagnostic presence bitmap plus a raw `PatchCodec` body. The body starts
with separate two-level sparse masks for evaluated pixels and pixels that differ from the shared
deterministic baseline. Difference samples then use homogeneous value planes for biome, signed
surface Y, surface kind, top map colour, fluid depth, and floor map colour (seven bytes per sample).
All fields are compared exactly. An evaluated pixel without a difference sample reconstructs from
the baseline; a difference sample is the server's absolute actual value. Returning to the baseline
therefore needs no removal marker—the next complete snapshot simply omits that sample.

`PatchCodec` writes the evaluated and difference sparse masks, then homogeneous value planes.
Surface heights are zigzag-varint deltas in pixel order; categorical fields remain grouped for
long terrain runs. Each body is Deflate-compressed with bounded inflation before `MAP_PATCH` is
framed, so client traffic accounting reflects the compact payload instead of the pre-compression
field planes. A missing or mismatched predictor uses a complete absolute snapshot, never a false
residual.

Every supported map LOD can carry corrections. LOD3-4 are scanned progressively under one bounded
server-tick budget. Every covered chunk is still visited so a visible construction cannot
disappear, but cold NBT summaries and current `.cfs` caches materialize only the centered source
columns represented by the output: four columns per chunk at LOD3 and one at LOD4. Revision-0
partial snapshots replace pending progress but stay non-drawable; only the final snapshot replaces
the committed tile. A coarse tile fully covered by fresh lower-LOD client results is omitted from
`MAP_VIEW_REQ`, so it consumes no server scan or response work.

Prediction is honest about its limits: the companion does not verify structure existence, so
structure markers remain candidates. No server seed is shared unless the operator enables
`shareSeed`.

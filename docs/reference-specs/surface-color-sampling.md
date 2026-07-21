# Surface Color Sampling

How a loaded chunk column becomes one map pixel: block color, biome tint,
slope and water shading, and the split between the live and cached passes.

The reference implementation has **two parallel rendering passes** that
recompute pixel colors with the same core algorithm and constants:

- **Live/minimap pass** — recomputed continuously against the currently
  loaded game world, driving the small always-on overlay and the
  fullscreen/live view.
- **Cached/world-map pass** — a persisted, per-region-file map that stores
  one snapshot of surface/height/light data per column the first time it's
  scanned, and re-renders from that stored snapshot (re-applying tinting,
  shading, and current lighting) even when the player is nowhere near that
  area anymore.

They share the exact same surface-detection rules, color sourcing, tint
rules, and shading formulas. Every place they genuinely diverge is called
out explicitly below (mainly §4's shading reference point and §4's
light-source timing). A separate "biome overlay" display mode (replacing or
washing the whole pixel with a single flat, arbitrarily-assigned-and-persisted
per-biome debug color) exists as a toggle but bypasses essentially all of the
algorithm below; it is out of scope for this document beyond that mention.

## 1. Surface determination

**Heightmap basis:** the column's nominal top is found via a "motion
blocking" style heightmap — the highest position whose block has either a
non-empty collision shape or a fluid, i.e. the same category of heightmap
Minecraft itself exposes for "where would something standing on this column
land." This is one query for the whole column, not a per-block-type scan.

**Waterlogged/submerged-greenery collapse:** before any opacity or color
decision is made about a block, waterlogged states plus both kelp and seagrass
states are replaced with a plain "just the fluid" representation for every
purpose downstream (opacity test, color, tint, everything). This happens on
the very first block fetched and again on every step of the descent below.
Practically: a waterlogged stair, slab, sign, or plant renders as plain water,
and dense green underwater vegetation renders as the surrounding ocean instead
of map noise. Coral and other submerged decoration remain eligible for the
seafloor overlay scan.

**Opacity test** applied to a candidate block: it counts as opaque if its
light-dampening factor is greater than zero; if that factor is zero but the
block can occlude and uses shape-based light occlusion, it's also tested by
checking whether its downward-facing or upward-facing occlusion shape fully
occludes — either one being true also counts as opaque.

**Descent loop:** starting from the heightmap's top block, while the current
block is *not* opaque, remember it as a transparent-candidate and move one
block down, refetch (applying the waterlogged collapse again), and retest.
Repeat until an opaque block is found or the world's bottom is reached.
There is **no fixed depth limit** on this descent — it can walk all the way
to the world floor.

Two separate "overlay" slots are derived from that walked stack:

- **Transparent-overlay slot:** the *first* (topmost) block seen when the
  descent began — captured once and never overwritten as the descent
  continues.
- **Foliage-overlay slot:** the *last* transparent block seen immediately
  before the opaque surface was found — i.e. the bottom of the stack,
  closest to the ground. If the descent loop never actually ran (the
  heightmap's top block was already opaque), a special branch instead
  re-fetches whatever block sits directly on top of that surface block and
  uses that as the foliage candidate.
- If top and bottom of the stack are the same single block, the
  foliage-overlay slot is cleared (so a lone transparent block isn't drawn
  twice, once as each layer).
- **Any block strictly between the top and bottom of a 3-or-more-block-tall
  transparent stack is silently dropped** — only the topmost and bottommost
  survive as renderable overlays.

**Snow layers:** if the resolved foliage-overlay candidate is specifically a
thin snow-layer block, it is *promoted to be the surface block itself*
(replacing the found opaque block as "the" surface) rather than drawn as an
overlay, and the foliage slot is cleared. (A grass block that merely has a
snowy top texture because it's in a snowy biome needs no special handling —
its own model/texture already reflects that, and it's sampled like any other
block.)

**Leaves:** leaves have a nonzero light-dampening factor in the underlying
game, so the general descent loop treats them as opaque — a tree canopy is
drawn as the surface, not the ground underneath it.

**Small plants, flowers, saplings, glass, etc.:** these have a zero
light-dampening factor and no occluding shape, so the descent loop walks
through them; they only appear at all if they end up as the top or bottom of
the transparent stack per the rule above.

**Seafloor scan (water/ice columns only):** triggered specifically when the
resolved surface block is water or ice. Starting one block below that
surface, the scan continues downward while the current block's
light-dampening factor is **less than 5** *and* the block is not a leaves
block. This threshold (5) is deliberately much stricter than the ">0 is
opaque" rule used above for general surface-finding — ordinary leaves
(dampening around 1) would *not* stop this loop on the dampening check
alone, which is exactly why leaves get an explicit, separate stop condition
here.

- While scanning down, the first block that is not water, not ice, not air,
  not a bubble column, and registers as opaque on the motion-blocking
  heightmap test is captured as the transparent-overlay candidate — but only
  if that slot wasn't already filled by the general surface pass above.
- Similarly, the first block (not water/ice/air/bubble-column) that differs
  from whatever just got captured as the transparent-overlay candidate is
  captured as the foliage-overlay candidate — again only if not already set.
  This is how things like coral end up rendered as an overlay on top of the
  seafloor. Kelp and seagrass have already collapsed to water and are excluded.
- This scan also has **no fixed depth limit** beyond the world's vertical
  bounds and the two stop conditions above.
- If the scan reaches the very bottom of the world while the column is
  *still* water the whole way down (a bottomless/void water column), no
  seafloor block is recorded at all — nothing is drawn beneath the water
  there.

**Void / no-surface-found fallback:** if the descent never finds an opaque
block anywhere down to the world's bottom, the surface height is replaced
with a placeholder value and a "no real surface" flag is set:

- Live/minimap pass: placeholder = the viewer's own current vertical
  position, plus one.
- Cached/world-map pass: placeholder = a fixed constant, **80** (there is no
  "current viewer" concept for an arbitrary remote pixel).

That flag disables all height/slope shading (§4) for the pixel and forces
its light value to zero, which in turn makes the pixel's color fully
transparent — this is how void gaps render as see-through rather than as a
solid color. One exception is carved out: if the block that actually ended
up resolved as the surface is lava, the "no real surface" flag is
force-cleared again immediately afterward (lava is never treated as a void
gap). The exact real-world scenario that produces "resolved surface is lava"
*and* simultaneously trips the void fallback is a narrow corner case in the
traced logic — see Confidence notes.

**Underground / ceilinged-dimension (cave, nether-like) surface search:**
instead of the world heightmap, the surface is found relative to the
*viewer's own current vertical position*:

- If the viewer's own position is already inside an opaque-or-lava block,
  scan **downward**, unbounded, for the first non-opaque non-lava gap, and
  report one above the last solid block found.
- Otherwise (viewer standing in open space), scan **upward**, bounded to 10
  blocks above the viewer's position, for the first opaque-or-lava block and
  report that block's own height; if nothing solid is found within that
  10-block window, report "no surface" instead.

This means cave/nether-style rendering shows the ceiling/floor nearest to
wherever the viewer currently is, not a top-down world heightmap.

## 2. Base block color

There is **no use of Minecraft's built-in "map item" color palette**
anywhere in this pipeline. Colors come from sampling the block's own
resource-pack texture pixels directly out of the currently-loaded, live
stitched block-texture atlas — so recoloring a resource pack changes the map
colors too, live.

**Primary method:** collect the block's baked render-model quads that face
upward plus any unculled/general-facing quads, composite them into a small
internal image approximating "the block as seen from directly above," and
box-filter/downsample that whole composite down to a single pixel. This is a
genuine spatial average over all the texture area the model shows from
above — not a single texel lookup.

**Fallback method** (used when the block has no standard render model, or
the primary method produces zero usable faces): sample the block's single
"particle" texture — the texture the game already uses for that block's
break-particle effect, generally its most representative face — and
box-filter/downsample that whole texture region to one pixel. For fluid
blocks specifically, if the particle texture resolves to a generic/missing
placeholder icon, the still/flowing water or lava texture is substituted
directly by name instead.

**Unresolvable textures:** if neither method can find real texture
coordinate data (the block maps to the engine's own "missing texture"
placeholder), the cached color becomes a near-black, low-alpha placeholder:
**alpha 27/255, RGB 0** — a faint, mostly-invisible marker rather than a
loud error color. The same minimum-alpha floor of 27/255 is also applied
generally: any computed color whose alpha lands below 27 gets clamped up to
27.

**Caching granularity:** the result is cached once per **exact block
state**, including every property value (so, e.g., each distinct redstone
power level, or each distinct leaf "persistent/distance" flag combination,
is an independent cache entry with its own independently-computed color).

### Per-block special cases layered on top of plain texture sampling

- **Cobwebs:** forced to fully opaque (alpha 255), overriding whatever alpha
  the sampled texture itself produced.
- **Redstone wire:** its color is baked in per exact block state using the
  game's own power-level-to-color function, applied unconditionally while
  building the cached base color — independent of, and prior to, any biome
  tint step (§3). Different power levels are literally different cache
  entries.
- **Sign posts / wall signs:** alpha forced to 31/255 (~12%).
- **Doors:** alpha forced to 47/255 (~18%).
- **Ladders and vines:** alpha forced to 15/255 (~6%).

These four fixed-alpha overrides exist to make thin, non-full-block geometry
read as faint/translucent on a flat top-down map instead of as a solid tile.

### Non-biome ("default tint") color table

A second, separate cached color table exists for a settings mode where
per-position biome tinting is turned off entirely. In that mode:

- Most tintable blocks use the game's own *context-free* default tint (its
  registered tint function evaluated with no world position at all).
- Water specifically gets a hardcoded default tint constant instead:
  **0x3F76E4**, applied at full alpha (so only RGB is affected, not the
  water texture's own sampled alpha).
- Three specific plants — the double-tall grass plant, the double-tall fern
  plant, and sugar-cane/reeds — are excluded from the generic default-tint
  path above and instead get a fixed reference grass color computed via the
  game's grass-color gradient function evaluated at a **fixed
  temperature/humidity pair of (0.7, 0.8)**, rather than at any real biome's
  actual values. (Ordinary single-height grass and fern are *not* in this
  exclusion list — they use the generic default path.)
- **Important caveat for modded blocks in this mode:** blocks outside the
  fixed "known tintable" set (see §3) that aren't vanilla-namespaced go
  through a live per-position tint lookup instead of a context-free one when
  their color is first cached — but only succeed if evaluated against a
  position inside a currently-loaded chunk. In the traced reference build,
  the fallback used when that position's chunk *isn't* loaded is an
  unconditional stub that always reports "no tint available." The practical
  effect: a modded block's non-biome-mode cached color can permanently end
  up as the same near-invisible placeholder described above (alpha 27/255,
  RGB 0) instead of its real base color, if the very first time its color
  gets cached happens to be evaluated without a loaded-chunk position behind
  it (a realistic scenario on the cached/world-map pass, which routinely
  colors areas that aren't currently loaded).

### Optional resource-pack color extensions

Support exists for layering in third-party, resource-pack-driven color
overrides (per-block custom palettes and "connected texture" style tinting
definitions, in the style some popular texture-pack-compatibility mods
support) when such a pack is installed. This is an optional, pack-supplied
data source layered on top of the baseline algorithm above, not part of the
mod's own baseline behavior — its exact grid/gradient sampling rules are
treated as out of scope here beyond noting the feature exists and that, when
active, it's consulted before the built-in per-block resolvers.

## 3. Biome tint application

**Which blocks are eligible for a biome tint at all** — a fixed set
(alongside anything a modded block registers on its own, see §2/§6): the
grass-top block, seven leaf types (oak, spruce, birch, jungle, acacia, dark
oak, mangrove), short grass, short fern, double-tall grass, double-tall
fern, sugar cane/reeds, vines, lily pads, water, leaf litter, and a
low bush/shrub-type block. (One further entry that appears to be intended
for a "tall flower" block is present in the underlying list but never
actually gets assigned to a real block in the traced source — it's an inert,
always-null member of that set.)

**Two structurally different resolution paths**, chosen by whether the
target position's chunk is currently loaded in the live game world ("live")
or not ("cached/unloaded"):

- **Live:** the tint is resolved by asking the game's own built-in
  per-block tint system for the color at that *exact* world position — a
  single-point sample, with no averaging added by this pipeline itself. Any
  smoothing visible in grass/foliage color in this path comes entirely from
  the game's own biome-blending behavior (a client video setting), not from
  the mod.
- **Cached/unloaded:** the mod performs its own explicit **3×3 neighborhood
  average** — the 9 columns from -1 to +1 on both horizontal axes around the
  target column. Each neighbor's biome is looked up from a cached per-pixel
  biome record (not the live world), with the lookup coordinates clamped to
  the currently-tracked data bounds (so edge columns effectively repeat the
  nearest known column rather than reading out of bounds). A missing/null
  neighbor biome record falls back to a generic default biome. The nine
  samples' red/green/blue channels are summed and divided by 9 (integer
  division, each channel masked to a byte) for the final tint.

**Which per-block color function is used inside that 3×3 average** (cached
path) or as the underlying category (live path, mirrored by the engine
itself):

- Grass-top block, short/tall grass, short/tall fern, reeds, lily pads, the
  low bush block, and the inert "tall flower" slot: the **grass** color
  function (this is the catch-all default for anything in the eligible set
  that isn't more specifically categorized below).
- Oak, jungle, acacia, and dark oak leaves, plus vines: the **generic
  foliage** color function.
- Spruce, birch, and mangrove leaves: **fixed, non-biome-dependent**
  reference colors (one constant per leaf type) — these three leaf types are
  deliberately *not* biome-sampled at all, matching the underlying game's
  own special-casing of those specific leaf textures.
- Leaf litter: a separate **"dry foliage"** color function.
- Water: the **water** color function.
- Redstone wire: the same power-level function used in §2 — technically
  routed through the same 3×3-averaging machinery in the cached/unloaded
  path, but since its output never actually depends on biome, averaging nine
  identical values is a no-op in practice.

The same optional custom-palette data source from §2 has its own parallel
3×3-averaged lookup, consulted preferentially over the built-in resolvers
above when custom palette data is loaded for a given block.

**No per-position noise or jitter is added by the mod itself** in either
path. Any fine-grained, pixel-level color variance you might observe is
either the game's own biome-blend smoothing (live path) or simply the
natural block-to-block variation across a real biome boundary reflected by
the 3×3 average (cached path) — there's no distance weighting and no
Perlin/simplex-style noise contributed by this pipeline.

**Modded blocks:** any block whose registered identifier isn't in the
vanilla namespace is, by default, still probed for a tint via the live
per-position path even though it isn't in the fixed eligible list above — so
a modded block that registers its own tint source with the game is picked
up automatically. One that doesn't simply resolves to "no tint" (not an
error).

## 4. Height/slope shading

Two independent toggles exist — an "absolute height" mode and a "slope"
mode — and can be turned on together. Both act on the final R/G/B channels
of a layer's color (alpha is untouched) via the same lighten/darken step
described at the end of this section. This shading step is skipped
entirely for a layer whose color already equals the current sky/air
placeholder color, is fully transparent, or was flagged as "no real surface"
in §1.

### Absolute-height term

Used whenever slope mode is off, or layered on top of slope mode when both
are on:

```
diff  = thisColumnHeight - referenceHeight
shade = log10(|diff| / 8 + 1) / K        (negated when diff is negative)
```

- `K = 1.8` when this is the *only* active shading mode.
- `K = 3.0` when it's being added on top of an already-computed slope term
  (a deliberately gentler blend in the combined case).
- `referenceHeight` differs by rendering pass: the **live/minimap pass**
  uses the viewer's *current* vertical position, re-evaluated live; the
  **cached/world-map pass** has no "current viewer" concept for an arbitrary
  remote pixel, so it always uses the fixed constant **80** instead. In
  practice this means the persisted full map's height-shading is always
  anchored to a fixed sea-level-ish reference, while the live minimap's
  shading shifts as the viewer's own altitude changes.

### Slope term

Only computed when slope mode is on. Compares this column's height for
whichever layer is being shaded (surface, seafloor, transparent-overlay, or
foliage-overlay each compare against their own respective recorded heights)
against **one fixed diagonal neighbor column** — the column at
`(x - 1, z + 1)` relative to the column being shaded (one less on one
horizontal axis, one more on the other).

- **Live/minimap pass:** always this same fixed neighbor direction, read
  from already-computed per-pixel data when available; at the edge of the
  currently-rendered tile (where that neighbor hasn't been computed yet
  this pass) it instead computes that neighbor's height live, on demand,
  rather than skipping the comparison.
- **Cached/world-map pass:** prefers the same `(x-1, z+1)` neighbor when it
  lies inside the currently-cached tile; if not (the opposite edge of the
  tile), it uses the *opposite* diagonal neighbor `(x+1, z-1)` instead and
  inverts the sign of the height comparison to compensate. If *neither*
  neighboring column is available at all, the slope term is simply treated
  as flat/zero — unlike the live pass, there is no on-demand live
  computation fallback here.

The resulting term is **not proportional to the size of the height
difference** — it is a fixed step:

```
+1/8  if the neighbor is higher
-1/8  if the neighbor is lower
 0    if equal
```

A one-block step and a hundred-block cliff edge produce identical shading.

### Applying the combined shade value

For each of R, G, B independently (`c` = current channel value 0–255):

- If `shade > 0`: `c = c + shade * (255 - c)` (blend toward white).
- If `shade < 0`: `c = c - |shade| * c` (blend toward black).
- If `shade == 0`: unchanged.

There is **no explicit clamp** back into the 0–255 range afterward. Within
normal Overworld-scale height ranges the combined shade value stays within
±1 in practice, so this typically isn't observable, but it is not a
guaranteed invariant of the formula itself at extreme height ranges (e.g.
some modded dimensions).

### Light-based (day/night) shading

A separate, independently-toggleable mechanism, not part of the
height/slope formula above:

- Gated entirely behind a "dynamic lighting" setting. When off, every
  layer's light multiplier is a pure no-op — full brightness, no
  time-of-day effect at all, regardless of actual in-game time or location.
- When on, each layer independently queries the real block-light and
  sky-light values (0–15 each) at its own resolved height, clamped into the
  world's vertical bounds — with one override: lava and magma-block
  surfaces always report a block-light value of **14**, regardless of the
  actual queried value.
- Those two values are combined into a lookup against a 256-entry table (one
  entry per possible block-light/sky-light pair) that reproduces the
  underlying game's own in-world lighting color curve:
  - A nonlinear per-channel brightness response (steeper falloff at low
    light levels): `brightness = level / (4 - 3*level)` for a 0–1 normalized
    light level.
  - A warm/reddish tint that grows with block-light, including a small,
    continuously-updating pseudo-random "torch flicker" term added to the
    block-light channel and decayed by a factor of 0.9 each update.
  - A cooler, blue-shifted tint that grows with sky-light and scales with
    the current time-of-day sky-darkening factor.
  - An ambient-light floor pulled in per current dimension (e.g. nether-like
    dimensions have a nonzero ambient floor even in total darkness).
  - A soft blend of 4% toward a mid-gray reference value (0.75), applied
    twice in the pipeline (once before, once after the gamma curve step
    below) — a "never quite pure black, never quite pure white" softening.
  - A gamma/brightness-setting-driven curve adjustment: blends between the
    raw value and a `1 - (1-x)^4` curve by the current video
    gamma/brightness setting.
- This lookup table is rebuilt whenever the game's gamma setting, sun
  brightness (day/night sun angle), night-vision effect status/duration,
  lightning-flash state, or pause state changes, plus on a periodic forced
  refresh.
- Night-vision forces a strong sky-brightness floor; once its remaining
  duration drops under roughly 10 seconds the strength ramps in via a
  sine-based pulse rather than a hard on/off cutoff, and is full-strength
  otherwise.
- A computed light value of exactly zero makes that entire layer's color
  fully transparent (invisible), not merely dark — this is also the exact
  mechanism that makes the void/no-surface case from §1 disappear visually,
  since it's force-set to a zero light value.
- **Live/minimap pass** recomputes each visible layer's light value fresh
  (subject to the refresh triggers above) against the live game world.
  **Cached/world-map pass** instead stores one packed light index
  (block-light + sky-light×16) per pixel at the moment that area was last
  scanned, and at render time looks that stored index up in the *current*
  lighting table — so a previously-explored, currently-unloaded area on the
  persisted map shows the light *level* recorded when it was last visited,
  but recolored using *today's* current day/night palette, not the palette
  from when it was recorded. There is no separate "how long ago was this
  seen" dimming factor beyond that interaction.

## 5. Water/fluid depth rendering

There is **no depth-count-based formula** — no "N blocks deep → alpha X"
curve, and no per-block-of-depth accumulation of transparency during the
seafloor scan. Depth affects the rendered result only indirectly, through
the mechanisms below.

**Composition** is a single, one-shot standard "over" alpha blend
(`resultAlpha = topAlpha + bottomAlpha*(1-topAlpha)`, RGB weighted
accordingly) of the fully-shaded, fully-lit water/ice surface color placed
on top of a composite built bottom-to-top from: the seafloor color, then any
underwater foliage-overlay (if its height is at or below the surface
height), then any underwater transparent-overlay (if its height is at or
below the surface height). Each of those layers already carries its own
independently-computed biome tint, height/slope shading, and light
multiplier *before* this final composite — the composite step itself
performs no additional depth-aware math.

Because of that: the water layer's own alpha — how much of the seafloor
composite shows through — comes entirely from whatever alpha its sampled
texture/tint produced per §2/§3, a fixed value for a given resource pack and
biome that does **not vary with how many water blocks are stacked above the
floor**. A one-block-deep pond and a forty-block-deep ocean trench get
exactly the same water-layer alpha blended over their own (independently
computed) floor colors.

**The one genuinely depth-correlated darkening** comes from the optional
dynamic-lighting mechanism in §4: real sky-light does attenuate with actual
depth in the underlying game (each block of water above a point reduces the
sky-light value reaching it, the same as ordinary solid blocks would), and
since the seafloor layer's light is queried at the seafloor's own height, a
deep spot legitimately queries a lower sky-light value than a shallow one
and renders darker as a result — but this effect exists **only when dynamic
lighting is turned on**. With it off, depth produces no visible darkening at
all beyond the single fixed-alpha blend described above.

**Ice-specific rule:** when the resolved surface block is ice sitting over a
water column, and dynamic lighting is enabled, the seafloor layer's light
value gets one additional flat multiply *on top of* the normal lighting
lookup — RGB channels multiplied by a constant gray value of:

- **200/255 (~78%)** if the game's smooth-lighting/ambient-occlusion video
  setting is on, or
- **120/255 (~47%)** if that setting is off.

Alpha is untouched by this extra multiply. This approximates ice blocking
more light than open water and is a fixed flat-darkening constant, not a
depth formula.

**Lava:** rendered through the ordinary single opaque-surface path, not the
water/seafloor dual-layer path — the seafloor scan in §1 is only ever
triggered when the resolved surface block is water or ice, never lava. Lava
also carries the one void-fallback exception from §1 (it is never
force-flagged as "no real surface") and the fixed block-light-14 override
from §4.

**Seafloor scan depth:** unbounded except by the world's vertical bounds and
the opacity/leaves stop conditions from §1 — there is no separate
maximum-depth cutoff specific to water beyond that.

## 6. Edge cases

- **Unloaded neighbor chunks (live/minimap pass):** biome lookups for the
  3×3 tint-averaging neighborhood in §3 fall back to a cached per-pixel
  biome record from this pipeline's own previously-captured data (clamped to
  its tracked bounds) rather than forcing the neighbor chunk to load. A
  missing/null cached record falls back to a generic default biome.
- **Unexplored/never-visited area (cached/world-map pass):** a map cell with
  no recorded data at all — detected by its recorded block state being
  exactly air, its recorded light being exactly zero, its recorded height
  being exactly the "unset" sentinel, and no biome recorded — renders as
  fully transparent/blank rather than any placeholder color. This is the
  persisted map's "fog of war" for areas never scanned.
- **Void below the world / nothing under the floor:** see §1's "no real
  surface" fallback — renders fully transparent (light forced to zero), not
  a solid black or colored tile. Ordinary bedrock has no special handling of
  its own; it's simply an opaque block sampled and tinted like any other.
- **Snow-covered grass / snow layers:** a standalone snow-layer block is
  promoted to be the rendered surface itself (§1), not drawn as an overlay
  on the block underneath. A grass block that merely displays a snowy
  texture/model variant because of its biome needs no special-case code —
  it's sampled like any other block since the model already reflects the
  snowy look.
- **Waterlogged blocks:** any block state carrying a non-empty fluid state
  is transparently swapped for a plain representation of that fluid before
  any opacity/color/height decision, at every step of the column scan.
  Practically: a waterlogged stair, slab, sign, or plant renders as plain
  water; its real solid geometry and color are never sampled.
- **Modded blocks with no known/derivable color:** if a block's texture
  can't be resolved to real coordinate data at all, the cached color is the
  near-black, mostly-transparent placeholder from §2 (alpha 27/255, RGB 0)
  rather than any hard error or built-in fallback palette. Modded blocks
  that *do* resolve normal texture data are treated exactly like vanilla
  blocks for sampling, and are opportunistically probed for a biome tint
  (§3) even when not on the fixed "known tintable" list. See also §2's
  caveat about non-biome-mode caching for modded blocks evaluated against an
  unloaded-chunk position.
- **Redstone wire:** deliberately excluded from the "biome" tint
  classification proper — its color is baked directly into the per-exact-
  block-state color cache using the game's own power-level color function,
  so it needs no live per-position resolution and is unaffected by the
  live-vs-cached branching in §3 (aside from being routed through the same
  averaging code as a harmless no-op, per §3).
- A final post-processing step, unrelated to surface/color/tint/shading
  proper, can additionally lay a flat semi-transparent tint over "slime
  chunk" columns and/or draw semi-transparent lines at chunk and region
  boundaries, depending on user settings. These are a straightforward alpha
  composite on top of the fully-resolved pixel color from everything above
  and don't feed back into any of the sampling/tinting/shading math.

## Confidence notes

- The exact real-world trigger for "resolved surface block is lava, but the
  void/no-surface fallback also got set" (§1) was read directly from the
  code as a conditional override, but the precise sequence of column states
  that produces both conditions simultaneously wasn't fully traced end to
  end — treat the override's *effect* (lava is never treated as void) as
  reliable, and the exact triggering scenario as a secondary detail.
- The numeric alpha value baked into a resource pack's water texture itself
  (which determines how strongly the water layer's fixed alpha blend in §5
  shows the seafloor through it) is supplied by the active Minecraft
  resource pack's own image asset at runtime, not by this pipeline's source
  — it was not independently measured here, so §5 describes the *mechanism*
  (a single fixed, non-depth-varying alpha) with certainty but not the
  specific byte value a default/vanilla pack would produce.
- The "modded block gets a near-invisible placeholder in non-biome mode when
  first cached against an unloaded-chunk position" finding in §2/§6 is a
  direct reading of an always-returns-"unavailable" stub function in the
  traced source. It's reported with high confidence as *current source
  behavior*, but it reads like an incomplete/disabled code path (a
  simplification of some earlier, more capable fallback) rather than a
  deliberately designed rule — a reimplementation should feel free to do
  better (e.g. fall back to the block's plain sampled color) without
  reproducing this specific gap.
- Vanilla Minecraft's own further internal biome-color smoothing (the
  client "biome blend radius" video setting) that the live tint path in §3
  inherits automatically was not re-derived here — it's the underlying
  game's behavior, not this pipeline's, and is only mentioned for
  completeness.
- The day/night lighting curve constants in §4 were transcribed faithfully
  from the reference implementation, which itself describes them as a
  reproduction of the base game's own lightmap shader math — high
  confidence on the shape and constants of the formula, but this is
  effectively describing the host game engine's lighting curve as much as
  the mod's own logic.
- Exact default values for which settings/toggles ship on vs. off, and any
  UI copy/labels for the options mentioned throughout (biome tinting,
  dynamic lighting, height map, slope map, water transparency, block
  transparency, slime chunks, chunk grid), were deliberately omitted per the
  clean-room constraint; none of the behavior above depends on their
  specific wording or defaults.

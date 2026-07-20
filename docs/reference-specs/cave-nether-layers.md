# Cave, Nether, and End Layer Rendering

Underground and non-Overworld rendering: cave-mode auto-detection, the Nether's
current-layer / ceiling / manual-Y-slice modes, and the End's void background.

Two independent renderers exist in the reference system and are treated separately below:

- **Live map** — a small always-on-screen minimap, continuously recomputed and centered on
  the player's current position.
- **Persistent/world map** — a larger, disk-cached "explored world" map that the player can
  pan around independently of their live position, backed by on-disk region files.

Where the two renderers diverge, both are described explicitly.

---

## 1. Underground/cave-mode detection

### 1.1 Live map

Detection runs once per recompute cycle (see cadence note below), using only the player's
own column, and produces a small set of boolean flags that are then applied uniformly to
every map pixel (re)drawn during that cycle. It is **not** evaluated independently per map
column — it is a single global decision per cycle, driven by the player's own position.

The dimension is classified into exactly one of three cases:

**Case A — dimension has a physical ceiling** (the generic equivalent of a "roofed" dimension
metadata flag; true for Nether-like dimensions):

1. Sample the block-motion heightmap at the player's column. Call the column "open" if that
   recorded height is at or below the player's current Y.
2. Set a "classic terrain" flag when player Y < 126.
3. Independently, set a "cave / escaped-the-roof" flag when player Y >= 126 **and** the
   column is *not* open **and** cave mode is enabled (see gating below).

Because of the Y < 126 / Y >= 126 split, at most one of the two flags is ever true at once.

**Case B — dimension has no ceiling, but uses "nether-style" lighting** (block light only, no
ambient sky light propagation; this is the classification the reference code appears to have
been written primarily for the End, but the check itself is generic):

1. Compute the same "open" check against the block-motion heightmap.
2. Set a single "cave" flag when the column is *not* open and cave mode is enabled.

**Case C — ordinary sky-lit dimension** (the Overworld and similar):

1. Set a "cave" flag when the sky-light channel (a 0–15 light level tracking ambient light
   from open sky, independent of torches/block light) sampled at the player's position is
   `<= 0`, and cave mode is enabled.

**Gating:** in all three cases, cave/underground detection additionally requires two
independent booleans to both be true: a server-controlled permission flag (servers can force
this off, e.g. to prevent x-ray-style cave viewing) and a client-side user setting (togglable
by hotkey). If either is false, the map always falls back to plain top-down surface
rendering in that dimension, regardless of light or heightmap state.

**Sampled Y:** the "player Y" used throughout is the floor of the camera/eye Y position, not
the feet position.

**Cadence:** the recompute loop runs on a dedicated background thread that is woken roughly
once per in-game tick/frame, so these flags are freshly re-derived from instantaneous state
almost continuously.

**Hysteresis:** no debounce or smoothing was found on the flag computation itself — it is a
direct function of instantaneous light/heightmap state each cycle. A player standing exactly
on a boundary (e.g. a cave mouth at the sky-light cutoff) could in principle see the flag
flip every cycle; no corrective mechanism for that was found. What *does* exist is a related
but distinct debounce that smooths the reference Y fed into the floor-scan algorithm as the
player moves vertically (Section 2) — that affects how quickly the *sampled floor height*
updates, not whether cave mode is currently active. Separately, whenever the flag *does*
change value between cycles, the live map forces one full re-render of its visible tile
buffer (see Section 6) rather than smoothing the transition.

### 1.2 Persistent/world map

Underground-ness here is a coarser, **per-region** decision, made once when a given map
region is first created (first visit, or first load from on-disk cache after a purge), based
purely on dimension metadata — never re-evaluated per column and never tied to the player's
live Y:

```
underground_region =
       (dimension is NOT nether-lit AND dimension has no ambient sky light)   -- covers the End
    OR dimension has a physical ceiling                                       -- covers the Nether, unconditionally
    OR dimension's name matches a small hardcoded allow-list of known
       fully-subterranean modded dimensions
```

Consequences:

- On the persistent map, the **entire Nether** is always rendered with the cave-floor
  algorithm — there is no Y < 126 / Y >= 126 split like the live map has.
- The **entire End** is always rendered with the cave-floor algorithm too, even directly
  above open void or a floating island top, because the decision is per-region, not
  per-column.
- Because this decision is made once per region object's lifetime, it does not change
  mid-session; a region only gets re-classified if its in-memory cache entry is purged and
  recreated (e.g. on a dimension change, Section 6).

---

## 2. Cave-mode column sampling

### 2.1 Core floor-scan algorithm

Both "classic terrain" and "cave" flags (live map), and every underground region column
(persistent map), are rendered through the *same* single routine — a bounded scan for the
nearest solid/lava surface relative to a pivot Y:

```
function findFloor(x, z, pivotY):
    y = pivotY
    block = blockAt(x, y, z)
    if block is non-opaque (does not occlude light) and block is not lava:
        # pivot sits in open space -> scan straight down for the nearest floor
        while y > worldMinY:
            y -= 1
            block = blockAt(x, y, z)
            if block is opaque OR block is lava:
                return y + 1        # top of whatever was hit
        return y                    # reached world bottom, nothing found
    else:
        # pivot sits inside solid/lava material -> scan straight up for an opening
        while y <= pivotY + 10 and y < worldMaxY:
            y += 1
            block = blockAt(x, y, z)
            if block is non-opaque and block is not lava:
                return y
        return NO_FLOOR_FOUND        # nothing open within 10 blocks above pivot
```

- **Live map pivot:** the player's smoothed/debounced Y (see below).
- **Persistent map pivot:** a fixed constant, Y = 80 — not player-relative, since the
  persistent map can be browsed far from the player's current position. The upward-scan
  bound is likewise a fixed constant (Y <= 90), i.e. the same 10-block window measured from
  the fixed pivot instead of the live player Y.

Key numeric/behavioral facts:

- **Downward scan is unbounded** except by the world's minimum Y — a column with a long
  unobstructed shaft scans all the way to the world floor.
- **Upward scan is capped at exactly 10 blocks** above the pivot. If nothing open is found
  in that window, the column has no visible floor at all (sentinel value).
- The scan returns only the **first** (nearest-to-pivot) solid/lava surface. It does not
  attempt to detect, rank, or choose between multiple stacked caverns in the same column —
  if a column has several caves stacked vertically, only the one nearest the pivot Y is ever
  shown. Deeper caverns become visible only once the player (live map) or the fixed pivot
  (persistent map) is close enough to them.
- **Lava stops the scan** exactly like an opaque block, but is explicitly excluded from the
  "unknown surface" fallback treatment described in Section 5, so it renders with its own
  color/glow rather than a flat placeholder.
- When no floor is found, the height is a sentinel "unknown" value. Downstream this is
  replaced by `pivot + 1` and the column is flagged **solid**, which (Section 5) forces the
  rendered pixel to fully transparent rather than a fabricated terrain color.
- **Overhang/foliage overlay:** the block immediately above a found floor is inspected; if
  it is snow, or is non-air/non-lava/non-water, it is drawn as a single overlay layer on top
  of the floor color (the same visual role as grass/leaves overlaying terrain on the surface
  map). Cave-mode columns only compute this one overlay layer plus the floor layer — the
  separate "see-through block" and "ocean floor beneath water" layers used on the surface
  map are not computed while underground.

### 2.2 Pivot-Y refresh debounce (live map only)

The player-Y pivot fed into the floor scan is **not** updated every tick. It refreshes only
when any of the following is true:

- a full re-render is already in progress, or
- the player's Y has moved by at least a threshold since the last refresh
  (**2 blocks** on multi-core hosts, **5 blocks** on single-core hosts), or
- a maximum tick count has elapsed since the last refresh without qualifying movement
  (**300 ticks** multi-core, **3000 ticks** single-core).

Effect: small vertical jitter (e.g. stepping over a one-block ledge) does not immediately
rescan every visible column's floor; the displayed cave floor can lag the player's true
altitude slightly until one of the thresholds above is crossed.

### 2.3 Vertical-distance shading ("height shading")

Applies to any non-"solid"-flagged, non-air-colored column, when the corresponding display
option is enabled — identically for genuine overworld terrain and for cave-mode floors; only
the reference/pivot Y differs (live map: the smoothed player-Y pivot above; persistent map:
the fixed constant 80).

```
diff  = surfaceY - pivotY
scale = log10(abs(diff) / 8 + 1) / 1.8
if diff < 0: scale = -scale

# scale > 0 lightens the color toward white; scale < 0 darkens it toward black.
# Applied per channel (r, g, b):
if scale > 0:  channel += scale * (255 - channel)
else:          channel -= abs(scale) * channel
```

An alternate, mutually exclusive "slope shading" mode instead compares each column's height
to a diagonally adjacent neighbor's height, producing a fixed ±(1/8) shift; if
vertical-distance shading is *also* enabled at the same time, an additional term using the
same `log10(|diff|/8 + 1)` curve (divided by 3 instead of 1.8) is added on top.

---

## 3. Nether handling

- **Avoiding "just the bedrock roof":** the Nether never falls back to a naive top-down
  opaque-block scan the way the Overworld surface does. Every Nether column goes through the
  Section 2 floor-scan instead. The bedrock roof is only ever encountered by the scan's
  *upward* branch (when the pivot itself starts embedded in rock), and that branch is capped
  at 10 blocks — so a solid roof with no gap within 10 blocks of the pivot simply yields "no
  floor found" (rendered transparent, Section 5), never the underside of the roof drawn as
  if it were terrain.
- **Layer selection below the ceiling:** there is no separate "which layer" step distinct
  from the general floor scan — whatever opaque or lava surface is nearest the pivot Y (up or
  down) is shown. This naturally favors terrain, structures, or lava-lake surfaces close to
  the player's/pivot's current altitude and ignores anything further away vertically in the
  same column, including other cave layers above or below the found one.
- **Lava sea handling:** a lava surface stops the downward scan like an opaque block would,
  so a lava ocean's surface becomes "the floor" for any column above it. It is exempted from
  the solid/unknown fallback path (so it keeps a real color rather than flattening to a
  placeholder). Independently, wherever lava or a magma-type block ends up as the rendered
  surface, seafloor, or see-through-layer block **in any dimension**, the lighting step
  (Section 5) hard-overrides its contributed block-light level to **14 out of 15**,
  regardless of the true computed value at that position — this is what produces lava's glow
  on the map even in total darkness.
- **Same algorithm, different trigger:** confirmed — Nether terrain, Nether-above-the-roof,
  Overworld caves, and End caves all funnel through the identical floor-scan routine
  (Section 2). What differs across these cases is only (a) the condition deciding whether the
  routine runs at all in a given cycle (Section 1), and (b) which pivot Y is used (player-Y
  with debounce on the live map; a fixed constant on the persistent map). The Y = 126 split
  inside the has-ceiling branch only decides an internal label ("terrain" vs "cave"); both
  labels drive the same rendering path.

---

## 4. End handling

- The End is detected via Section 1's Case B (no physical ceiling, no ambient sky light,
  non-Nether-style lighting) on the live map — this appears to be the case the reference
  logic was primarily written to handle.
- **Void background:** identical to the Nether's "no floor found" outcome — a column with
  nothing within the scan's bounds resolves to fully transparent output (Section 5), not a
  dedicated void color. Gaps between islands and open void above/below them render as
  blank/see-through map pixels, letting whatever is behind the map layer show through.
- **Island-surface rules (live map):** whether a given End column renders as an ordinary
  top-down surface (showing an island top, with the full foliage/see-through/ocean-floor
  layering used on the Overworld) or as a cave-style floor scan depends on the *player's own*
  position at the moment of the recompute cycle, applied to the whole redraw batch:
  - Player standing in the open (nothing above them — on an island top, or in open void) →
    the whole batch uses the ordinary top-down scan, correctly finding island tops.
  - Player enclosed from above (e.g. underneath a floating island) → the whole batch instead
    uses the pivot-relative floor scan, which shows whatever is near the player's current
    altitude rather than any island top.
- **Persistent/world map:** this player-relative switching does not apply. Per Section 1.2,
  the whole End dimension is permanently flagged underground, so every column — island top or
  void — goes through the fixed-pivot (Y = 80) floor scan. An island near Y = 80 (within the
  10-block upward window) will be picked up by the scan; islands or terrain far outside that
  band, or genuine void, will not.
- No further End-specific rules (arena handling, distinct void gradient, etc.) were found
  beyond the general cave/void mechanics above.

---

## 5. Light/color modulation underground

### 5.1 Per-pixel light value

- If a column is flagged **solid** (Section 2's no-floor-found fallback), its light value is
  forced to the minimum, which multiplies its color down to fully transparent black
  (see "unexplored area" below).
- Otherwise, if dynamic lighting is enabled, the light value combines two independently
  sampled 0–15 channels at the found surface position: a **block-light** level (torches,
  lava, glowstone, etc.) and a **sky-light** level (ambient light reaching that block from
  open sky). If dynamic lighting is disabled, the base terrain color is used unmodified —
  full brightness, no cave-darkness effect at all.
- **Lava/magma override:** if the rendered block is lava or a magma-type block, its
  block-light channel is hard-set to **14** before combining, regardless of the true computed
  value — producing lava's glow on the map even where ambient light is otherwise zero.

### 5.2 Combining block light + sky light into a color

This reproduces the base game engine's own light-to-color lookup (the same curve the game
uses to tint block faces), not anything specific to the mapping logic itself. Described
generically:

```
function combineLight(blockLevel, skyLevel):     # both 0..15
    # non-linear brightness response, applied to each normalized (0..1) input
    curve(level) = level / (4 - 3*level)
    blockStrength = curve(blockLevel/15) * blockFactor   # blockFactor ~1.5, plus small random flicker
    skyStrength   = curve(skyLevel/15)   * skyFactor     # skyFactor derives from time-of-day darkening

    # block light gets a warm tint that shifts toward white as it saturates
    r = blockStrength
    g = blockStrength * ((blockStrength*0.6 + 0.4) * 0.6 + 0.4)
    b = blockStrength * (blockStrength^2 * 0.6 + 0.4)

    # blend toward full brightness by the dimension's minimum ambient-light floor
    r,g,b = mix(r,g,b toward 1.0 by ambientLightFactor)

    # add the (tinted) sky-light contribution on top
    r += skyStrength * skyTintR ; g += skyStrength * skyTintG ; b += skyStrength * skyTintB

    # small fixed blend toward mid-gray both before and after a gamma-style curve
    r,g,b = mix(r,g,b toward 0.75 by 0.04); clamp to 0..1
    r,g,b = mix(r,g,b toward gammaCurve(r,g,b) by userGammaSetting)
    r,g,b = mix(r,g,b toward 0.75 by 0.04); clamp to 0..1
    return (r,g,b)
```

This is precomputed into a small 16×16 lookup table (one entry per block-light/sky-light
pair) whenever a relevant input changes (gamma setting, time-of-day, night-vision effect,
lightning flash, pause state) rather than per pixel per frame; per-pixel work is a table
lookup by `(blockLevel, skyLevel)`, multiplied into the base terrain color.

**Underground consequence:** cave/Nether/End columns typically have sky-light 0, so their
color is driven almost entirely by the block-light term — unlit caves render essentially
black, while torches, lava, and glowing blocks visibly brighten and warm-tint the surrounding
map pixels. Lava's forced block-light-14 override makes it stand out even in total darkness.

### 5.3 Unexplored/unknown area rendering

- A column where the floor scan finds nothing (Section 2) is flagged **solid** and its light
  forced to zero, producing a fully transparent pixel — nothing is drawn, rather than a
  placeholder color or texture.
- This is visually indistinguishable from a column that has simply never been scanned yet,
  since a freshly initialized (never-processed) map cell also defaults to fully transparent.

---

## 6. Mode/state transitions

### 6.1 Toggling surface ↔ cave within the same dimension

- There is no user-facing toggle distinct from the automatic detection in Section 1, other
  than the single global "cave mode enabled" setting, which suppresses *all* automatic cave
  detection everywhere (not just at the player's current position).
- When the automatically derived flag changes value between recompute cycles, the live map
  forces a **full re-render** of its entire visible tile buffer — instead of the cheaper
  incremental "shift and redraw only the newly exposed edge" update normally used while
  panning. Every visible pixel is recomputed under the new mode; there is no cross-fade or
  gradual transition.
- No separate cache is kept per mode for the same column — the live map's tile buffer holds
  one value per column at a time, and a mode switch simply overwrites that column's stored
  height/light/color with freshly computed data for the new mode.

### 6.2 Crossing dimensions

- A dimension change is detected by comparing the client's current world/level object
  reference once per tick; since each dimension is a structurally distinct level instance,
  any Nether/Overworld/End transition (portal, command, respawn) is detected exactly like
  joining a different server or starting a fresh session.
- On that event:
  - The **live map's** entire in-memory tile buffer is discarded and a full re-render is
    scheduled from scratch. There is **no per-dimension in-memory cache** for the live map —
    every dimension is rebuilt from freshly queried world data every time it is (re-)entered,
    even moments after having just left it.
  - The **persistent/world map's** in-memory region cache (the pool of already-loaded,
    already-rendered regions) is likewise purged on every such transition. However, its
    underlying data is also mirrored to on-disk cache files, and those files **are** kept
    separate per dimension — the on-disk path is namespaced by save/world name, then by a
    "sub-world" identifier (if the setup uses one), then by a folder derived from the
    dimension's own display name, with one file per map region beneath that. Re-entering a
    previously explored dimension therefore reloads its previously saved region data from
    disk into the freshly purged in-memory cache, rather than starting blank. This is the
    key practical difference between the two renderers: the live map is always blank on
    dimension entry; the persistent map restores previously explored terrain per dimension.
  - The persistent map's per-region "underground" flag (Section 1.2) is decided fresh at the
    moment each region object is (re)created after such a purge, from the *entered*
    dimension's metadata — no "cave mode" state carries over between dimensions.

### 6.3 Per-dimension persistent state found

- The on-disk region cache directory tree, permanently namespaced per dimension as above.
- A small dimension registry (used for UI purposes such as naming/ordering dimensions in
  waypoint lists), seeded with the vanilla Overworld/Nether/End and extended with any other
  dimension identifiers encountered; this registry is not itself part of the cave-detection
  logic.
- No persistent state was found that remembers "cave mode was active" for a dimension —
  underground status is always re-derived from current conditions (Section 1), never stored
  and restored across a visit.

---

## Confidence notes

- The live map's "essentially every frame" recompute cadence is inferred from its
  background-thread wait/notify wiring being pumped once per in-game tick callback; it was
  not independently measured against a running client.
- The "known fully-subterranean modded dimension" allow-list was observed as a single
  substring check against the dimension's name in the reference source. It's unclear whether
  additional entries exist elsewhere; treat this as "there is precedent for a small hardcoded
  override list for unusual modded dimensions," not as a specific list to reproduce exactly.
- The distinction that Section 1 Case B is "for the End" is inferred from context in the
  reference source (not reproduced here) rather than an explicit dimension-ID check — the
  underlying condition (no ceiling, no sky light, non-Nether-style lighting) is generic and
  could in principle also match certain modded dimensions. A clean-room implementation should
  probably key off the same combination of properties rather than assuming it's End-specific.
- "Cardinal lighting type" (nether-style vs. overworld-style light propagation) is treated
  here as an opaque per-dimension classification roughly equivalent to "does this dimension
  use the Nether's block-light-dominated lighting model." Its own precise definition/source
  was not traced in this pass.
- The light-to-color curve in Section 5.2 reproduces the base game engine's own lightmap
  math, not anything original to the reference mapping mod. It's included because it's
  necessary to reproduce the actual on-screen appearance, but a clean-room implementation
  could reasonably substitute any equivalent brightness/tint curve rather than matching these
  exact constants, since they belong to the base game rather than to the mapping logic.
- Only the live map and the persistent/world map were examined. If the target project has
  additional map surfaces (e.g. a separate biome-only or radar view), they were not covered
  here.
- All Nether/End-specific claims rely on standard vanilla dimension metadata (has-ceiling,
  has-sky-light, cardinal lighting) as read by the reference source's conditionals; behavior
  in unusual modded dimensions with atypical combinations of these flags was not verified
  against a live client.

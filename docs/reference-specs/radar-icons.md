# Entity Radar

Entity classification, radar dot rendering, above/below elevation cues, and
the icon mapping for minimap markers.

## 1. Entity collection

**Source pool.** Each scan draws from the client's standard per-frame renderable-entity
collection for the player's current dimension — the same pool the game itself already
iterates to draw entities in the 3D world. No separate network query or server-side entity
list is used; everything is derived from entities the client already knows about.

**Type filter.** Only entities that are "living" (creatures and players; not item drops,
projectiles, vehicles, etc.) are eligible at all. The local viewing player's own entity is
always excluded from becoming a tracked record.

**Category classification (live, per-instance).** Each eligible entity is classified into
exactly one of three categories, checked in this order:

1. **Player** — the entity is a player-controlled entity.
2. **Hostile** — otherwise, hostile if any of the following hold, checked in order:
   - it matches the game's generic "monster" trait (the same generic marker most vanilla
     hostile mobs share) → always hostile.
   - it is a rabbit-family entity → hostile only if its specific variant is the rare
     "evil"/killer variant; ordinary rabbits are not hostile via this rule.
   - it exposes a generic "remembers who provoked it and stays angry at them" trait (shared
     by several neutral-by-default creature families) → hostile **only if** its currently
     stored anger target's unique ID matches the local viewing player's own ID — i.e. it must
     be angry specifically *at the local player*, not merely angry at some other player or
     entity. If it has no stored anger target at all, this rule does not make it hostile.
   - none of the above matched → not hostile.
3. **Neutral** — anything that is neither a player nor classified hostile above.

There is **no separate "tamed/owned" category** in the live classification — only these three
categories exist. (An unused "is this entity ownable" helper exists elsewhere but is never
invoked by the classifier or anything else; treat it as dead code, not a feature.)

**A second, coarser classifier exists for per-species management** (the UI that lets a user
permanently hide an entire species — see §4). It does **not** use the live rules above. It
instead asks the base game's own static, per-species category and treats the game's built-in
"monster" bucket as hostile and everything else as neutral. This means the two classifiers can
disagree for the same creature: a species the base game does not statically bucket as a
monster (e.g. a wolf) always shows as neutral in the per-species management list, even though
a specific individual of that species currently angry at the local player would be tinted and
grouped as hostile on the live radar itself.

**Range limits.** A tracked entity must satisfy both a horizontal and (optionally) a vertical
bound, expressed in on-screen minimap distance units (world-block distance divided by the
current zoom-scale factor):

- Horizontal: within 32 minimap units of the player. On a round minimap this is a circular
  radius (`dx² + dz² ≤ 32²`); on a square minimap it is a square box (`|dx| ≤ 32 and |dz| ≤ 32`).
  This always matches the visible edge of the minimap itself, so it automatically covers more
  world blocks as the player zooms out.
- Vertical: only enforced while the elevation-dimming option (§3) is enabled. The vertical
  window is 32 units for ordinary entities, doubled to 64 units for one specific flying
  hostile species (the Phantom). When the elevation option is off, there is no vertical cutoff
  at all — entities at any height within the horizontal bound are eligible.

The periodic scan (below) applies these same bounds with a **5-unit outer buffer** added to
both the horizontal and vertical limits, so an entity is captured into the tracked list
slightly before it visually crosses the display boundary. The per-tick refresh (below) then
applies the bounds with **no buffer** every tick to decide moment-to-moment whether to actually
draw an already-tracked entity. This two-tier buffer means an entity hovering right at the
boundary between periodic scans still gets its visibility flipped correctly every tick without
waiting for the next scan, avoiding pop-in/out flicker.

**Additional per-instance exclusions**, applied during the per-tick refresh:
- A crouching player entity is skipped entirely while crouching, if the relevant toggle is on
  (default on).
- An entity the base game's own visibility rules would treat as invisible to the local viewer
  (accounting for potion invisibility, team-glow interactions, etc.) is skipped entirely, if the
  relevant toggle is on (default on).

**Scan frequency.** A periodic full re-scan runs at most once every 16 client ticks (roughly
every 0.8 real-time seconds at the standard 20-tick-per-second rate). Each periodic scan only
re-evaluates **one eighth** of the tracked-entity space at a time: entities are partitioned
into 8 buckets by a stable hash derived from the low 3 bits of each entity's unique ID, and one
bucket is discarded-and-repopulated-from-the-world-entity-pool per scan, cycling through all 8
buckets in turn. A full refresh of every currently-tracked entity therefore takes up to 8 scan
cycles (~128 ticks, ~6.4 seconds) to complete, though a newly appeared entity can be picked up
as soon as its bucket's turn comes around. Independently of this bucketed scan, **every
already-tracked entity has its screen position, bearing, distance, and elevation-dimming value
recomputed every single tick** (see §5) — the bucketing only throttles the cost of discovering
new entities and dropping stale ones, not the smoothness of already-tracked entities.

Whenever any radar-relevant setting changes, the scan timer is force-reset so a fresh scan
fires on the very next tick, rather than waiting out the normal interval.

---

## 2. Icon generation

Two independent rendering modes exist, selected by a single mode setting; several other
options are only meaningful in one mode or the other (noted in §4).

### Full mode: runtime-rendered per-species icons

Icons are **not** a pre-baked sprite sheet in this mode. Each distinct species (plus relevant
runtime variant state, see below) is rendered on demand by reusing the game's own entity model
and texture for that entity, offscreen, and the result is cached and reused thereafter.

**What part of the model is rendered.** Rather than rendering the whole body, a small, fixed
selection heuristic picks which parts of the model to draw, evaluated in this priority order:

1. A short list of model shapes that are rendered in full (their entire model, not just a
   part) — this covers a few small/round creature shapes (cod-like, salmon-like, slime-like,
   magma-cube-like, tropical-fish-like, and one other cube-like creature shape).
2. A specific three-headed boss model → all three head parts.
3. A villager-shaped model (including its zombified variant) → the head part plus its
   hat/overlay layer.
4. Any model exposing a distinct "head parts" grouping (used by horse-like models) → that
   whole grouping.
5. Any model with a plain "head" part → that part; if the same model also has a distinct first
   body segment (spider-like models), the body segment is included alongside the head.
6. Otherwise, a "body" part if present (covers e.g. bee-/ghast-like shapes).
7. Otherwise, a generic "cube" part if present.
8. Otherwise, a first-and-second "segment" pair if present (silverfish-/endermite-like models).
9. Final fallback: the entire model root.

A slime-type entity additionally gets a translucent outer-layer mesh added on top of its base
body mesh. Player-type entities are handled specially: instead of going through model-part
selection, the icon uses the player's actual equipped skin texture directly (the base/body
layer), so a player's icon is a small portrait of their real skin rather than a generic model
render.

**Texture/color inputs.** Up to four texture layers (primary/secondary/tertiary/quaternary)
can be composited per icon, each with an independent flat color multiply. Most species use
only a primary texture at full white multiply. A handful of species use extra layers via a
per-species hook — e.g. an overlay texture for a "wet" or status-variant look, glow-eye
overlays, a colorable base+pattern pair (e.g. a fish-like species where two independent color
choices tint two separate texture layers), or a horse-like species' independently selectable
coat/marking layers.

**Post-processing pipeline** (applied in order, after the offscreen model render):
1. For a few specific model shapes (camel-like, llama-like, a large flying mount-like shape),
   a fixed pixel rectangle within the rendered bitmap is forcibly cleared — this removes a
   saddle/harness/decoration region baked into those particular textures that would otherwise
   look wrong at icon scale.
2. Auto-crop tightly to the bitmap's non-transparent content bounds.
3. Uniform rescale by (a per-species configurable multiplier, default 1.0) divided by (any
   runtime size factor already baked into the render — e.g. a variable adult-size attribute
   some fish-like species have).
4. Pad the image with a fixed 3-pixel margin on every side (room for an outline).
5. If the outline option is enabled, run an "outline fill" pass **twice**: each pass looks at
   every fully-transparent pixel, and if it has a directly-or-diagonally adjacent pixel that is
   more than ~20% opaque, it is filled solid black. Two passes in sequence produce a roughly
   2-pixel-thick black outline around the icon's silhouette. Whether or not outlining is
   enabled, one more non-solid-fill pass always runs afterward, which similarly spreads
   existing edge colors outward by one pixel into transparent neighbors (without forcing them
   black) — this slightly grows anti-aliased edges to reduce seams while the icon sits in the
   shared texture atlas.

> **Deviation (Conflux Map):** icons come from a pre-drawn bundled sheet rather than runtime
> model bakes, and the outline-fill pass runs **once**, not twice — a 1-pixel ring hugging the
> first transparent pixels around the silhouette (user request), baked per sheet cell into a
> tintable mask texture (`IconOutliner` / `EntityIconOutlineTexture`). The contour is also not
> fixed black: each marker samples the composed map color beneath it and flips between black
> and white for contrast (`RadarMarkerRenderer#contourBase`). Player faces keep the plain 1px
> square frame, which for a fully-opaque square crop is already its silhouette outline.

**Distinct cached variants per species.** A species can have more than one cached icon when
its appearance meaningfully differs at runtime. Recognized variant axes include: a discrete
"puff state" (pufferfish-like species), a combined pair of independently selectable colors
packed into one identifier (tropical-fish-like species), a baby-vs-adult flag (applies
generically to any species that can be a baby), and a computed size-scale bucket rounded to
one of roughly 100 discrete steps (for species with continuously variable adult size). Each
distinct combination is rendered and cached independently the first time it is encountered;
later requests for the same combination reuse the cached result.

**Per-species icon override.** A resource pack or server content pack can supply a fully custom
image for a specific species (matched by a per-species file-naming convention), optionally
paired with a small properties file controlling: an icon scale multiplier, a vertical pixel
offset used when layering a headgear icon on top (see below), and a fixed rotation to apply to
the offscreen camera pose before any model-based render (used for species where the override
mechanism still needs a model render, not just for full image overrides). When a custom image
is supplied, it is used as-is (after the same pad/outline treatment as above) instead of a
model-based render.

**Icon sizing.** The offscreen render always happens against a fixed 512×512 render target,
regardless of any nominal "requested size" value passed in by the caller — that value is only
used to distinguish cache entries (so a request at one nominal size doesn't collide with a
cached entry from a different nominal size), not to change render resolution. After the crop/
rescale steps above, the **displayed on-minimap size is the final baked bitmap's pixel
width/height divided by 8** — larger creatures naturally produce visually larger icons than
smaller creatures; there is no forced uniform icon box size.

**Headgear/armor icons.** A separate, very similar offscreen render+cache pipeline exists just
for a worn-headgear overlay, drawn as a second sprite layered on top of the base icon at a
small per-species configurable vertical pixel offset (default: no offset). A couple of species
get bespoke post-processing here too — e.g. a wool-covering-style overlay is tinted to match
that specific individual's own color rather than using a flat white tint.

**Fallback for unknown/modded entities.** There is no generic "unknown creature" placeholder
glyph drawn for a species whose render fails. The model-part fallback chain above (§ steps 1–9)
already produces a reasonable icon for the vast majority of unfamiliar species, since it falls
back all the way to "just render the whole model root" before giving up. Only if the runtime
lookup for that species' renderer/model fails entirely (no usable renderer at all) does icon
generation give up — that species is remembered as failed (so it is not retried every request),
and any request for its icon simply returns nothing. A tracked entity that never obtains an
icon this way is excluded from **both** the icon-draw pass and the name-label pass entirely
(both require a successfully generated icon before an entity is drawn or refreshed at all in
Full mode) — so such an entity is invisible on the radar rather than shown with a placeholder.

### Simple mode: fixed generic markers

Simple mode does not generate any per-species icons at all. Every tracked entity is drawn as
one of two small fixed pre-made sprites: a plain dot/ping marker (always drawn), and an
optional facing-direction arrow overlay sprite (drawn only if the relevant toggle is on),
rotated to the entity's real head-facing direction. Both are tinted a flat color per category
(see §3) rather than reflecting the entity's actual appearance. Because every entity uses the
same two fixed images, there is no "unknown entity" problem in this mode.

---

## 3. Radar rendering on minimap

### Position projection

For each tracked entity, every tick:
1. Compute the flat (X, Z) vector from the entity's current interpolated position to the
   player's position.
2. Raw bearing = angle of that vector (atan2 of the X component over the Z component).
3. Raw distance = length of that vector.
4. Final on-screen bearing = raw bearing + a single shared "map direction" value computed once
   per frame for the whole minimap (not per entity — see below).
5. On-screen distance = raw distance ÷ current zoom-scale factor.

**The shared map-direction value** depends on the minimap's current display mode:
- **Rotating-map mode** (the default: the map view itself spins under a fixed player marker),
  and the view is the small in-game minimap (not the persistent/fullscreen map screen): the
  shared value equals the player's own facing yaw, offset by 180° and wrapped into [0°, 360°).
- **North-locked mode** (rotation option turned off), **or** whenever the persistent/fullscreen
  map screen is open (which always renders contacts north-locked regardless of the rotation
  option): the shared value is a small fixed legacy offset — 0° normally, or 90° if a
  backward-compatibility "old north" display option is enabled (which also rotates the whole
  map background texture by that same 90°, keeping the two consistent with each other).

### Icon/marker orientation

**Full mode:** icons are positioned via translate-to-center → rotate by −bearing → translate
outward by on-screen distance → rotate back by (bearing + an extra per-entity spin value) →
translate back. The extra spin value is 0 for essentially every entity, **except**: if an
entity's display name (with formatting codes stripped) exactly matches one of two specific
classic joke names, it gets an extra 180° spin — rendering that one entity's icon upside-down,
mirroring an equivalent joke in the base game's own player rendering. (This joke also requires,
for player entities specifically, that the player's cloak/cape rendering is currently enabled.)
Because the rotate/counter-rotate pair cancels out except for that spin term, ordinary icons
always render upright on screen no matter how the map itself is currently rotated.

An alternate positioning path exists, toggled by an "icon filtering" option (Full mode only,
default on): instead of the rotate/counter-rotate approach, it computes the same (X, Z) screen
offset directly via trigonometry and **rounds the offset to the nearest whole pixel-equivalent
step** before applying it. This alternate path never applies the joke-name spin (a
joke-named entity renders right-side-up if this path is active), and produces a blockier,
non-subpixel-smoothed icon position compared to the default path.

**Simple mode:** the ping marker uses the same bearing/distance transform as Full mode's
default path (always upright). The separate optional facing-arrow overlay, when enabled,
additionally rotates to the tracked entity's real head-facing yaw, compensated by the same
shared map-direction value — so the arrow always points the entity's true world-facing
direction relative to whatever orientation the map is currently displayed in.

### Above/below indication

There is **no** distinct arrow, icon-shape change, or badge used to show whether a tracked
entity is above or below the player. It is communicated purely through opacity/tint, and only
when the elevation-dimming option (§1, §4) is enabled:

- Every tick, a "closeness" value (0–1) is computed from the entity's absolute vertical
  distance to the player, scaled by the current zoom factor, against the same vertical window
  used for range-culling (32 units, or 64 for the one flying-hostile exception). The value
  decreases linearly toward 0 as vertical distance approaches the window, then is **squared**
  (so it falls off faster once partway to the limit than a straight linear fade would), and is
  floored at 0 beyond the window.
- **If the entity is above the player's position**: that closeness value is used as the icon's
  **alpha** (transparency), while its color multiply stays fully bright/undimmed — the icon
  fades toward fully transparent the higher above the player it is, but never darkens.
- **If the entity is at or below the player's position**: the icon instead stays **fully
  opaque**, but its color multiply is **dimmed toward black** by that same closeness value,
  floored so it never dims below 30% brightness — the icon darkens/grays out the farther below
  the player it is, but never fully vanishes.
- If the elevation option is off, every icon renders fully opaque at full brightness regardless
  of actual height difference — no vertical indication at all.
- The same alpha/dimming multiply is applied identically to the optional headgear overlay
  sprite.

**Simple mode's category tint** is layered independently of the above: hostile markers get an
additional reddish flat-color multiply, neutral markers an additional greenish flat-color
multiply, and player markers get no additional category tint — all combined with the
elevation-based alpha/dimming above.

### Name labels

A label is only ever considered for a tracked entity that had a non-empty display name
assigned at the moment it was first discovered — which happens for every player entity
unconditionally, and for any other entity (any category) that happens to carry a custom name
tag. Entities without either are never labeled, even if the relevant toggle is on.

The label text is the entity's plain display name, or — if a "full names" option is off (its
default state) — a shortened form of the same name. For player entities, the shortened form is
additionally recolored to that player's team color when they have one.

Whether a label is actually drawn is gated by category, independently of the name being
assigned: a "show player names" toggle for player-category entities, a separate "show mob
names" toggle covering every non-player category. Both are Full-mode-only; Simple mode never
draws name labels.

Labels are rendered in a **separate pass that runs only after every icon for the current frame
has already been submitted** — so a label is never occluded by any icon belonging to any
tracked entity, regardless of relative draw order. Label text is drawn at a small independently
configurable font-scale multiplier, horizontally centered and vertically anchored a few pixels
below the icon's center point.

### Icon overlap / z-order

Immediately after each periodic scan finishes repopulating the tracked list, the entire list is
resorted by each entity's world height (ascending), with interpolated X then interpolated Z as
tie-breakers. Both the icon pass and the (separately-run) label pass walk this same sorted
order and nudge each successive entry's render depth slightly closer to the camera than the
previous entry. Net effect: where two tracked entities' icons visually overlap on screen, the
one that is **physically higher up in the world** always renders on top, independent of scan
order or how recently either was discovered.

A ridden/mounted entity whose mount is itself a currently-visible tracked entity gets an
additional small fixed pixel offset applied to its icon's on-screen vertical position (shifted
upward), on top of the height-based depth ordering above — a purely visual nudge so a rider's
icon partially separates from its mount's icon instead of fully overlapping it. The same
vertical-distance ("height window") range check used for culling also grants this entity a
1-unit vertical offset boost for range purposes while mounted this way.

---

## 4. Filtering options

| Toggle (paraphrased) | Default | Scope | Notes |
|---|---|---|---|
| Master radar visibility | On | Both modes | Hides the entire radar overlay when off. |
| Mode: Simple vs Full | Full | — | Selects which rendering behavior (§2/§3) is active. |
| Elevation-based dimming | On | Both modes | See §3 "Above/below indication". |
| Hide invisible-to-viewer entities | On | Both modes | Uses the base game's own invisibility-to-viewer check. |
| Players toggle | On | UI grouping only | Enables/disables several dependent player-related UI toggles (names, headgear, sneak-hiding) in the settings screen. Based on the actual scan-inclusion logic, this toggle does **not** itself gate whether player entities are scanned/shown — see Confidence notes. |
| Player name labels | On | Full mode only | Depends on the players toggle being on. |
| Player headgear icons | On | Full mode only | Depends on the players toggle being on. |
| Hide sneaking players | On | Both modes | A crouching player is skipped entirely while crouching. |
| Facing-arrow overlay | On | Simple mode only | Draws the small rotated arrow sprite on top of each ping. |
| Show hostile category | On | Both modes | Category is entirely excluded from scanning when off, not just hidden visually. |
| Show neutral category | Off | Both modes | Same as above. |
| Legacy category quick-cycle | — | Both modes | Steps through 4 combined states in order: neither → hostile-only → neutral-only → both → neither, by flipping the two category toggles together. |
| Mob name labels | On | Full mode only | Covers hostile + neutral categories collectively. |
| Mob headgear icons | On | Full mode only | Covers hostile + neutral categories collectively. |
| Full entity names | Off | Full mode only | When off, names are shortened (see §3); applies to both player and mob labels via their own dependency chains. |
| Per-species management list | — | Both modes | See below. |
| Icon filtering | On | Full mode only | See §3 "Icon/marker orientation" for the exact behavioral difference. |
| Icon outline | On | Full mode only | Controls whether newly-baked icons get the black-outline post-process (§2). Already-cached icons are not retroactively re-baked when this is toggled — only a cache reset (e.g. a resource reload) re-bakes them without the outline. |
| Compatibility/CPU rendering | Off | Full mode only | Forces the slower CPU-side icon-baking backend instead of the default GPU-accelerated one. Toggling this resets the icon cache. |

**Per-species management list.** A separate dialog lets a user permanently show/hide individual
species by identifier, independent of (and layered on top of) the broader category toggles
above — a species hidden this way is excluded from scanning even while its whole category is
otherwise enabled. The dialog itself is pre-filtered to only list species belonging to whichever
categories are currently enabled by the category toggles (players are never listed here at
all — this dialog only ever covers non-player species), and offers a live text search matching
against either the species' localized display name or its raw registry identifier.

**Server-controlled restrictions.** A connected server can optionally push a small settings
payload to the client containing three independent allow/forbid booleans: whether the radar
feature is allowed at all, whether non-player mobs may be tracked, and whether players may be
tracked. Each defaults to *allowed* when the client receives no such payload (e.g. singleplayer,
or an unconfigured/vanilla server) — so behavior is unrestricted unless a server explicitly
opts in to restricting it. When the server forbids something, the corresponding user-facing
toggle(s) become inert/greyed-out in the settings UI, **and** the restriction is separately
re-enforced at the actual scan-inclusion check — so a locally-cached "on" preference cannot
bypass a server-side "forbid" by, for example, editing a local settings file, since the
server's flags and the local toggles are combined with a logical AND at scan time, not merely
presented as disabled in the UI.

---

## 5. Update/interpolation

- **Position interpolation**: every tick, for every entity already in the tracked list
  (independent of the bucketed periodic scan described in §1), the displayed horizontal (X, Z)
  position is linearly blended between that entity's position as of the end of the previous
  tick and its position as of the current tick, using the current render frame's tick-fraction
  (the same partial-tick blending the base game's own entity renderer uses for smooth motion).
  This is what makes icons glide continuously between discrete tick updates instead of visibly
  snapping twenty times a second.
- **Vertical position** is not interpolated the same way — it uses the entity's raw current-tick
  height directly, plus the small mounted-entity offset described in §3 when applicable.
- **Bearing, distance, and the elevation-closeness value are all recomputed every tick** from
  the (possibly interpolated) position — they are not cached from the periodic scan.
- **Despawn/leave handling**: nothing is removed from the tracked list on a per-frame basis
  purely because an entity left range or became otherwise ineligible. The per-tick refresh
  simply flips that entity's display state to hidden (skipped by both the icon pass and the
  label pass) as soon as it fails the strict, no-buffer range/visibility checks (§1). Actual
  removal from the tracked list only happens as a side effect of that entity's assigned
  bucket's *next* periodic scan (each scan unconditionally discards its entire bucket before
  repopulating it from the current world-entity pool) — so a genuinely despawned or
  disconnected entity can remain an inert, already-hidden list entry for up to one full 8-bucket
  cycle (~6.4 seconds worst case) before being purged. This has no visible effect since the
  entity is already hidden the moment it fails the per-tick check; the lag is purely internal
  bookkeeping.
- Whenever any radar-relevant setting changes, the next periodic scan is forced to run
  immediately (see §1) rather than waiting for the normal interval, so category/species-filter
  changes are reflected within roughly one tick rather than up to 16.

---

## 6. Performance behaviors

- **No hard cap** on the number of simultaneously tracked or rendered entities was found — the
  tracked-entity list is an unbounded growable collection (given only a small initial-capacity
  hint for allocation efficiency, not a limit).
- **Distance culling**: as described in §1 — roughly a 32-minimap-unit horizontal
  radius/box (scaled by zoom) plus an optional vertical window, both re-checked every tick for
  already-tracked entities, and re-evaluated (with a small outer buffer) once per periodic scan
  for entity discovery.
- **Amortized discovery cost**: the periodic scan only re-evaluates one eighth of the
  tracked-entity space per cycle (round-robin over 8 buckets, §1), rather than re-scanning the
  entire world-entity pool against every filter every cycle.
- **Icon caching**: in Full mode, an icon is only baked once per unique combination of species
  + relevant runtime variant traits (baby/adult, discrete state, color choice(s), size bucket)
  + requested nominal size + outline-on/off, then stored in a shared GPU texture atlas and
  reused by every entity sharing that identity thereafter. Steady-state rendering does no
  per-entity offscreen work — only an atlas-sprite lookup.
- **Asynchronous baking**: the pixel-level post-processing for a newly-baked icon (crop,
  rescale, pad, outline) runs on a background thread pool rather than the render thread. The
  finished bitmap is only merged into the live texture atlas on a subsequent render tick, and
  multiple bakes that complete around the same time are batched into a single atlas
  rebuild/upload rather than one rebuild per icon.
- **Two baking backends** (GPU-accelerated and CPU-side) exist; a user or forced
  compatibility/server override can select the CPU path (e.g. for environments where the
  GPU-accelerated path is unusable), and switching between them triggers a full icon-cache
  reset (every icon is considered stale and will be re-baked on next request).
  A world/server (re)join and any resource-pack reload also both trigger a full icon-cache
  reset.
- **Invisible/sneaking/culled entities are skipped, not merely dimmed**: an entity excluded by
  the invisibility toggle, the sneaking-player toggle, or the range checks does no icon/label
  rendering work at all for that tick (it is not drawn at 0 opacity — it is simply skipped in
  both render passes).
- **No explicit separate "spectator entity" check** was found in the radar logic itself. The
  entity pool scanned is the game's own standard renderable-entity collection (§1) — the same
  set the game itself would iterate to draw entities in the 3D world — so any exclusion of
  spectating players happens at that underlying level rather than via radar-specific logic.

---

## Confidence notes

- All numeric constants and thresholds in this document (32/64/5-unit ranges, 16-tick scan
  interval, 8-way bucketing, 512×512 bake resolution, ÷8 icon-size-to-minimap-unit ratio,
  3-pixel pad, 2 outline passes, 50%-ish alpha threshold for outline sampling, 30% brightness
  floor, 180°/90°/0° rotation offsets, ~100-step size bucket) were read directly from the
  reference source, not estimated or guessed.
- The **player-visibility toggle discrepancy** (§4: the "players" toggle appears in the settings
  UI and gates several dependent UI toggles, but the actual per-entity scan-inclusion check does
  not consult it — only the server-controlled player-permission flag and category logic do) was
  independently verified by reading both the settings-UI wiring and the scan-inclusion logic
  directly; it reads as a genuine behavioral gap in the reference implementation rather than
  intentional design. A clean-room implementation should decide deliberately whether to
  replicate this gap or make the toggle actually functional — it is flagged here rather than
  silently "fixed" or silently reproduced.
- The exact behavioral *meaning* of the "icon filtering" toggle (§2/§3: rotate/counter-rotate
  positioning with a joke-name spin exception, vs. direct trig with pixel-rounding and no joke
  exception) is derived from tracing both code paths, not from an explanatory comment — the
  option's own label does not fully self-document this distinction. The description here is a
  faithful behavioral account of what each path does, independent of what the option is called.
- The "unknown/modded entity" fallback story (§2) is a documented absence: no generic
  placeholder-icon asset or drawing path was found anywhere in the reference implementation. The
  fallback that *does* exist is the progressive model-part-guessing chain, which in practice
  produces a usable icon for almost any species; only a hard renderer/model lookup failure
  produces "no icon," which in turn produces "not drawn" rather than a placeholder glyph.
- The reference clone reflects a modern rendering-pipeline era of the mod (state-object-based
  entity rendering, a submit/collector-based render pipeline, GPU/CPU baking backend split,
  sealed-type-style category pattern matching). Older or legacy releases of similar mods may
  behave differently in ways not captured here; this document should be treated as describing
  one specific, fairly recent implementation, not the entire historical design space of
  Minecraft minimap radars in general.
- Two related systems referenced only in passing — the persistent/fullscreen map's own
  always-north-locked contact orientation (§3), and the "old north" legacy 90° compatibility
  offset (§3) — were confirmed from the map-direction computation but were not traced through
  every consumer of the map's rotation state; their interaction with every other minimap display
  option (e.g. simultaneous square-map mode) was not exhaustively re-verified beyond the specific
  formulas quoted.

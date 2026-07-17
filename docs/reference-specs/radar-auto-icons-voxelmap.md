# Radar Auto Mob-Icon Pipeline — Behavior Specification

This document specifies, at implementation depth, how a Minecraft minimap mod's entity-radar
feature turns a tracked entity into an icon: the fallback chain between icon sources, the
offscreen-rendering mechanics that bake a mob's real in-game model into a flat sprite, the pixel
post-processing applied to that render, and the caching/threading/atlas plumbing around it. It
contains no source code and no copied identifiers (identifiers appear only in "Source anchors").
A companion document, `radar-icons.md`, already specifies the user-observable behavior of the
whole radar feature (scanning, classification, on-screen positioning, filtering options); this
document goes deeper into the icon-baking pipeline's internals.

---

## 1. Pipeline shape and resolution order

Every icon request (mob-body icon or headgear/armor icon — two structurally parallel pipelines,
§9) resolves through the same order, each step only consulted if the previous one failed:

1. **Custom image override** (§3) — a resource-pack-supplied replacement image for that exact
   species, if one exists on disk.
2. **Cached bake** — an existing (or in-flight) result for the same cache key (§8) is reused; no
   rendering happens.
3. **Automatic model render** (§4–§7) — the entity's real model/texture(s) are rendered offscreen
   and baked into a new sprite, cached for step 2 to find next time.

If step 3 also fails (§12), the request returns nothing; the caller treats a null icon as "draw
nothing this frame." There is no generic placeholder glyph anywhere in this pipeline.

**On the reported "hardcoded UV-crop" tier.** No such tier — a hand-authored table of fixed pixel
crops into each species' skin texture — was found in the source read for this document. Every
icon that isn't a full custom-image override goes through the *same* automatic-render path
(§4–§7). What reads from a distance as "hardcoded per-species cropping" is three narrower special
cases living inside that one path, none of which touch raw texture UVs: a short list of model
classes rendered whole instead of part-selected (§4 priority 1), two named-part special cases for
two specific shapes (§4 priorities 2–3), and a hardcoded post-render pixel-rectangle *clear* (not
crop) on three species' finished bitmaps (§7 step 1). An implementer should fold these into the
one automatic pipeline as small conditionals rather than building a separate crop subsystem. (See
Confidence notes for how far this negative finding should be trusted.)

---

## 2. When an icon is requested, and on what thread

A mob-body icon is requested once per contact: the first tick after that contact is newly tracked
with no icon yet assigned. Once satisfied, the contact keeps the sprite reference and never
re-requests it. A headgear/armor icon is requested independently, under the same "only while
still null" rule, gated by that contact's category having the headgear toggle on.

This request — including, on a cache miss, the *entire* offscreen render (§5) — runs
synchronously on the client's main render/tick thread, inline with the per-tick walk of newly
tracked contacts. It is not dispatched to a worker thread. Only the pixel post-processing after
the render (§7) is backgrounded (§11); the render itself and its GPU→CPU pixel readback both run
on the render thread. Because new-contact discovery is itself throttled/bucketed (companion
document §1), icon bakes are naturally rate-limited by however many new species/variant
combinations a given discovery cycle turns up — there is no separate "bake at most N icons per
frame" cap.

A nominal "requested size" integer is threaded through every call but never changes render
resolution (§5) — it only participates in the cache key so a future differently-sized request
wouldn't collide with an existing entry. Every traced call site passes the same constant, so this
part of the key is inert today.

---

## 3. Tier 1 — custom image override

A resource/content pack can supply a full replacement image per species, at a fixed path built
from that species' registry id, with an optional sibling small text config. Recognized keys:

| Key (paraphrased) | Meaning | Default |
|---|---|---|
| scale | Multiplier applied to the override/baked image before padding | 1.0 |
| offset | Vertical pixel offset when layering this species' headgear icon onto its body icon | 0 |
| rotation | `{axis: degrees, ...}` applied to the offscreen camera pose before any model render | none |

`rotation` is read and applied unconditionally per species (not only when a custom image is
absent), so it stays live for species that still fall through to the model render.

If the override resource exists, it's loaded, color-normalized, and run through the shared
pad+outline post-process (§7 steps 4–5) — but *not* crop/rescale, since an authored override is
assumed pre-sized — then cached under species id + outline-enabled flag. If the resource is
absent, the lookup correctly falls through to tier 2 every call: this call site re-checks "does
the cached entry actually have image data," unlike tier 2's cache lookup (see the bug noted in
§12).

---

## 4. Tier 2 — automatic model render: part selection

Rather than the whole body, a fixed heuristic selects which part(s) of the entity's already-posed
model to draw. The model is reset to its resting pose first, and each selected part's own local
rotation is zeroed, so icons always show a neutral, un-animated pose. Priority order, first match
wins:

1. **Whole-model list** — a short hardcoded list of model classes (cod-, salmon-, slime-,
   magma-cube-, tropical-fish-like in both body-shape variants, plus one other cube-shaped
   hostile-creature model) matched by class → entire model root used.
2. **Three-headed boss model** — class match → all three named head parts.
3. **Villager-shaped model** (incl. zombified) — class match → named "head" part plus its named
   "hat"/overlay child.
4. **"head_parts" grouping** (horse-like) — generic scan of every top-level part for a child
   literally named `head_parts` → that whole grouping.
5. **Plain "head" part** — generic scan for a child named `head`; if the same top-level part also
   has a child named `body0` (spider-shaped), that segment is included alongside the head.
6. **"body" part** — generic scan for a child named `body` (bee-/ghast-shaped).
7. **"cube" part** — generic scan for a child named `cube`.
8. **Paired "segment" parts** — generic scan for `segment0`; if found, `segment1` is included too
   (silverfish-/endermite-shaped).
9. **Fallback** — the entire model root, unconditionally.

A slime-shaped model additionally always gets its translucent outer "jelly" layer mesh appended,
sourced from the game's own slime layer object.

**How selection is actually implemented.** Not Java reflection over declared fields, and not a
lookup by field name in source code. Every model exposes a generic **named-child API** at
runtime: "does this part have a child registered under exactly this string," and "give me that
child." The heuristic walks each model's top-level parts probing for `head_parts`, `head`,
`body0`, `body`, `cube`, `segment0`/`segment1` by string key against each model's own child map
(populated when the model was built) — never by screen position or bounding box. Only priorities
that need to disambiguate models colliding on these generic names (the whole-model list, the
three-headed boss, villagers) fall back to a concrete Java-class check instead. **This matters for
porting to 1.17.1 — see §13**: that generic named-child lookup is itself a fairly modern addition;
older model parts typically expose named children only as plain object fields with no shared
by-name API.

**Player-controlled contacts** still go through part selection (a humanoid model resolves to its
`head` part via priority 5) but skip the normal texture-selection step (§6/§10): instead of the
game's standard per-frame skin resolution, the pipeline uses the player's own configured skin
image directly as the sole texture layer. The icon is the player's real skin painted onto a small
rendered head, not a flat thumbnail crop.

---

## 5. Offscreen render mechanics

**Render target.** One dedicated, reused offscreen target: **512×512**, RGBA, cleared to fully
transparent (and depth-cleared) before each bake, with its **own dedicated depth buffer**
separate from the main scene's. Every species bakes at this same resolution regardless of actual
size; only how much of the canvas ends up non-transparent differs, which drives the
crop-then-÷8 sizing rule from the companion document.

**Camera/projection.** Orthographic (no perspective foreshortening). The primary (GPU) backend
uses an orthographic frustum spanning roughly ±256 units in X/Y (X mirrored, un-mirrored on
readback), depth range generous enough (~1000–21000 units) to avoid clipping, plus a pose that
translates the model back along the view axis and scales it ×64 per model-unit. The secondary
(CPU) backend reconstructs an equivalent framing manually: it maps each cube's raw model-space
geometry (in sixteenths of a block, as usual for this model format) by scaling/centering into the
512×512 canvas, tuned to match the GPU path's effective scale.

**Camera pose override.** A per-species fixed rotation from §3's config, if present, is applied
to the camera pose first; otherwise every species shares the same fixed framing.

**Lighting.** Two fixed directional light vectors loaded into the standard entity-lighting-uniform
format, combined with fully-bright per-vertex light coordinates and no overlay tint. Every icon
therefore renders at natural, undimmed texture colors — no directional shading, no
block/sky-light darkening, no dependency on real-world time of day.

**Blend/cull.** Alpha-blended (translucent) with a small alpha-cutout threshold. Cull is off for
mob bodies, on for the headgear/armor pipeline (§9).

**Two backends.** The **GPU backend** does a real hardware render pass, then a synchronous
GPU→CPU readback (explicitly asserted on the render thread) that walks all 512×512 pixels in a
plain loop — a real, synchronous cost paid once per newly-discovered species/variant (§11). The
**CPU backend** software-rasterizes every selected part's cube polygons directly: back-face culls
by polygon normal when enabled, sorts polygons back-to-front by depth (painter's algorithm, no
depth buffer), and rasterizes with barycentric-interpolated UVs, compositing each texel with a
standard "over" operator (opaque texels overwrite; partial-alpha texels blend). It touches no GPU
state, at a real per-bake speed cost, and exists as a compatibility fallback (§11). Both backends
implement the same alpha-compositing math, so output should be visually equivalent modulo
rasterization-level edge/antialiasing differences.

**GPU-path mirroring.** Because the GPU ortho box mirrors X, the raw readback is horizontally
flipped; it's unconditionally flipped back before post-processing — an internal detail with no
visible effect.

---

## 6. Texture layer compositing

Up to **four** texture layers — primary/secondary/tertiary/quaternary — each with its own flat
RGBA color multiply (default fully-opaque white on every layer for most species; see §10 for
exceptions). Both backends composite the same way: the *entire* selected mesh (§4) is redrawn once
per non-null layer, in primary→secondary→tertiary→quaternary order, each pass binding that
layer's texture/color and alpha-blending onto the same canvas atop previous passes. This is a
whole-mesh multi-pass composite, not per-face/per-UV overlay — it only reads correctly because the
non-primary layers used in practice (markings, patterns, glow-eyes, badges, status overlays) are
themselves mostly-transparent images.

---

## 7. Pixel post-processing pipeline

Runs in this order after the render (and readback) completes, entirely on a **background
thread**, not the render thread (§11):

1. **Species-specific pixel-rectangle clear.** For a camel-like, a llama-like, and a large
   flying-mount-like model, a full-width horizontal band is cleared to transparent from a fixed
   row to the canvas bottom: row 192 (camel-like), row 248 (llama-like), row 352
   (flying-mount-like). Removes a saddle/harness decoration baked at a fixed screen location by
   the fixed camera framing.
2. **Auto-crop** — tight bounding box over every nonzero-alpha pixel (true AABB over all four
   edges independently, not a row/column skip tolerant of interior gaps).
3. **Uniform rescale** — by (species scale multiplier from §3, default 1.0) ÷ (a per-entity
   "unique size" factor, wired up for exactly one species family — an adult-size trait some
   fish-like species carry — defaulting to 1.0/no-op for everything else).
4. **Pad to a bordered square** — new square canvas of side `max(cropped w, h) + 6` px (a fixed
   3px margin on every side, sized off the longer cropped dimension), content centered — a
   non-square silhouette ends up with asymmetric empty space on its shorter axis.
5. **Outline/edge-bleed fill.** If outline is enabled, two "solid" passes run first: any
   fully-transparent pixel with an 8-neighbor (4 orthogonal + 4 diagonal) whose alpha exceeds
   ~20% (50/255) is filled opaque black — two passes ≈ a 2px black silhouette outline.
   **Regardless of the outline toggle**, one more pass always runs with the same 8-neighbor test,
   but instead of black it copies the neighbor's **RGB only, leaving alpha at zero**. This does
   not visibly grow the silhouette; it prevents later bilinear filtering (scaling on the minimap,
   or atlas-neighbor bleed) from sampling toward a dark fringe at transparent edges — a standard
   atlas color-bleed fix, not a cosmetic effect.

Mob-body icons always get full solid-black outline passes. The headgear/armor pipeline (§9) reuses
this exact routine but with one extra behavior: within a centered square region (sized from a
per-kind constant), qualifying pixels are *erased* to transparent instead of painted black, so
only the outer perimeter gets outlined — avoiding spurious outlines on interior silhouette gaps
(e.g. a helmet's visor slit).

---

## 8. Caching, cache keys, and the texture atlas

**Mob-body key**: species type + a packed integer "variant identifier" (§10) + nominal requested
size (§2, inert in practice) + outline-enabled flag + the resolved texture Identifier for each of
the four layers. Plain field-by-field equality, not a similarity heuristic.

**Headgear/armor key** (§9) is separate: worn item (or a sentinel for "no item," the wool-overlay
case) + resolved texture + a small "kind" tag (armor-asset / block-model / skull) + nominal size +
outline flag — **deliberately excluding the wearer's species.** Two different species wearing the
same helmet share one cached sprite.

**Two-phase registration.** A request first checks for an existing entry under the computed key
(finished or still baking) and returns it immediately if present — this is how multiple contacts
of one species share a single in-flight bake. Only a true miss registers a new empty placeholder
and kicks off a bake. When background post-processing (§7) finishes, the bitmap is queued, not
applied immediately.

**Atlas packing.** All sprites for this feature share one dynamically-sized atlas, packed with
this engine's own general-purpose rectangle bin-packer (the same kind used for the game's
block/item atlases) — sprites keep their natural cropped size rather than a uniform grid cell; the
atlas grows (up to the GPU's max texture size) and re-uploads as needed. Multiple bakes finishing
around the same time are folded into one re-stitch/upload.

**Invalidation.** Directly confirmed full-reset triggers: a resource-pack reload, and toggling the
GPU/CPU backend (checked every tick). A world/server (re)join is plausibly also a trigger per the
companion document, but wasn't independently re-confirmed in this pass. No per-icon/per-species
invalidation exists short of a full reset — an icon baked once is reused for the rest of the
session.

---

## 9. Headgear/armor overlay — a parallel sub-pipeline

A worn head-slot item gets its own icon through a structurally identical, independently-keyed
pipeline (§8), drawn as a second sprite on top of the base icon at a small configurable vertical
offset (§3). Three kinds, in priority order:

1. **Wearable armor/equipment** — renders a dedicated copy of the game's humanoid *outer-layer*
   head part (built with the same cube-inflation as the visible armor mesh, so it's the armor's
   own silhouette), textured with that equipment's resolved texture. Cull is enabled here (unlike
   mob bodies).
2. **A placeable block held in the head slot** — renders that block's actual block model (baked
   quads via the same per-direction quad list the game itself uses), rotated 180° and scaled to
   ~5/8 size. Because block-model quad selection can depend on a random seed for
   multi-variant blocks, a re-bake could in principle pick a different visual variant — a minor,
   low-impact non-determinism.
3. **A mob-head/skull item** — renders the game's skull block model (~1.19× scale) with a small
   hardcoded table for vanilla skull-type textures, but **does not** resolve a player-head item's
   actual profile skin texture — explicitly left unhandled (a `TODO` in the traced source), so a
   player-head item falls through with no texture resolved.

Post-processing after the shared trim/pad/outline routine differs for equipment: a helmet's
cropped image is additionally re-squared into a plain width×width canvas (using cropped *width*
for both dimensions, content anchored top-left rather than centered) before padding.

**Sheep wool overlay** renders the game's real wool fur-layer geometry (its head part) for any
non-baby, non-sheared sheep, textured with the plain wool texture. **Directly observed and worth
flagging:** this handler's cache key does not vary by the sheep's dye color, and the layer's color
multiply is hardcoded to opaque white — no per-individual tint is applied anywhere in this path.
Every sheep, regardless of actual wool color, appears to share one cached, undyed-looking overlay
in this reference version. This reads as an oversight rather than intended design; a clean-room
implementation should decide deliberately whether to apply the entity's real dye color instead of
silently reproducing this gap.

---

## 10. Species variants and equipment-driven texture selection

Primary-texture selection is always delegated to the game's own standard per-frame texture
resolution for that entity's renderer, so ordinary per-instance switches the base game already
handles (profession-neutral villager skin, wolf coat, cat breed, zombie-villager profession, etc.)
are inherited for free. Only a short list of species get *additional* bespoke layers:

| Species (paraphrased) | Extra layer(s) | Selection basis |
|---|---|---|
| "Bogged" skeleton variant | Secondary: fixed status overlay | Always applied |
| "Drowned" zombie variant | Secondary: fixed outer-layer overlay | Always applied |
| Enderman | Secondary: fixed glow-eyes texture | Always applied |
| Horse | Secondary: one of several fixed coat-markings overlays, or none | From the entity's markings trait |
| Ender dragon | Primary force-overridden to one fixed texture | Always applied |
| Villager / zombie villager | Secondary: biome-type overlay (separate baby/adult sets); tertiary: profession badge (adult only) | From the entity's biome-type and profession traits |
| Tropical fish | Secondary: one of twelve fixed pattern textures; primary + secondary each get their own color multiply | Pattern from the entity's pattern trait; colors from its independent base/pattern-color traits |

**Runtime "variant identifier" packing** (the integer in the mob-body cache key, §8): a discrete
"puff state" field for one species, *or* — mutually exclusive per species — a packed pair of two
independent 4-bit color choices for another species; unconditionally, one "is baby" bit; and a
10-bit field holding `round(unique-size-scale × 10)` clamped to 0–100 — the per-instance size
factor (§7 step 3) quantized to roughly 100 steps of 0.1 granularity. Species without a specific
hook contribute 0 to all of these except the baby bit, which applies generically to any species
capable of being a baby.

---

## 11. Threading and performance characteristics

- **Render-thread cost per newly-baked icon:** the full offscreen render (§5), and for the GPU
  backend its synchronous readback, run inline on the render thread. Several new species/variants
  discovered in one tick (new area, or a debug pre-bake-everything mode) all bake within that
  frame — a possible visible hitch.
- **Background-thread cost:** all of §7 (clear/crop/rescale/pad/outline).
- **Deferred render-thread cost:** the finished bitmap is queued and applied on a later tick,
  batching simultaneous finishes into one atlas re-stitch.
- **No explicit per-frame bake cap** — amortization comes from how few new icons are typically
  needed per tick (§2, §8), not from an explicit limiter.
- **Two backends, one active**, switchable by preference or forced compatibility override; the
  CPU path exists for environments where the GPU path misbehaves (e.g. shader-pack interactions).
  Switching triggers a full cache reset (§8).
- **State isolation:** a dedicated depth buffer separate from the main scene avoids one class of
  corruption; the GPU backend explicitly saves/restores the projection matrix and model-view stack
  around its render rather than assuming a clean starting state.

---

## 12. Failure modes

- **Missing custom-image resource (§3):** falls through to the automatic render cheaply and
  correctly every call; no caching hazard.
- **Model/renderer lookup fails entirely** (the entity's renderer isn't one of the two families
  this pipeline can pull a posed model from): a real bug — the empty placeholder sprite for that
  cache key is registered *before* the failure is detected, and the cache-hit check on later
  requests doesn't verify the sprite actually has data, only that an entry exists. Result: the
  species gets stuck as a permanently-empty, zero-size sprite that's non-null (so the caller
  doesn't skip drawing it) but never retried or replaced. **Don't reproduce this** — verify cache
  entries have data before treating them as hits, or memoize failures explicitly (as the
  debug/preview path already does, below).
- **Exception in the live per-contact bake:** no try/catch found around this call; a misbehaving
  modded renderer can throw uncaught here.
- **Exception in the debug/preview bake path** (pre-baking/previewing arbitrary registered entity
  types, e.g. for a species-list UI): wrapped in try/catch, logs a warning, memoizes permanent
  failure so it's never retried — more defensive than the live path; worth applying uniformly.
- **Atlas overflow:** a sprite that finishes baking but can't be packed (atlas already at the
  GPU's max texture size) is silently replaced with a shared 1×1 transparent sentinel — an icon
  can vanish purely from atlas pressure, with only a log line surfaced.
- **Unresolvable texture Identifier for a layer:** that layer's draw pass is simply skipped; no
  fallback texture or color.
- **Player-head skull item (§9):** unhandled — falls through with no texture resolved, most likely
  rendering that headgear icon blank rather than crashing.

---

## 13. Version notes — adapting to 1.17.1

The reference source targets a substantially newer client than 1.17.1: an "entity render state"
object model, a submit/collector frame-assembly pipeline, and a low-level GPU command-buffer/
pipeline abstraction (explicit buffers, command encoders, render passes, pipeline objects) that
did not exist in 1.17.1 at all. None of the *behavior* above depends on this, but the mechanics in
§5, §8, and part of §4 need real re-engineering:

- **No state-object/submit pipeline in 1.17.1.** Entities render by calling the renderer directly
  against a live entity with a pose-stack/buffer-source/light argument list — no immutable "render
  state" snapshot to build ahead of time. An offscreen bake must drive the renderer's normal entry
  point directly (or replicate its model-posing manually).
- **No GPU command-buffer abstraction.** 1.17.1 rendering is effectively immediate-mode: vertex
  buffer builders plus direct low-level state calls against a fixed set of built-in render types,
  not custom pipeline objects. A 512×512 offscreen target must be a manually managed render-target
  object (create/bind/clear/draw with the closest built-in entity render type/unbind/read pixels);
  §5's "custom pipeline with named blend/cull config" has no direct equivalent.
- **Model-part child lookup by name isn't guaranteed.** As flagged in §4, the generic "child named
  X" API is itself a newer addition; 1.17.1's model classes typically expose named children as
  plain object fields with no shared by-name lookup. Porting §4 realistically means either a small
  per-vanilla-model-class table hardcoding which field is "the head," "the body," etc.
  (recommended — predictable, maintainable), or genuine Java reflection over declared fields as a
  generic fallback for unknown/modded model classes.
- **Direct static registry references**, not a unified registry-access helper, for anything doing
  a generic entity-type lookup (e.g. §12's "iterate every registered entity type" debug path).
- **Player-skin lookup differs in shape** (a modern multi-component skin record vs. 1.17.1's
  simpler single texture-location accessor) but is conceptually equivalent — §4's player special
  case ports directly at the behavioral level.
- **Texture-atlas packing carries over conceptually** — 1.17.1 already has its own generic
  rectangle-packing atlas machinery for block/item atlases; §8's "reuse the engine's atlas packer"
  strategy is directly portable, only names differ.
- **Matrix-stack handling already matches** — that rewrite predates 1.17.1, so §5's pose-transform
  math ports essentially as-is.

---

## Confidence notes

- The **absence of a separate UV-crop tier** (§1) is a negative finding, high-confidence for the
  exact snapshot read (a single-commit, already-squashed checkout with no accessible older
  history). It's plausible an *older* release of this mod family — one actually contemporaneous
  with Minecraft 1.17.1, older than what this checkout targets (§13) — used a different,
  hand-authored crop-based system later replaced by the automatic pipeline described here; that
  can't be confirmed or ruled out from what's available.
- The **edge-bleed pass leaving alpha at zero** (§7 step 5) is a high-confidence reading,
  cross-checked against two independent (image-format-specific) implementations of the same
  routine confirmed logically identical — offered as a refinement of, not a contradiction to, the
  companion document's higher-level paraphrase of the same behavior.
- The **sheep-wool missing-dye-tint** (§9) and **empty-sprite-on-failed-lookup caching bug** (§12)
  are both direct readings of traced code, flagged explicitly as likely defects rather than
  intentional design specifically so a clean-room implementation can choose deliberately whether
  to reproduce them.
- The **world/server-join reset trigger** (§8) was not independently re-confirmed against this
  pipeline's own source in this pass; it's carried over from the companion document as
  plausible-but-unverified here.
- The **CPU/GPU backend parity claim** (§5) rests on matching compositing math (both implement the
  same "over" operator); the two backends' rasterization was not pixel-diffed and could still
  differ in fine antialiasing/edge detail.

---

## Source anchors

For traceability only — not part of the normative behavior description above.

- `Radar` — per-contact icon request trigger (`initContact`), render-thread tick hook.
- `EntityMapImageManager` — request/cache orchestration for both pipelines; custom-image lookup;
  model-part-selection heuristic (`getPartToRender`); variant-identifier packing
  (`getMobIdentifier`); post-render clear/crop/rescale/pad/outline sequencing
  (`postProcessRenderedMobImage`).
- `AbstractEntityRenderer`, `EntityGPURenderer`, `EntityCPURenderer` — the two offscreen-render
  backends; camera/projection/lighting setup; layer compositing; GPU pixel readback.
- `EntityVariantData`, `EntityVariantDataFactory`, `DefaultEntityVariantDataFactory`, and the
  per-species factories (`HorseVariantDataFactory`, `VillagerVariantDataFactory`,
  `TropicalFishVariantDataFactory`, `EnderDragonVarintDataFactory`, plus inline
  `DefaultEntityVariantDataFactory` registrations for the bogged/drowned/enderman overlays) —
  §10's table.
- `AbstractArmorHandler`, `DefaultArmorHandler`, `SheepOverlayHandler`, `EntityArmorData`,
  `EntityArmorDataFactory` — §9's sub-pipeline.
- `ImageUtils` (`trim`, `pad`, `scaleImage`, `fillOutline`, `flipHorizontal`) — §7's exact pixel
  post-processing routines.
- `ColorUtils` (`colorMultiplier`, `colorAdder`) — §5/§6's alpha-compositing and color-multiply
  math.
- `TextureAtlas`, `Sprite` — §8's two-phase registration and atlas-packing behavior.
- `GLUtils` (`readTextureContentsToBufferedImage`) — §5's synchronous render-thread pixel
  readback.
- `VoxelMapRenderTarget`, `VoxelMapPipelines`, `VoxelMapCachedOrthoProjectionMatrixBuffer` — §5's
  dedicated offscreen target, render-pipeline (blend/cull) definitions, and cached orthographic
  projection setup.

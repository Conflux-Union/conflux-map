# Radar Auto Mob-Icon Pipeline — Behavior Specification (Xaero's Minimap Reference)

This document specifies, at implementation depth, how a second Minecraft minimap mod — Xaero's
Minimap — turns a tracked entity into a radar icon. It covers the same problem that
`radar-auto-icons-voxelmap.md` already specifies against a different reference implementation
(VoxelMap), but this mod solves it through an almost entirely different mechanism: instead of a
per-species part-selection heuristic feeding a dedicated CPU/GPU offscreen renderer, this
implementation **instruments the game's own real entity-rendering call** for one frame, records
exactly what it drew, and replays a filtered subset of that recording into a small offscreen
target. The two documents are independent — this one does not assume the companion has been read
— but §9 and the Confidence notes call out where the two mods converge or diverge.

It contains no source code and no copied identifiers; identifiers appear only in "Source anchors".

---

## 1. Overview: five collaborating pieces

1. **A per-frame dispatcher/cache** that turns a tracked entity into a cache key, returns a cached
   icon on a hit, and on a miss decides whether a bake may run *this frame at all* (§8).
2. **A one-shot render tracer**: two engine-level instrumentation points, active only while the
   pipeline deliberately re-invokes the entity's real renderer once, off to the side, with its
   pose forced to a neutral rest state. Everything the real renderer would have drawn is recorded,
   not drawn.
3. **A structural part-selection heuristic** ("head replay") that walks the recording and decides
   which recorded model parts to re-emit, driven by the *class* of the entity's model object plus
   a fixed priority order — not a per-species art-direction table.
4. **An FBO-based baker**: a fixed-size supersampled offscreen render, a GPU-only outline
   technique (no CPU pixel scanning anywhere in this pipeline), hardware mipmap generation, and a
   packed texture atlas.
5. **An optional per-entity-type JSON layer** that can override pieces of steps 2–4, or bypass them
   entirely for a plain image or a fallback dot — but most vanilla species ship with no override
   and rely entirely on steps 2–4.

This is read from an early-2024 build targeting Minecraft 1.17.1/Fabric. Baking is fully
synchronous on the render thread with a one-bake-per-frame throttle (§8) — there is no background
thread pool, no async job queue, and no content auto-crop step, which are the most consequential
departures from the modernized architecture this document was asked to verify against (full list
in §9).

---

## 2. Cache keying and lifecycle

### 2.1 Key composition

A cache key is a single string built from three parts, concatenated in order:

1. **Variant-state string** — §2.2.
2. **Entity type** — the entity's registry identifier (e.g. `minecraft:zombie`), not its Java
   class. Two entity types are always different cache entries even if they share an implementation
   class.
3. **Worn-headgear/armor item**, *only* for non-player living entities — the registry identifier of
   whatever item occupies that entity's head equipment slot, **except** horse-family entities,
   which substitute their chest-equipment-slot item instead (horse armor lives there). An empty
   slot contributes nothing. Players are explicitly excluded from this component even though they
   are living entities.

There is no separate "baby" flag hardcoded into the key structure — a baby/adult distinction only
exists to the extent a species' variant-string builder (§2.2) chooses to encode it, and the shipped
builder does not do so generically.

### 2.2 Variant-state string

An entity type may register a static callback (§6) that appends its own state to a `StringBuilder`;
if none is registered (the common case), a built-in default builder runs instead. The default:

1. Looks up the entity's current main texture path and appends it verbatim. If this throws (a
   badly-behaved renderer, typically from another mod), the variant string is left empty for that
   frame — logged once, not fatal.
2. For a short, hardcoded list of vanilla renderer families, appends extra fields after the texture
   path, separated by `%`:

   | Species family | Extra fields appended after the texture path |
   |---|---|
   | Horse | marking overlay texture path, or `null` |
   | Villager / zombie villager | is-baby, biome/region type, profession, profession level |
   | Cat / Wolf | is-tamed |
   | Iron golem | crack/damage-stage enum name |
   | Llama / trader llama | is-trader-llama, carpet colour or `null` |
   | Pig / strider | is-saddled |
   | Tropical fish | packed variant integer (body shape + both dye colours) |

   Every other entity type's variant string is just its texture path, which already
   differentiates most palette-swap variants (biome skins, most "recolor via alternate PNG"
   species) for free.
3. For a JSON-defined entity type, the finished string is looked up in that type's variant table
   (§6) to pick an icon *type* (model / dot / plain image) and, for the model type, which
   model-config override applies. A type with no JSON definition skips this lookup and always uses
   the model type with the default config.

### 2.3 Lifecycle and invalidation

| Trigger | Effect |
|---|---|
| Texture-atlas (resource pack / texture reload) event | Full reset: cached icons dropped, atlas GL textures deleted, **and** every per-entity-type JSON definition reloaded. |
| Icon-scale setting crossing the below-1.0 / at-or-above-1.0 boundary (global or per-category) | Cache-only reset (definitions kept) — the display scale is baked directly into the render camera (§5.2), so an icon baked at the old scale is simply wrong at the new one. |
| Manual "reset radar icons" action, or editing per-species category settings | Same cache-only reset. |
| Offscreen-rendering capability check fails at runtime (defensive fallback, e.g. GPU/driver stops supporting the framebuffer setup mid-session) | Full framebuffer teardown, which also forces the cache/atlas reset. |

**Not** a trigger, verified by reading the reset call sites rather than assumed: a world/server
(re)join by itself, or a dimension change. Nothing ties an icon's validity to the current dimension
or session — an implementer should scope invalidation to "things that can change what an icon
should look like" (resource packs, JSON definitions, baked-in scale), not session boundaries.

Both "permanently failed" and "intentionally a dot" results (§7) are written into the same cache
map as real icons and are just as durable — only the triggers above clear them.

---

## 3. Render tracing

### 3.1 What triggers a trace

Once per requested bake, the pipeline calls the entity's real renderer exactly once, off-screen,
with fixed zero-yaw / full-tick-delta / maximum-brightness arguments so lighting/interpolation
don't introduce variance. A global "currently tracing" flag is set for that one call and cleared
immediately after; two engine-level instrumentation points — one at a model's top-level draw-call
entry, one at an individual named model-part's lowest-level geometry-emit entry — only record while
that flag is set. Outside a trace both are no-ops, so this has no cost on normal in-world
rendering.

**Pose neutralization.** Immediately before the traced call, the entity's live animation state is
saved and forced to a neutral rest pose: body/head yaw, pitch, limb-swing distance, hand-swing
progress, and body rotation are zeroed, and its age/tick counter is forced to a small fixed
non-zero value (10 ticks — past any "just spawned" special-casing, not large enough to trigger any
age-based visual change). Saved values are restored immediately after, regardless of whether the
call threw. This guarantees every baked icon reflects the same canonical standing/facing-forward
pose independent of what the entity was doing in-world at bake time.

### 3.2 What gets recorded

The top-level hook fires once per distinct model *draw call* observed, filtered immediately by a
**class-identity** check: only draw calls whose model is an instance of the same Java class as the
entity's own primary body model are kept. A pass using an unrelated model class (a held item, a
particle effect) is invisible to the recorder — but a **second model object of the same class**
used for a different purpose is not excluded, and is recorded as its own independent pass (this
matters for armor, §4.3).

For each kept pass, the recorder captures: the model object (for replay), the bound texture, a full
copy of the active render layer's composed state (texture-binding, transparency/blend, depth-test,
colour-write-mask, face-cull, and shader phases, each copied verbatim) — this is what lets a
translucent or specially-blended pass (a glow effect, a see-through overlay) replay with its
original blending intact without the pipeline needing to know any species' blend mode in advance —
and a flat RGBA tint multiplier.

The lower-level hook fires once per individual named part that actually emits geometry *within* the
currently active pass, recording that part's own tint. A part present in the model's structure but
currently hidden (an empty armor slot, a toggled-off cosmetic layer) never fires this hook and is
invisible to everything downstream — visibility is entirely inferred from "did this part draw
anything," never a separately-inspected flag.

**Nothing-observed fallback.** If a pass was recorded but the lower-level hook never fired for any
part during it (an instrumentation gap on a non-standard pipeline), every part of that pass is
treated as visible rather than none — a blank icon is judged a worse failure than an
occasionally-too-inclusive one. This only engages if *nothing at all* was observed across every
recorded pass for the whole bake.

### 3.3 Multiple passes of the identical model class

Vanilla renderers frequently draw the same body twice — a base pass plus an overlay (glow effect,
tinted second coat) — reusing the same model class, sometimes the same object. When a second pass
matching the primary body's class arrives:

- If its visible-part set is **identical** (by object identity) to an already-replayed pass of the
  same class, it's treated as a pure recolour/reblend: the already-selected geometry is redrawn
  through the new pass's texture/render-layer state, without re-running part selection. This is how
  eye-glow overlays and similar single-species cosmetic layers end up on the icon automatically,
  with no per-species table describing them.
- If the visible-part set differs, or the pass uses a different model object, it's queued as an
  independent extra layer with its own full part-selection pass (§4) — the mechanism that
  incidentally reproduces worn armor on humanoid-shaped mobs (§4.3).

---

## 4. Part selection ("head replay") and replay mechanics

### 4.1 Priority order

Given the recorded primary-body pass, the pipeline picks a *model root*, then decides which parts
under it to replay, in this order (first match wins; "full render" means every part reachable
under the root, not just one):

0. **Custom root override** (JSON) — a chain of field lookups can redefine the root. Absent that, a
   short hardcoded list of "flat part-list" shapes (squid, ghast, slime, phantom, strider, magma
   cube) substitutes their bare part tree as the root; every other shape uses the model object
   as-is.
1. **Auto-promotion of a trivial head-parts wrapper**, only with no JSON override: if the root
   exposes a "head parts" grouping method (shared by four-legged/animal models) that yields exactly
   one part with no geometry of its own (a pure wrapper), that wrapper is promoted to root and a
   full render is forced. This is what makes horse-family models — whose "head parts" grouping is a
   single wrapper bundling head+ears+mane — render their whole head-and-neck cluster instead of an
   empty node.
2. **Animal-family models**: if specifically the humanoid/biped family, render head + a
   hat/head-overlay part together unconditionally (§4.3); in all cases call the model's own "head
   parts" method and render every part returned; if this species is configured (JSON flag or a
   hardcoded shape list) for a full render, additionally render every part from its "body parts"
   method.
3. **Flat-part-list models** (promoted root from step 0, or the same base family reached directly):
   look up a child literally named `head`; if found, render it, and on a full render additionally
   render every direct child of the root.
4. **Composite multi-part models** not covered above, on a full render: call the model's declared
   "all segments" method and render every part returned.
5. **Everything else** (residual fallback): if the model implements a legacy single-head accessor,
   render that part; otherwise (or additionally, absent a JSON `modelPartsFields` override) consult
   a small hardcoded per-species field table (§4.2); finally run a second reflective field search —
   the JSON's explicit field list if configured, else (on a full render) every declared
   part/array/collection/map-of-parts field anywhere in the class hierarchy unfiltered, else a
   second small hardcoded "secondary fields" table (§4.2).

Whether a full-body render is requested at all is itself per-model: a JSON override can force it
either way; absent one, it defaults **true** for a fixed hardcoded shape list where "just the head"
makes little sense — observed to include the flat-part-list family (step 0), a
humanoid-glow-effect model, a large mount shape, a bee shape, most fish shapes (cod, salmon, all
three pufferfish sizes), dolphin, guardian, endermite/silverfish, and wither — and **false**
(head-only) for everything else, the ordinary case.

### 4.2 The hardcoded per-species field table

For the residual fallback (step 5), a fixed table hardcodes a head-equivalent field for roughly
nineteen species whose shape doesn't structurally advertise a head: bat, blaze, spider, creeper,
llama, parrot, rabbit, ravager, iron golem, snow golem, ender dragon, shulker, and axolotl are
matched by a literal field name (`head`, with a numeric fallback identifier tried if that literal
name isn't present — a defensive dual-lookup for cross-mapping-revision robustness, not two
different fields); slime, magma cube, squid, ghast, strider, and phantom are matched by a *named
child part* looked up by string key (`cube`, `inside_cube`, or `body`, depending on species). A
short secondary table adds left/right-ear and nose parts for rabbit specifically, rendered
alongside its head — no other species has a secondary-fields entry.

### 4.3 Humanoid head + hat, and the emergent armor case

The humanoid/biped family — zombies, skeletons, piglins, players, and similar shapes — is the one
case where step 2 renders *two* parts unconditionally: head, and a "hat"/head-overlay part (the
vanilla cosmetic head-layer convention; for a player this is the hair-over-hat layer).

Because this family is shared by so many species, and because vanilla's armor-layer renderer
reuses the **identical model class** as the body renderer (just a second object with different
geometry plugged in), a worn helmet or armor set on a humanoid-shaped mob can end up baked directly
into the icon as a side effect of the ordinary multi-pass mechanism (§3.3) — with **no dedicated
armor/headgear code path** anywhere. This is exactly why the worn-headgear cache-key component
(§2.1) exists: without it, an armored and unarmored instance of the same species would collide on
one cache entry. Whether armor actually shows up this way depends on whether that mob's
armor-layer model shares a class with its body model — confirmed structurally from the code, not
verified against every armored mob in a live game.

**Players are not special-cased.** No "render the skin as a flat portrait" shortcut exists
anywhere. A player has no JSON definition, is a living entity, and its model is the same
humanoid/biped family as any other biped mob — so it goes through exactly the path above, head +
hat, using whatever texture the real renderer binds (the player's actual skin). The portrait-like
result is what a biped head with a player skin naturally produces, not a player-specific mechanism.

### 4.4 Replaying one part

For each part chosen by §4.1, in order: (1) skip if already replayed earlier in the same bake
(de-duplication across the priority chain); (2) skip if it was never observed firing the low-level
hook and the pass isn't in the nothing-observed-fallback state; (3) skip if it (and everything
nested under it) has no geometry at all; (4) **center** it — an anchor point is computed from the
root-selected main part's own pivot, offset by the midpoint of that part's single largest cuboid by
volume if it has any, and the part being drawn has its pivot temporarily re-expressed relative to
that anchor (restored after), so the replay is always centered on the "main part" — usually the
head — regardless of where it sits in the full body's coordinate space; (5) **neutralize
rotation** — unless a JSON override disables it (default enabled), the part's own local rotation
angles are temporarily zeroed before drawing and restored after, independent of the whole-pose
neutralization in §3.1; (6) emit the part's geometry through the reconstructed render-layer
state/texture from its owning pass, tinted with its recorded colour multiplier, at fixed maximum
light. A part that genuinely emits no vertices doesn't count toward "this bake produced something."

### 4.5 The dead "extra custom layer" hook

The code contains a small class family for injecting an arbitrary extra layer onto an icon after
part selection — an eyes-glow, colourable-pattern, or similar per-species addition, structurally
ready to use. In the analyzed build, the lookup that would supply a per-species instance
unconditionally returns nothing for every species. It is present but entirely unwired — zero
species use it. Any equivalent variation comes for free from the generic multi-pass replay in §3.3
wherever a species' real renderer already implements it that way.

---

## 5. Icon creation

### 5.1 Render targets

Two offscreen framebuffers back every bake, both fixed at **512×512 pixels** regardless of the
final icon size: a **model-render target** (depth attachment present but depth testing disabled
for the whole draw, making it functionally vestigial; nearest-filtered, no mipmaps) where the
traced replay (§3–§4) or a plain-image quad draws, and a **compose/outline target** (no depth, up
to 4 mipmap levels down to 64×64, nearest-mipmap-nearest minification) where the outline pass
(§5.4) and final downsample happen.

The **final icon is always exactly 64×64 pixels** — also the atlas cell size (§5.5). A "requested
size" parameter passed by the caller only distinguishes cache entries at different display scales;
it never changes render resolution.

### 5.2 Camera and pose

The model-render target uses an orthographic projection over a logical 64×64-unit canvas (near
−1, far 500) mapped across the full 512×512 viewport — an **8× supersample per axis**. The camera
is positioned by translating to the canvas center at a fixed depth, then in order: a per-species
pixel offset from JSON, a uniform scale of 32 (× the per-species base-scale multiplier, default
1.0, × the requested display scale only when below 1.0 — scales at or above 1.0 don't enlarge the
bake, only how the finished icon is later drawn on the map), the JSON's Y/X/Z rotation in that
order, a small hardcoded per-species tweak (§5.3), and finally the model's own (already
pose-neutralized) live transform hierarchy.

There is **no content-cropping step anywhere** — the full 64×64 canvas is always used as-is; a
species' apparent size is controlled entirely by this camera/scale math, never by measuring and
trimming rendered content after the fact.

### 5.3 Hardcoded per-species camera tweaks

About eighteen vanilla species get an extra hand-tuned scale/rotation on top of §5.2, evidently to
frame reasonably within the fixed canvas without a generic auto-framing step:

| Adjustment | Species |
|---|---|
| Rotate 90° (vertical axis), scale ×0.5 | Cod, salmon |
| Rotate 90° (vertical axis) only | Tropical fish |
| Scale ×0.5 | Bat, guardian, squid, ghast, ravager, strider, ender dragon, llama |
| Rotate 65° (horizontal axis), scale ×0.7 | Horse |
| Scale ×0.7 | Dolphin, goat, panda |
| Rotate 45° (horizontal axis), scale ×0.5 | Hoglin |
| Scale ×0.35 | Wither |
| Scale ×0.3, rotate 90° (horizontal axis) | Phantom |

Separately, the slime model's own live "squish" animation value is temporarily zeroed for the bake
and restored after, so its icon isn't caught mid-squash.

### 5.4 Outline generation — a GPU compositing technique, not a pixel scan

With the outline option on, the black border is produced **without ever reading pixels back to the
CPU**:

1. The already-rendered content is drawn into the compose target **eight times**, once at each of
   the eight one-(logical-)pixel offsets around its final position (every combination of {−1,0,+1}
   per axis except the center) — one logical unit here equals 8 physical pixels, i.e. one final
   icon pixel.
2. Each draw uses a shader outputting the source texel's *own* alpha tinted fully black, and
   **discards** the texel wherever that alpha is at or below a **5% threshold**.
3. Blending is ordinary "over" for colour but pure **addition** for alpha (destination alpha
   accumulates across all eight passes rather than blending). Because each pass is shifted by one
   final-pixel step, a pixel just outside the silhouette in the unshifted render picks up alpha
   from one or more shifted copies near the edge, while pixels well outside every copy accumulate
   nothing. Net effect: a solid black ring exactly one final-pixel wide hugging the silhouette,
   with no image ever downloaded from the GPU to be inspected.
4. The real full-colour content is drawn once more directly on top, blending disabled entirely (an
   opaque overwrite, same 5% discard threshold), so genuine model pixels replace the ring wherever
   they exist and only the true border stays black.

With the outline option off, this pass is skipped and the raw supersampled render carries forward
unmodified.

### 5.5 Downsample, mipmaps, and atlas packing

Hardware mipmap generation runs on the 512×512 compose target *before* the final blit into the
atlas, so the 8× reduction to 64×64 is a properly filtered box-downsample (sampled exactly at the
matching mip level), not a naive nearest-neighbor shrink. The result is copied into the atlas's
next free cell via one more textured-quad draw (with an optional horizontal flip, used only by one
legacy plain-image variant format, §6).

Atlas textures are square, sized to the smaller of the driver's max texture dimension or 1024
pixels, rounded down to a multiple of 64 — normally 1024, a 16×16 grid of 256 cells. Cells are
handed out strictly in allocation order, never individually reused or evicted; a full atlas
allocates a new one. Atlas textures use bilinear filtering (unlike the nearest-filtered bake
targets), letting one baked 64×64 sprite scale smoothly across zoom levels without re-baking.
Atlases are only ever torn down as a complete set (§2.3), never partially.

### 5.6 Plain-image and dot variants

A JSON definition can route a variant straight to a fixed image instead of a model render. No
entity tracing happens; the image is drawn with a single textured quad into the *same*
model-render target used for a model bake, then goes through the identical
downsample→outline→mipmap→atlas-pack pipeline (§5.4–§5.5) — a structural guarantee, not a
convention, that hand-drawn overrides and rendered icons look visually consistent. One of the two
plain-image forms is flipped horizontally at the final blit for backward compatibility; the newer
form is not.

A "dot" variant skips this section entirely — resolved once, cached permanently, never touching the
render pipeline again until an explicit cache reset.

---

## 6. Per-entity-type JSON configuration

### 6.1 Location and load order

One JSON resource per entity type, at a fixed resource path keyed by registry namespace and path.
Every currently-registered entity type is probed on (re)load; a missing resource is silently
skipped — that type falls through to every default in §2–§5. Reload happens on every
resource-pack/texture-reload event (§2.3); a single definition's load failure is logged and only
that definition dropped.

Most shipped vanilla definition files declare **no behavioral override at all** — they exist to
document (via comments) that species' variant-string format for pack authors, with an empty
override list. Only a small minority (observed: axolotl) carry a real override, needed because
that species' model isn't reachable by any generic path in §4.1 without help. The generic
heuristics are expected to suffice for nearly all species, vanilla or modded, unaided.

### 6.2 Top-level schema

| Field | Type | Meaning |
|---|---|---|
| `variantIdBuilderMethod` | string, optional | Fully-qualified static method `(StringBuilder, EntityRenderer, Entity) -> void` appending variant state (§2.2) directly. Called every frame for every visible instance of that type — documented as needing to be cheap. A thrown exception permanently disables it for the session (logged once), falling back to the next option. |
| `variantIdMethod` | string, optional | Legacy alternative: static `(EntityRenderer, Entity) -> String` returning the variant string directly, tried only if the field above is absent or failed. |
| `variants` | map string→string | Maps a variant-state string (or `default`) to an icon-type directive (§6.3). |
| `modelConfigs` | array of model-config objects | Referenced by index from a `model:<index>` variant entry; unreferenced entries are inert. |

### 6.3 `variants` value grammar

| Value | Meaning |
|---|---|
| `model` / `model:<index>` | Model-render pipeline (§3–§5) with the referenced config, or the default config if unindexed/unresolved. |
| `dot` | Always the fallback dot marker; never renders (§5.6). |
| `sprite:<file>` | Legacy plain-image form, flipped, no outline. |
| `normal_sprite:<file>` | Plain-image form, not flipped, no outline. |
| `outlined_sprite:<file>` | Plain-image form, not flipped, outlined like a model render. |

`<file>` resolves in a fixed resource-pack sprite directory; a recommended (unenforced) 64×64 with
generous transparent padding is documented, consistent with the no-autocrop behavior of §5.2.

### 6.4 Model-config object schema

| Field | Type / default | Meaning |
|---|---|---|
| `baseScale` | float, 1.0 | Extra uniform scale, §5.2. |
| `rotationX/Y/Z` | float, 0 | Extra rotation, applied Y then X then Z, §5.2. |
| `offsetX/Y` | float, 0 | Extra camera-space pixel offset, §5.2. |
| `modelPartsRotationReset` | bool, true | Whether each part's local rotation is neutralized, §4.4 step 5. |
| `renderingFullModel` | nullable bool, null (auto) | Forces/suppresses the full-body decision from §4.1. |
| `modelMainPartFieldAliases` | field-path list, optional | Overrides which field(s) establish the "main part" anchor first, ahead of §4.1's heuristics. |
| `modelPartsFields` | field-path list, optional | Replaces §4.1 step 5's residual field search entirely. |
| `modelRootPath` | list of field-path lists, optional | A chained field-lookup that redefines the model root before §4.1 runs. |
| `layersAllowed` | bool, true | Whether any pass beyond the primary body (§3.3) is drawn on this icon. |

**Field-path syntax**: `<fully-qualified-declaring-class>;<fieldName>`, optionally suffixed with
`[<key1>,<key2>,...]` to select element(s) out of an array/`Collection`/`Map`-valued field (numeric
index, or string/numeric map key; multiple comma-separated keys select multiple elements). Fields
are searched from the model's runtime class up through superclasses, stopping at the generic model
base classes — a subclass's own field is always found before a shared base class's.

### 6.5 Modded entities

An entity type with no matching JSON resource — the default for essentially every modded living
entity — falls straight through to the model-render pipeline, default config, default
variant-string builder (§2.2), and the full generic part-selection priority order (§4.1) including
its hardcoded-species table (which simply won't match an unrecognized shape, leaving the residual
reflective search as the last word). There is no dedicated "unknown/modded entity" placeholder —
the same progressive fallback handling unusual vanilla shapes *is* the mod-support story, down to
§7's failure state.

---

## 7. Fallback chain

| Condition | Result | Cached? |
|---|---|---|
| Renderer exposes no body model at all (only two renderer families are recognized as model-bearing: the generic living-entity renderer, and the ender-dragon renderer) | Permanent failure sentinel | Yes — until a full reset (§2.3) |
| The one real traced render call throws | Recording discarded; falls back to a single synthetic pass built from just the entity's texture lookup — "whole model root, no filtering, no extra layers" instead of aborting | That fallback pass's result is cached normally |
| A texture lookup inside the trace throws | Logged; that pass is skipped | N/A — other passes proceed |
| Part selection (§4.1) finds nothing even though geometry was observed | Permanent failure sentinel | Yes |
| Atlas GL-texture allocation fails mid-bake | Caught and logged; result left at whatever it was before the failed step — indistinguishable from the row above | Yes — a transient GPU failure caches exactly as durably as a genuine unsupported species (§10) |
| JSON routes this variant to `dot` | Fallback dot, intentionally, cheaply | Yes, without ever entering the render pipeline |
| No icon obtained this frame (miss, and this frame's one-bake budget already spent) | Drawn as a fallback dot for this frame only | No — retried next frame |

A permanently-failed entity draws as a fallback dot indefinitely; an independent "show name when
icon fails" option can surface it more visibly even without an icon.

---

## 8. Threading and amortization

Baking is entirely render-thread and synchronous — no background thread, worker pool, or async
queue exists anywhere in this pipeline. Throughput is instead amortized by limiting work per frame:
a single "may bake this frame" flag is armed once per radar draw pass; while iterating that frame's
visible tracked entities, the **first** cache-missing one consumes the flag and bakes synchronously
right there; every other cache-missing entity later in the same iteration gets neither a bake
attempt nor a cache write, drawing as a fallback dot and retrying on a later frame once the flag is
re-armed. In a scene with many simultaneously-new species the icon set fills in gradually, one new
icon per frame at most, rather than stalling one frame or using a worker thread. Once every visible
species is cached, steady state is a plain atlas-sprite lookup with zero rendering work.

The bake fully saves/restores the ambient projection/pose-stack matrices and rebinds the caller's
own destination framebuffer before returning, so a bake is transparent to the surrounding draw call
aside from its one-time GPU cost.

---

## 9. 1.17.1-era specifics and deviations from the modernized architecture

Constants confirmed directly from source (all introduced with context in §3–§8 above):

| Constant | Value |
|---|---|
| Final icon / atlas-cell size | 64×64 px |
| Supersampled bake resolution | 512×512 px (8× per axis) |
| Outline: alpha-discard threshold / offset pattern | 5% / 8 directions, ±1 final-pixel |
| Atlas edge length / icons per full atlas | min(driver max, 1024) rounded to a multiple of 64 / 256 typical |
| Pose-neutralization forced age | 10 ticks |
| Bakes permitted per rendered frame | 1 |

Deviations from the reported modern architecture, each confirmed by reading the code rather than
assumed:

- **Synchronous, render-thread, one-bake-per-frame throttle — not an async background pool** (§8).
- **No content auto-crop step** — the canvas is always the full fixed 64×64; size is controlled
  entirely by camera scale (§5.2–§5.3).
- **No dedicated headgear/armor icon sub-pipeline** — no second FBO pass, no separate cache, no
  distinct sprite layer. Where armor appears at all, it's an emergent consequence of the generic
  same-class multi-pass replay (§3.3, §4.3); the only equipment-specific machinery is the cache-key
  differentiator (§2.1).
- **Players are not special-cased** — they use the identical generic humanoid heuristic as any
  other biped mob (§4.3); the portrait-like result is emergent.
- **The "extra custom layer" hook exists but is entirely unwired** (§4.5) — supplied to zero
  species in this build.
- **Outlining is a pure GPU compositing trick** (§5.4), not a CPU pixel neighbor scan or flood
  fill — no pixel data is ever read back from the GPU. VoxelMap's companion document, by contrast,
  performs CPU-side pixel post-processing for its equivalent step: two mods solving a visually
  similar problem with genuinely different mechanisms, not just different constants.

**Environment notes for an implementer targeting this exact mapping set.** Several internal
reflection lookups explicitly special-case unwrapping a specific shader mod's render-layer-wrapping
class before inspecting a layer's internal state — shader-mod compatibility was a deliberate,
tested concern here, not incidental. Separately, before relying on its own vertex-observation
instrumentation, the pipeline runs a one-time self-test confirming its synthetic vertex-consumer
wrapper actually detects emitted vertices on the current rendering backend, silently falling back
to a lower-fidelity replay path if the test fails rather than assuming its instrumentation works.
The model-class vocabulary the whole heuristic keys off — a base entity-model class; an "animal"
family adding head-parts/body-parts accessors; a "flat part-list" family and a "composite
multi-part" family as the two ways non-animal models are built; a humanoid/biped family with
head+hat fields; a legacy single-head-accessor interface; and model parts carrying their own cuboid
list and named-child map — is the exact seam every branch in §4.1 keys off, worth knowing by name
before porting this heuristic to a mapping revision where field/method names may have shifted.

---

## 10. Failure modes worth copying defensively (or explicitly not)

- **The one-real-render-then-replay strategy trusts a single traced frame is representative.** An
  entity whose renderer conditionally varies frame-to-frame (a randomly-swapped or multi-frame
  procedural texture) gets whatever was live at bake time, permanently, until something forces a
  reset. Worth deciding deliberately whether to reproduce this or add explicit re-bake triggers.
- **Every exception path caches the same permanent failure, with no distinction between "cannot
  structurally be rendered" and "hit a transient resource error"** (an out-of-memory GL allocation
  is indistinguishable from an unsupported shape once cached, §7). A cleaner implementation would
  retry transient failures and only permanently cache structural ones.
- **The recording filter is by class identity, not object identity** — a mod reusing a shared model
  class for two unrelated purposes could have those draw calls conflated. Low practical risk, worth
  a guard if reproducing this design.
- **Self-testing whether your own instrumentation observes anything before trusting it** is a good
  defensive pattern independent of the specific mechanism, worth keeping in any reimplementation
  that also intercepts engine-internal render calls.
- **Treating "nothing observed" as "everything visible" rather than "nothing visible"** (§3.2) is a
  deliberate trade of a possibly-too-inclusive icon for a guaranteed-non-blank one — worth keeping
  as an intentional choice, not silently "fixed" into the arguably-more-correct opposite default.

---

## Confidence notes

- Everything in §2–§8 was read directly from decompiled source for the analyzed build (Xaero's
  Minimap 23.9.7, Fabric, Minecraft 1.17.1, via Modrinth), including §9's numeric constants, the GL
  blend arguments behind §5.4's outline, and the shader source it consumes (read as a plain-text
  jar resource, not decompiled bytecode). Confidence on these is high.
- **Absence of a background baking pool and of a content auto-crop step** (§9) are negative
  findings — no such code was found anywhere in the icon-pipeline classes or their direct callers.
  As with any negative finding from a single-version read, an older or newer release could differ;
  this was not cross-checked against another version.
- **Whether armor actually renders for every humanoid-shaped mob in practice** (§4.3) is a
  structural inference — vanilla's armor-layer and body renderers share a model class for that
  shape family, cross-checked against the class-identity filter's logic — but not verified against
  a live game.
- The **per-species camera-tweak table** (§5.3) and **hardcoded field-name table** (§4.2) were each
  read completely and resolved against the `1.17.1+build.65` mapping set with confidence; a small
  number of individual field identifiers referenced by the code did not resolve against that exact
  build (mostly confirmed as an alternate spelling of a field that did resolve, consistent with the
  dual-lookup pattern in §4.2) — this doesn't change the behavioral description but is noted for
  anyone re-deriving exact identifiers.
- The shader-mod compatibility and self-test notes (§9) were read but not exercised — no shader mod
  was actually loaded alongside this jar during analysis.
- Comparisons to "the modern/reported architecture" are against the architecture description
  supplied as this task's brief, not a second decompiled version of this mod — no newer Xaero's
  Minimap release was decompiled for this document.

---

## Source anchors

For traceability only — not part of the normative behavior description above. All names are from
Xaero's Minimap 23.9.7 (Fabric, Minecraft 1.17.1), package root `xaero.common.minimap.render.radar`
unless noted.

- `EntityIconManager` — cache-key composition (`getEntityHeadTexture`, `getSavedEntityId`),
  once-per-frame bake gate (`allowPrerender`/`canPrerender`), JSON loading (`resetResources`),
  reset entry points (`reset`).
- `EntityIconPrerenderer` — the bake: traced-render invocation and pose neutralization
  (`prerender`, `LivingEntityRotationResetter`), part-selection dispatch (`renderIcon`,
  `renderModel`), per-part replay (`EntityIconModelPartsRenderer.renderPart`), FBO/camera/outline
  mechanics, atlas hand-off; detection callbacks `onModelRenderDetection`/
  `onModelPartRenderDetection`; gate `DETECTING_MODEL_RENDERS`.
- `ModelRenderDetectionElement`, `ModelPartRenderDetectionInfo` — per-pass/per-part recording
  (§3.2).
- `EntityIconDefinitions` — hardcoded field tables (`getMainModelPartFields`,
  `getSecondaryModelPartsFields`), model-root substitution (`getModelRoot`), full-render/
  forced-field-check species lists (`fullModelIcon`, `forceFieldCheck`), the camera-tweak table
  (`customTransformation`), and the default variant-string builder (`buildVariantIdString`).
- `EntityIconModelFieldResolver`, `ResolvedFieldModelPartsRenderer`,
  `ResolvedFieldModelRootPathListener` — the field-path grammar and reflective walker behind §4.1
  step 0 and §6.4.
- `resource.EntityIconDefinition`, `resource.EntityIconModelConfig` — the JSON schema (§6.2, §6.4).
- `custom.EntityIconCustomRenderer` and its subclasses — the unwired extra-layer hook (§4.5).
- `xaero.common.icon.XaeroIcon`, `XaeroIconAtlas`, `XaeroIconAtlasManager` — atlas packing (§5.5).
- `xaero.common.graphics.CustomRenderTypes` (`entityIconRenderType`, `EntityIconLayerPhases`) —
  captured-render-layer reconstruction (§3.2).
- `xaero.common.graphics.ImprovedFramebuffer` — offscreen render targets (§5.1), mipmap generation.
- `xaero.common.minimap.render.MinimapRendererHelper` (`drawIconOutline`,
  `drawMyTexturedModalRect`) plus the `pos_tex_icon_outline`/`pos_tex_alpha_test` shader pair
  (plain-text GLSL jar resources, not decompiled) — the blend-state/discard mechanics of §5.4.
- `xaero.common.mixin.MixinCompositeEntityModel`, `MixinSinglePartEntityModel`, `MixinModelPart` —
  the three injection points implementing §3.1's two hooks.
- `xaero.common.minimap.render.radar.element.RadarRenderer` — per-frame call site arming the
  bake-permission flag (§8).
- `xaero.common.events.ModEvents`, `xaero.common.settings.ModSettings` — reset triggers (§2.3).

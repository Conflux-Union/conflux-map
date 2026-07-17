# Reference Specs — Clean-Room Workflow

This directory holds the *behavior specifications* that a few of Conflux Map's
larger features (color sampling, zoom/LOD, cave/nether/end layers, waypoints,
entity radar) were implemented from. It exists to document, and make
independently checkable, how those features were built without copying code
from any All-Rights-Reserved reference mod.

## Why this exists

Conflux Map targets behavior comparable to established minimap mods like
VoxelMap and Xaero's Minimap. Both are closed-source/All-Rights-Reserved (see
[`THIRD_PARTY_NOTICES.md`](../../THIRD_PARTY_NOTICES.md) for the exact
licensing findings), which rules out copying or adapting their code, assets,
or text under any license this project could ship. What it does not rule out
is looking at how they *behave* - zoom steps, color choices, layer-detection
heuristics, waypoint UX conventions - and reimplementing the same observable
behavior from scratch, in this project's own structure and words. That is a
long-standing, legally established practice (clean-room / black-box
reimplementation), and it only works if the "looked at the original" step and
the "wrote the implementation" step are kept genuinely separate.

## The two-stage workflow

1. **Spec extraction.** A read-only reference clone (VoxelMap-Updated) is
   checked out *outside this repository* - never committed, never opened by
   the implementation stage. A spec-extraction agent reads that reference
   source and writes one Markdown document per feature area into this
   directory: `surface-color-sampling.md`, `zoom-lod-ux.md`,
   `cave-nether-layers.md`, `waypoint-ux.md`, `radar-icons.md`. Each document
   describes *what the system does and why* - data shapes, algorithm steps,
   numeric constants, thresholds, edge cases - in prose and
   language-neutral pseudocode. Source code, identifiers, comments, and
   verbatim strings from the reference are deliberately excluded; only
   behavior, which is not copyrightable, crosses this boundary.
2. **Implementation.** A separate implementation pass reads *only* the spec
   document(s) plus this project's own architecture plan - never the
   reference source - and writes the actual Conflux Map code: its own class
   structure, naming, package layout, and idiom, deliberately not mirroring
   the reference implementation's organization. Where a spec calls out a
   reference-specific quirk or limitation, the implementation is free to
   (and often does) do something better instead, noted inline as a
   "Recommendation" in the spec.

This split means anyone can audit the result two ways: read a spec document
and confirm it contains no copied expression, or read the implementation and
confirm it isn't structured like the thing it's compatible with.

## What's in this directory

| Document | Feature area | Slice |
|---|---|---|
| `surface-color-sampling.md` | Turning a loaded chunk column into one map pixel (block color, biome tint, slope/water shading) | S3 |
| `zoom-lod-ux.md` | Minimap/world-map zoom steps, rotation, corner/size layout, multi-resolution LOD | S4/S5 |
| `cave-nether-layers.md` | Cave-mode auto-detection, Nether current-layer/ceiling/Y-slice modes, End rendering | S7 |
| `waypoint-ux.md` | Waypoint data model, cross-dimension coordinate conversion, death points, list/edit UI, in-world and minimap indicators | S8 |
| `radar-icons.md` | Entity classification, radar dot rendering, above/below elevation cues | S9 |
| `radar-auto-icons-voxelmap.md` | VoxelMap's offscreen model-render icon pipeline (part selection, FBO bake, pixel post-processing, caching) — reference for a future dynamic-icon tier | unbuilt |
| `radar-auto-icons-xaero.md` | Xaero's mixin-instrumented render-trace icon pipeline (trace → head replay → GPU outline → atlas) — reference for a future dynamic-icon tier | unbuilt |

## Ground rules that apply to every spec in this directory

- No source code, class/method/field names, comments, or verbatim UI strings
  from the reference implementation appear in the normative body of these
  documents. The `radar-auto-icons-*` specs additionally carry a segregated
  **Source anchors** appendix listing real class/method names purely for
  traceability — anyone auditing can cross-check a behavioral claim against the
  named anchor without those identifiers bleeding into the implementation.
- Numeric constants and thresholds *are* included where they matter, because
  they are behavior (e.g. "zoom in 4 steps of 0.5/1/2/4 blocks-per-pixel"),
  not expression.
- Where the reference implementation does something that looks like an
  accident of its own code structure rather than a deliberate design choice,
  the spec says so and recommends a cleaner alternative instead of
  reproducing the accident.
- The reference clone this was read from is never committed to this
  repository and is not a build or runtime dependency of Conflux Map.

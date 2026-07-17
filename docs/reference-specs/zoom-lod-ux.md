# Minimap & World Map Zoom / Scale / Rotation — Behavior Specification

This document specifies the observable *behavior* of a Minecraft minimap
mod's zoom, rotation, layout, and multi-resolution world-map systems,
derived by reading a reference implementation for clean-room purposes. It
intentionally avoids describing source code organization, identifier names,
or verbatim UI copy. All facts below are behavioral rules, data shapes, and
numeric constants — implement them however fits the target codebase's
architecture.

### Three map surfaces, up front

The reference implementation actually has three distinct map presentations,
which this document is careful to keep separate:

1. **Corner minimap** — a small always-on overlay pinned to a screen corner.
2. **Enlarged live minimap** — a hotkey-toggled, screen-filling version of
   the *exact same* live-updating minimap buffer/zoom system as the corner
   minimap (no panning, still centered on the player, rotation forced off).
   It is a display-size toggle on the live minimap, not a separate feature.
3. **Persistent/explorable world map** — a fully separate full-screen
   interface with independent mouse-driven pan/zoom and a disk-backed,
   region-tiled rendering pipeline covering the whole explored world.

Sections 1–3 below cover the corner minimap (and, where noted, how the
enlarged live variant differs). Section 4 covers the persistent/explorable
world map exclusively.

## 1. Minimap zoom

### The level set

The live minimap maintains **five discrete zoom levels**, indexed 0
(tightest/most magnified) through 4 (widest/most zoomed out). Each level is
backed by its own independently-sized, persistently-maintained pixel buffer
at native 1-pixel-per-block resolution — not one buffer rescaled per level:

| Level | Buffer resolution | World diameter shown | Displayed multiplier |
|-------|-------------------|-----------------------|-----------------------|
| 0 | 32×32 px | 48 blocks (3×3 chunks) | 4× |
| 1 | 64×64 px | 80 blocks (5×5 chunks) | 2× |
| 2 (default) | 128×128 px | 144 blocks (9×9 chunks) | 1× |
| 3 | 256×256 px | 272 blocks (17×17 chunks) | 0.5× |
| 4 | 512×512 px | 528 blocks (33×33 chunks) | 0.25× |

The buffer resolution doubles each step while the world-diameter-per-level
follows a chunk-count progression (3, 5, 9, 17, 33 chunks across — each step
adds one full ring of chunks around the previous square). The "displayed
multiplier" is the number shown in the on-screen zoom-changed message; it
halves each step outward and is derived as `2^(2 − level)`.

Regardless of which level is active, the on-screen minimap always renders at
the same fixed pixel footprint — zooming changes how much world is packed
into that footprint, not the footprint's size. The enlarged live-minimap
toggle uses this same five-level system rendered at a larger fixed footprint;
it is not a sixth "more zoomed out" level.

### Hotkey cycling

A dedicated key cycles the active level. Cycling always steps toward the
**next more-zoomed-in level** (4→3, 3→2, 2→1, 1→0); stepping past the
most-zoomed-in level (0) wraps around to the most-zoomed-out one (4) and
continues from there. Each press shows a transient on-screen message with
the new displayed multiplier and forces an immediate full recompute of that
level's buffer (see §6).

### How each level's texture is produced and sampled

Only the **currently active** level's buffer is kept live-updated by the
background recompute process (§6); the other four sit with whatever content
they last held (stale, possibly from a different world/location) until
reselected, at which point switching triggers a full rescan of that level
from world data before it's shown — it is never just a rescaled copy of
another level's buffer.

A separate "filtering" display setting controls how that native buffer is
turned into an on-screen texture, independent of which level is active:

- **Filtered**: the native small buffer (e.g. 32×32 at level 0) is uploaded
  directly to the GPU, and hardware bilinear sampling stretches it up to
  display size — this blends/blurs colors across block boundaries, giving a
  smooth, softened look.
- **Unfiltered** (default): the buffer is always allocated at a fixed
  maximum size (512×512) regardless of level. Each logical one-block pixel
  is manually replicated (nearest-neighbor fill, not interpolated) into an
  N×N block of raw texture pixels, where N = 512 ÷ that level's native
  width (16, 8, 4, 2, 1 for levels 0–4 respectively). The GPU still applies
  bilinear filtering on top, but since neighboring texels inside one
  replicated N×N block are identical, blending only ever occurs at the
  boundary between two already-uniform blocks — producing crisp, blocky
  edges that match the game's own block-art aesthetic instead of a blur.

Both filtered and unfiltered variants are kept resident simultaneously for
all five levels (ten texture objects total); the filtering setting simply
switches which set is drawn from, with no rebuild needed when toggled.

## 2. Minimap rotation

### Modes

Two mutually exclusive orientation modes, toggled by a setting (default:
player-rotate):

- **Player-rotate**: the map spins beneath a fixed "up" player-facing arrow,
  so whatever is directly ahead of the player is always drawn toward the
  top of the map.
- **North-locked**: the map's orientation never changes; instead the
  player's arrow icon itself rotates to indicate current facing against the
  fixed map.

A separate "legacy north" toggle (independent of the above) adds a fixed
90° offset to the locked reference orientation — a compatibility option for
an older compass convention, applied identically whether player-rotate is
on or off.

The enlarged live-minimap toggle **forces player-rotate off** regardless of
the setting — only the small corner overlay supports live map rotation; the
enlarged view always shows the fixed (optionally 90°-offset) orientation
with just the arrow rotating.

### Per-element behavior when player-rotate is on

- **Map background texture**: rotates opposite the player's live facing
  angle, so the world appears to spin beneath a stationary viewpoint.
- **Player arrow icon**: stays pointing straight up at a fixed orientation
  (it's the map that moves, not the arrow).
- **Waypoint markers, in-range**: screen *position* orbits the map center
  following the same rotation as the map texture, but the icon *artwork*
  itself is drawn upright — a rotate/translate/counter-rotate transform
  cancels out the map's spin for the icon's own orientation, so it never
  renders sideways or upside-down.
- **Waypoint markers, out-of-range**: swapped to a distinct directional
  arrow glyph pinned to the map's edge; unlike the normal icon, this arrow
  glyph is the one element that *does* rotate with its bearing, since its
  whole purpose is pointing toward the off-map target.
- **Radar/entity contact icons**: identical pattern to in-range waypoints —
  position orbits with the map, icon artwork stays upright via the same
  counter-rotation technique. Contact name labels follow the same rule.
- **Cardinal letters (N/E/S/W)**: each letter's *position* orbits around the
  map's rim to stay aligned with its true compass bearing as the player
  turns, but the letter glyph itself is drawn upright, never rotated or
  mirrored.

### Per-element behavior when north-locked

Map texture and cardinal letters stay fixed on screen; only the player
arrow rotates to show live facing. Waypoint and radar icon positions are
computed against the fixed reference angle (plus the optional legacy-north
90° offset) instead of the player's live facing, so they don't move at all
as the player turns in place — only as the player physically moves.

### Smoothness

The player's facing angle and world position feeding all of the above are
linearly interpolated every rendered frame between the previous and current
game-tick values, weighted by that frame's fractional progress toward the
next tick. Because the underlying simulation only advances once per tick but
rendering happens far more often, this keeps map rotation and panning
visually smooth (no stepped/jittery motion) during rapid turning or movement
without needing any separate animation system — it falls out of sampling
the same interpolated position/facing used for camera rendering generally.

## 3. Minimap layout

### Size

Six discrete size options (roughly: small, medium, large — default, extra
large, 2× extra large, 3× extra large). Each option is an offset added to
an **auto-computed base scale factor** derived from window resolution (the
window is repeatedly halved against a ~320×240 reference until it would drop
below that floor, and the resulting divisor is the base scale). Size
settings are therefore relative bumps on top of that auto-scaling, not fixed
pixel dimensions — the same size setting still renders larger in absolute
pixels on a bigger/higher-resolution window.

### Corner placement

Four corner positions (top-left, top-right — default, bottom-right,
bottom-left). The map's center sits a small fixed margin in from both edges
of its corner.

One conditional auto-adjustment: when pinned specifically to the top-right
corner and the player has active status-effect icons showing (which render
in that same corner in the base game), the map shifts downward to avoid
overlapping them. The shift amount depends on effect severity — a larger
offset is reserved if any active effect is a "harmful" one (which the base
game renders larger/more prominently) versus only "beneficial" effects.

### Coordinate/info text placement

A text block sits just outside the map's frame, above or below it depending
on available room — if the map's corner is near the bottom of the screen
the text is placed above it instead of below, so it never runs off-screen
vertically. Up to three stacked lines, each independently toggleable:

1. Coordinates — either two lines (X,Z then Y) or one combined line (X,Y,Z),
   or hidden entirely, per a three-way setting.
2. Current biome name (optional).
3. A transient status message (e.g. world/dimension-change confirmation),
   which auto-clears three seconds after appearing.

The enlarged live-minimap variant replaces this stacked block with a single
line docked at the top of the screen, showing coordinates alongside a
compass-word description (north/south/east/west or combinations) computed
by bucketing the current facing angle into 45°-wide wedges around each of
the four cardinal points — a plain-language heading string rather than a
numeric bearing.

### Square vs. round frame

Purely a masking choice over the same underlying map texture (a square
stencil+frame image vs. a circular one) — the pixel data feeding the map is
identical either way. Two behavioral differences follow from the shape
itself, not from any separate rendering path:

- **Square + player-rotate combined**: the source map is pre-rendered about
  41% larger (a √2 scale-up) than normal before being rotated and cropped
  to the square frame. Without this, a diagonally-facing rotation would
  leave empty triangular gaps at the square's corners, since a square
  rotated inside its own bounding box no longer covers that box's corners.
  A circular crop never needs this extra scale-up (rotating a circle inside
  itself never exposes gaps), so round mode always uses the map at its
  normal size.
- **Off-map edge clamping**: waypoint/radar icons that fall outside the
  visible map area are redrawn pinned to the frame's edge (see §2) rather
  than being dropped. The clamp threshold is a uniform radius in every
  direction on the round frame, but an axis-aligned (box) threshold on the
  square frame — so on the square frame, points near a corner clamp
  noticeably farther from map-center than points near an edge midpoint,
  following the shape of the square itself rather than a circle.

## 4. Fullscreen (persistent/explorable) world map

### Pan mechanics

- **Mouse drag**: holding the primary mouse button and moving the cursor
  translates the view opposite the drag direction, 1:1 in world-space at
  the current zoom (screen-pixel delta divided by that zoom's
  world-units-per-screen-pixel ratio, recomputed every frame).
- **Keyboard**: the four directional movement keys pan continuously at a
  speed that stays roughly constant *on screen* regardless of current zoom
  (the base speed is divided by the zoom factor before being applied), with
  a sprint/run modifier key roughly doubling that speed. Panning speed is
  scaled by real elapsed time between frames, so it's frame-rate
  independent.
- **Release momentum**: releasing a mouse drag doesn't stop the pan
  instantly — the drag velocity from the moment of release coasts to zero
  over 0.7 seconds along a standard exponential ease-out curve (value
  approaches its target following a `1 − 2^(−10t)` shape), giving a light
  inertial "fling" after a fast drag.
- **Jump-to-coordinate**: clicking directly on the coordinate readout turns
  it into an editable text field pre-filled with the current view-center
  coordinate. Typing new X/Z values and confirming re-centers the view
  exactly on that point instantly (no animated pan, no zoom change).

### Zoom mechanics

Unlike the minimap's five discrete levels, the world map uses a
**continuous multiplicative zoom factor** (default 4×; user-configurable
minimum/maximum bounds default to 0.5× and 16×, adjustable across powers of
two from 0.125× up to 32×).

- **Mouse wheel**: each notch multiplies (scroll toward zoom-in) or divides
  (scroll toward zoom-out) the zoom target by roughly 1.26× — about a 26%
  step per notch.
- **Keyboard shortcuts**: a jump-style key and a sneak-style key apply the
  same 1.26× step in the zoom-out and zoom-in directions respectively.
- **Animated transition**: every zoom change (wheel or keyboard) animates
  from the old value to the new target over 0.7 seconds using the same
  exponential ease-out curve as pan-release momentum, rather than snapping
  instantly.
- **Zoom-at-cursor vs. zoom-at-center**: mouse-wheel zoom keeps the world
  point currently under the cursor visually fixed — each animation frame,
  the pan offset is nudged by an amount proportional to the cursor's
  screen-space distance from center, scaled by that frame's incremental
  zoom ratio, producing a "zoom toward cursor" feel. Keyboard-triggered
  zoom instead always anchors on the exact screen center, ignoring cursor
  position.
- **Ultrawide compensation**: on windows wider than 1600 logical pixels, an
  extra linear scale factor (`windowWidth / 1600`) is applied to the
  effective zoom math, so the amount of world visible per physical
  screen-inch stays consistent rather than an ultrawide window simply
  showing more world at the same nominal zoom number.

### Multi-resolution / LOD scheme

The world is partitioned into fixed **256×256-block regions** (16×16
chunks each). Each currently-loaded region owns exactly **one 256×256-pixel
texture** (one texel per block) plus a GPU-generated mipmap chain of **seven
additional, progressively-halved levels**, built with a simple box/mean
averaging downsample (not nearest-neighbor or a sharper filter). There is
no separate pre-baked set of low-resolution tiles — "zooming out" relies
entirely on the GPU sampling a coarser mip level of the same per-region
texture, the same way any standard mipmapped game texture would minify.

The **one zoom-dependent switch** in the whole scheme is the texture
sampler used per region: below roughly 2× magnification (more zoomed out)
the sampler blends smoothly across mip levels during minification; at or
above 2× magnification (the default view and anything more zoomed in) it
snaps to a single nearest mip level with no blending. This is the only
hard LOD threshold in the system — everything else is continuous GPU
minification of the same eight-level mip chain.

**Region load**: every rendered frame while the map is open, the currently
visible world-rectangle is converted into a region-grid rectangle (with one
extra region of padding on every side beyond the visible edge). Any region
inside that padded rectangle that isn't already resident is created and
queued for a background load, which tries, in order: an on-disk per-region
cache file from a previous session; freshly-scanned data from chunks the
client currently has loaded; and (singleplayer only) a direct scan of the
world's saved chunk storage for areas within that region the player hasn't
walked through yet but that already exist on disk. Each of a region's
16×16 chunks is filled independently as its data becomes available, so a
region can be partially populated and rendered before it's fully loaded.

**Region unload**: regions falling outside the padded visible rectangle are
**not** deleted immediately. A shared pool holds all currently-known
regions, sized by a configurable count (default around 500, with a
dynamically-enforced floor computed from the configured minimum zoom bound
— roughly, enough region slots to cover a full ~1600×1100-pixel screen at
that most-zoomed-out setting, plus a 35% safety margin, so a user can
zoom all the way out without constant eviction thrashing). Whenever the pool
exceeds its size limit, it's pruned oldest-viewed-first, breaking ties by
distance from the current view center — the least-recently-seen, farthest
regions are evicted first. A region being evicted first flushes any pending
changes to its on-disk cache file (and, if an optional "export images"
setting is on, a standalone PNG snapshot) before being dropped from memory.
Idle regions untouched for 5 seconds additionally have their in-memory pixel
and per-block data compressed (not evicted, just shrunk) until touched
again, decompressing transparently on next access.

**Region rebuild cadence**: a region's 256×256 pixel image (and its mipmap
chain) is only rebuilt from scratch when that specific region's underlying
block data actually changed (a chunk inside it loaded new terrain or was
edited) or a display-affecting setting changed — never merely because the
view panned or zoomed across it. Chunk-change events are applied to their
owning region on a roughly 20-tick (~1 second) delay after the event fires,
rather than instantly, presumably to let block/lighting state settle before
capturing it.

### Cursor coordinate display

The visible coordinate readout continuously tracks whichever world point is
currently under the mouse cursor — recomputed every frame from cursor
screen position run back through the current pan/zoom transform — not the
view's pan-center coordinate. If the interface has recently been driven by
keyboard input instead of the mouse, the readout instead tracks a fixed
crosshair reticle shown at screen-center in place of the OS cursor (which is
itself hidden while keyboard-driven).

## 5. Map persistence view state

**Pan position is not persisted.** Whenever the persistent/explorable map
screen is *closed*, its stored view-center coordinate is overwritten every
game tick to continuously track the player's live position — it only stops
tracking the player while the screen is open and actively being
panned/dragged. The practical effect: reopening the map always starts
centered on wherever the player currently is, never on wherever the view
was last scrolled to in a previous session (or even a previous open/close
within the same session).

**Zoom level does persist, but only as a single global value** — one shared
number across every world, save, server, and dimension, not a per-world or
per-dimension memory. It updates in memory immediately on every wheel or
keyboard zoom action, and is written out to the shared settings file
whenever a settings save is triggered (which happens on many routine
actions elsewhere in the interface, plus unconditionally on client
shutdown) — so the zoom level generally does carry over into the next
session, but switching to a *different* world or dimension does not restore
a zoom level specific to that world; it simply keeps whatever was last set,
anywhere.

For comparison, the corner minimap's own zoom level (§1) is also a single
global value with no per-dimension memory, but it is written to disk
**immediately** on every hotkey press rather than waiting for a later save
trigger — a persistence-eagerness difference between the two zoom systems
worth preserving if reproducing both.

The configured min/max zoom bounds, the region cache-pool size, and the
coordinate/waypoint-display toggles for the persistent map are all ordinary
persisted global settings living in that same single shared file.

Separately, there is an opt-in (default off) setting that additionally
writes each region's rendered 256×256 image out as a standalone PNG file on
disk purely for external viewing/export — this is a one-way export
convenience and plays no role in restoring view state on the next open.

Cached region data itself (§4) is keyed per-world and per-dimension on disk
(each dimension's regions live under their own subfolder), so changing
dimension while the map is open effectively swaps which region set is being
displayed without needing to reset the map screen — the view center simply
continues tracking the player, who is now expressed in the new dimension's
coordinate space.

## 6. Rendering cadence

### Corner / enlarged live minimap

A dedicated background thread continuously recomputes the **currently
active zoom level's** pixel buffer, woken up roughly once per rendered
frame (signaled every time the HUD draws, not on a fixed timer) rather than
running unthrottled or on a fixed clock.

- **Incremental update (the common case)**: when the player has only moved
  a small distance since the last pass, the existing buffer is shifted in
  memory by the movement delta (a raw block-copy, not a redraw) and only
  the newly-exposed strip of pixels at the leading edge(s) is recomputed
  from world data — the bulk of the buffer is reused untouched.
- **Full recompute**: triggered by switching zoom level, teleporting
  farther than one buffer-width in a single step, crossing into or out of
  "beneath rendering" (cave/nether-style shading), loading a new world or
  dimension, or various display-setting changes. A full recompute re-scans
  every pixel of that level's buffer from scratch.
- Only the actively-selected level's buffer is kept live by this process;
  the other four sit untouched until reselected, at which point selection
  itself forces the full recompute described above — so briefly-stale,
  last-seen contents can be visible for a moment before the rescan
  completes.
- The CPU-side pixel buffer and the GPU-uploaded texture are decoupled: the
  buffer can be updated multiple times between rendered frames, but the
  actual texture upload happens at most once per rendered frame, gated by a
  changed flag, and only for whichever level is currently being displayed.
- Lighting/sky-color inputs used to shade the map (torch flicker, day/night
  cycle, gamma setting, biome at the player's feet) are recalculated on
  their own change-driven schedule — checked every frame, but only actually
  recomputed when a relevant input changed, with a periodic safety-net
  refresh roughly every 50 ticks regardless, rather than every single
  frame unconditionally.

### Persistent/explorable world map

Every rendered frame while the screen is open, each region currently in
view is sent a lightweight "refresh" request — but that request is a no-op
unless the region's underlying data or a display setting has actually
changed since its last refresh. Real work (rebuilding the 256×256 pixel
image and regenerating its mipmap chain) happens on a background thread
pool, never the render thread, and only for regions that changed. A
per-region in-flight flag prevents a second background rebuild from being
queued while one is already running for that region.

### Radar / entity contacts

The full nearby-entity scan is amortized rather than repeated every tick:
roughly every 15 ticks, one-eighth of the tracked contact set (bucketed by
a stable per-entity hash) is refreshed/replaced, cycling through all eight
buckets over about 120 ticks (~6 seconds) of continuous play. A relevant
settings change forces an immediate full-set refresh instead of waiting for
the next scheduled slice. Each individual already-tracked contact's
on-screen position, however, is recomputed every single frame (using the
same partial-tick-interpolated player position/facing as the rest of the
minimap — see §2), so movement of already-visible icons looks smooth even
though which entities are tracked at all only updates in slow, staggered
batches.

## Confidence notes

- The exact numeric constants (1.26× zoom step, 0.7-second animation
  duration, the ~2× LOD sampler threshold, the 1600px ultrawide breakpoint,
  the 20-tick chunk-settle delay, the 15-tick/eighth-bucket radar
  amortization, the 5-second idle-compression delay) are transcribed
  faithfully from the constants used in the reference implementation. They
  are tuned to that implementation's specific buffer sizes and target frame
  rates; treat their *existence and rough shape* as reliable, and the exact
  values as a reasonable starting point rather than a hard requirement.
- The "displayed multiplier" and "world diameter shown" figures for the
  five minimap levels are derived arithmetically from the underlying
  resolution/chunk-count formulas rather than read from a literal lookup
  table, so they are high-confidence but worth double-checking against any
  UI string in the target project if exact wording matters.
- The claim that switching corner-minimap zoom levels always forces a full
  rescan (rather than sometimes reusing a still-valid stale buffer) is
  read directly from the level-switch code path and is high-confidence.
  Whether that momentary stale-frame flash is perceptible in practice
  depends on how fast the background thread completes the rescan relative
  to frame rate, which wasn't independently measured.
- The dimension-change behavior for the persistent map's cached regions
  (§5's closing paragraph) is inferred from the on-disk cache-key layout
  and a world-change handler taking the new dimension as a parameter; the
  precise trigger conditions for that handler (e.g. whether every
  portal-style dimension transition invokes it, versus only full
  world/server (re)joins) were not independently traced end-to-end and
  should be verified against the target Minecraft version's client
  lifecycle if faithful reproduction of dimension-switch behavior matters.
- The square-map "√2 scale-up when rotating" and the round-vs-square
  edge-clamp shape (§2, §3) are read directly from the transform math and
  are high-confidence; the specific pixel-radius/box thresholds used for
  clamping were intentionally left out of this document as approximate
  proportions rather than literal constants, since they depend on the
  minimap's fixed on-screen footprint size, which is itself an
  implementation choice.

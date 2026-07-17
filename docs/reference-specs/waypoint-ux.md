# Waypoints — Behavior Specification

This document specifies the observable *behavior* of a Minecraft minimap
mod's waypoint system — data model, cross-dimension coordinate handling,
death-point capture, list/edit UI, in-world rendering, minimap indicators,
and fullscreen-map interaction — derived by reading a reference
implementation for clean-room purposes. It intentionally avoids describing
source code organization, class/field/method names, or verbatim strings.
Everything below is a behavioral rule, data shape, or numeric constant —
implement it however fits the target codebase's architecture. Where a rule
is a vanilla Minecraft fact (e.g. the Nether coordinate ratio) rather than
something the reference mod invented, that is called out explicitly.

## 1. Waypoint data model

A waypoint is a small persisted record with the following conceptual
fields:

| Field | Type / range | Notes |
|---|---|---|
| Name | free text | Not required to be unique. Empty name is disallowed by the create/edit UI (see §5) but not by the underlying model. |
| Position X, Z | integer | Stored in a single canonical coordinate space, not "whatever dimension it was created in" — see §3. |
| Position Y | integer | Never scaled by dimension; see §3. |
| World/server scope | see §2 | Not a field on the waypoint's identity so much as a partition the whole waypoint collection lives in. |
| Sub-world scope | free text, may be empty | Empty means "applies to every sub-world within this world/server." See §2. |
| Dimension scope | a small set of dimension identifiers | Which dimension(s) this waypoint is considered to belong to. An empty set is treated as "every dimension," but ordinary create/edit flows always seed it with at least the creation dimension and never allow removing the last member — so in practice a waypoint is single-dimension-scoped unless the user deliberately multi-selects additional dimensions. See §2 and §3. |
| Color | three floats, 0.0–1.0 each (R/G/B) | Not a palette index — a free continuous color. See §5. |
| Icon | a name/key into a small built-in icon set | Free-form string in the model; an unrecognized value gracefully falls back to a generic default icon rather than erroring. The reference build ships roughly 30 built-in icons covering everyday categories (tools, food, animals, structures, etc.) plus one dedicated skull-style icon that death points use by convention (see §4) — nothing stops a normal waypoint from using that same icon, it is not reserved. |
| Enabled / visible | boolean | See the visibility-toggle semantics below — this is *not* the same thing as "currently applicable." |
| Type (normal vs. death) | **not present** as an explicit field in the reference implementation | See the recommendation below. |
| Creation timestamp | **not present** | See the recommendation below. |
| Grouping/tags | **not present** beyond the world/sub-world/dimension scoping above | There is no free-form folder/tag/group concept. |

**"Enabled" vs. "currently applicable."** A waypoint's *effective* active
state is the AND of three things: its own enabled flag, whether the
player's current sub-world matches its sub-world scope, and whether the
player's current dimension is in its dimension scope. The latter two are
runtime-computed, not stored. This distinction matters for rendering (§6,
§7) and for the management list (§5): the list still shows a
disabled-but-in-scope waypoint (with a toggle affordance to re-enable it),
but the corner-minimap and in-world layers hide anything that isn't
effectively active — with one deliberate exception on the fullscreen map,
noted in §8.

**No fixed-vocabulary "type" field.** The reference implementation
distinguishes a death point from an ordinary waypoint purely by convention
— a specific name pattern, a specific icon choice, and a specific
fade-toward-gray starting color — not by a stored discriminant. Its
death-handling logic (§4) identifies "is this a death point" by pattern-
matching the *name string* at runtime, which is fragile: a player who
manually renames an ordinary waypoint to match that pattern gets swept up
in the death-point rename/retention logic by accident. **Recommendation:**
give our own model an explicit `type: Normal | Death` discriminant field
instead of relying on name-string sniffing. It costs nothing and removes
an entire class of accidental-collision bugs.

**No creation timestamp.** "Sort by creation order" in the reference
implementation is really "sort by position in the in-memory list," which
happens to equal insertion order today but is not a durable, semantically-
named property. **Recommendation:** store an explicit creation timestamp.
It is strictly more robust (survives any future feature that reorders the
in-memory collection) for negligible cost, and lets "sort by created" mean
what it says.

## 2. Dimension / world identity scoping

Waypoint storage is partitioned at **three nested levels**, and getting
this partitioning right is what stops server A's waypoints from leaking
into server B or into a different singleplayer save:

1. **World/server identity (outermost, one persisted collection per
   value).** Computed once per session as: the singleplayer save's folder
   name, if playing offline/LAN-hosted; otherwise a value derived from the
   connection — a Realms server resolves to a stable realm-id-plus-owner
   identifier, a LAN game resolves to the LAN-advertised session name, and
   an ordinary remote server resolves to its configured `host:port` (with
   the default port stripped for a slightly friendlier identity string).
   Each distinct value gets its own independent waypoint collection on
   disk; nothing is shared across values.
2. **Sub-world scope (optional, within one world/server collection).** A
   free-text label that partitions waypoints *within* a single world/server
   collection into named sub-areas — useful for server setups that host
   multiple logical "worlds" behind one connection/save without actually
   changing the vanilla dimension. A waypoint whose sub-world label is
   empty applies to every sub-world in that collection; a non-empty label
   restricts it to sub-worlds whose *current* label matches. The reference
   implementation supports three ways a session's current sub-world label
   gets set: a companion server-side protocol that pushes the label
   automatically, a manual picker the player uses themselves, and a
   terrain-similarity heuristic that compares newly-scanned map data
   against previously-cached data for each known label and auto-selects
   only when exactly one candidate is unambiguously the best match. The
   first and third require infrastructure (a paired server plugin, and a
   persisted terrain cache) that's a stretch goal at best for a first
   implementation. **Recommendation:** implement only the manual picker for
   sub-worlds initially — it's the cheapest of the three and covers the
   common case (a player who knows their server has multiple logical
   worlds can just tell the mod which one they're in); treat automatic
   detection as a later enhancement, not a launch requirement.
3. **Dimension scope (innermost, per-waypoint).** Covered in detail in §3
   below, since it interacts with coordinate conversion.

**Multiple dimensions within one world/server** are *not* separate
waypoint collections — they're all in the same collection (level 1 above),
filtered per-waypoint by the dimension-scope set (level 3) and, for
coordinate *display*, converted per the rule in §3. A brand-new dimension
encountered mid-session (e.g. a modded dimension never seen before) is
simply registered on the fly and becomes selectable in the dimension-scope
picker; there's no fixed enumeration limit beyond the vanilla three plus
whatever else the server advertises.

**Confidence/edge-case note on world identity:** identifying a remote
server purely by `host:port` is a pragmatic but imperfect heuristic — a
server whose address changes (dynamic DNS, migration to a new host) will
silently start a *new* waypoint collection rather than continuing the old
one, and it can't distinguish two different servers that happen to share
an address. If the target platform/loader exposes anything more stable
(e.g. a server-supplied UUID), prefer that; otherwise this address-based
approach is a reasonable, low-effort default to carry forward.

## 3. Cross-dimension coordinate conversion

**The ratio itself is a vanilla Minecraft fact, not something this mod
invents:** each dimension type declares a coordinate-scale multiplier —
1.0 for the Overworld and the End, 8.0 for the Nether — that vanilla
itself uses for its own Nether-portal linking logic. The classic "Nether
coordinates are Overworld coordinates ÷ 8" rule falls directly out of that
declared multiplier.

**Which axes it applies to:** only the two horizontal axes, X and Z.
Vertical position (Y) is never scaled, regardless of which dimension a
waypoint was created in or is being viewed from — a waypoint's Y is always
displayed and used exactly as stored.

**Storage representation:** rather than storing "the dimension this was
created in" plus "raw local coordinates" and converting pairwise between
every possible dimension combination on demand, the reference
implementation stores X/Z in a single **canonical, scale-1.0 coordinate
space** (i.e. "as if the Overworld/End," since those two share the same
1.0 multiplier) and converts *on both write and read* using whichever
dimension is currently active:

```
# writing a coordinate the player typed/clicked while standing in dimension D:
storedX = enteredX * scaleOf(D)
storedZ = enteredZ * scaleOf(D)

# reading a coordinate for display while standing in dimension D:
shownX = storedX / scaleOf(D)
shownZ = storedZ / scaleOf(D)
```

Because both directions key off *the dimension the player is currently
in* (not the dimension the waypoint was originally created in), this one
pair of formulas correctly round-trips regardless of where the waypoint
started — creating it in the Nether stores the Overworld-equivalent X/Z
(local value × 8); viewing it later from the Overworld divides by 1 and
shows that same Overworld-equivalent value; viewing it from the Nether
divides by 8 and recovers the original Nether-local value. Both
directions truncate toward zero on the integer cast (ordinary `int`
truncation, not rounding) — this is a minor, rarely-visible source of
±1-block drift when repeatedly converting back and forth, not a
deliberately designed rounding rule.

**The End specifically:** since the End's declared coordinate-scale is
1.0 — identical to the Overworld's — **no conversion happens between
Overworld and End at all.** A waypoint's X/Z is numerically identical
whether displayed while standing in the Overworld or in the End. Only the
Nether's 8.0 multiplier ever produces a visibly different number.

**Display filtering — the actual rule, not the intuitive-sounding one.**
The task framing this document was written against poses two plausible
rules: "show everything everywhere, converting Overworld↔Nether and just
hiding End waypoints outside the End" versus something else. The
reference implementation does neither. Visibility is governed **purely by
each waypoint's own dimension-scope set** (§1/§2), independent of the
coordinate-scale math above:

- A waypoint is only rendered/active while the player's current dimension
  is a member of that waypoint's dimension-scope set (or that set is
  empty, meaning "every dimension" — reachable in practice mainly through
  hand-edited or legacy-format data, since the ordinary create/edit UI
  always seeds at least one dimension and never lets the set shrink to
  zero).
- By default, a newly-created waypoint's dimension-scope set contains
  **exactly the dimension it was created in** — nothing else. So a
  waypoint made in the End, left at its default scope, simply does not
  appear at all while the player is in the Overworld or Nether — not
  coordinate-converted, not hidden-with-an-exception, just entirely out of
  scope. The same is true of an Overworld-made waypoint viewed from the
  Nether, etc.
- A player can deliberately widen a waypoint's scope by multi-selecting
  additional dimensions in the edit UI (§5). Once widened, coordinate
  display for the added dimensions follows the conversion rule above like
  any other dimension pair — e.g. an End-scoped waypoint that the player
  also adds to the Overworld's scope will show identical (unconverted)
  X/Z in both, because both share the 1.0 multiplier.

**Supporting evidence for "Y is never scaled":** the reference
implementation's teleport-to-waypoint logic falls back to a fixed
default height (open sky build limit, or a mid-range fixed height in a
ceilinged dimension like the Nether) whenever a waypoint's stored Y falls
below the *current* dimension's minimum height — a check that only makes
sense if Y is carried across dimensions completely unconverted and can
therefore end up out of range for the dimension currently being viewed.

**Recommendation for our mod:** keep the same canonical-storage approach
(store X/Z pre-multiplied by the creation dimension's scale, convert by
the *current* dimension's scale on both write and read) — it's simpler
than storing a home-dimension tag and doing pairwise conversion, and it's
the only representation that makes "show this waypoint's coordinates
correctly no matter which dimension you're currently viewing it from"
free. Keep dimension-scope filtering (visibility) and coordinate
conversion (display math) as two genuinely separate concerns, as the
reference implementation does — don't couple "is this dimension in
scope" to "does this dimension's scale differ from 1.0."

## 4. Death points

**Trigger.** Purely client-side: the reference implementation watches for
the moment the vanilla death screen first becomes the active UI screen
(edge-triggered — it fires once on the transition into that screen, not
continuously while it stays open) and treats that as "the player just
died." **Recommendation:** hook whatever client-side "player died" signal
the target loader/game version exposes most directly (a death-screen-open
callback, a health-reached-zero client event, etc.) — the exact hook is
an implementation detail, but the edge-triggered, once-per-death firing
behavior is the important part to preserve, since re-firing on every
render frame the death screen is open would obviously be wrong.

**Server-side gate.** Death-point creation is additionally gated by a
server-controllable permission flag (default: allowed) — a server
operator can disable client-side death-waypoint creation entirely for
their server, independent of the player's own local settings. Worth
keeping as a hook point for servers, even in a first implementation with
only client-local config, since disallowing your own client from
recording where you died is a legitimate server-owner request.

**Captured data.** Current position (X/Z stored pre-multiplied by the
current dimension's coordinate scale per §3, exactly like a manually
created waypoint), current dimension (as a single-member dimension-scope
set — see the "narrow by default" note below), and current sub-world
label. Y is captured **one block below** the player's floored vertical
position, a small empirical offset not applied to manually created
waypoints (which use the player's floored Y directly) — presumably
compensating for the death/spectator camera sitting at eye height inside
the block above where the player actually died.

**No explicit timestamp is captured** (consistent with §1's general
finding); the point's position in the naming/retention sequence below is
the only ordering signal.

**Retention policy — three modes, no built-in count cap:**

| Mode | Behavior |
|---|---|
| Off | No new death point is created on death. (Existing death points from before the setting was turned off are still cleaned up per the "off" rule below — this is a live gate on *creating new* points, not a toggle that also hides old ones.) |
| Most-recent-only (**default**) | Exactly one death point exists at a time. On each new death, whatever held the "latest" name/slot is deleted outright and replaced. |
| Keep history | Every death adds a new point rather than deleting the previous one. **There is no maximum-count cap in the reference implementation** — this mode grows without bound for the life of the world/server's waypoint collection. Treat "unbounded" as the literal, verified behavior, but strongly consider adding a configurable cap in our own implementation (the retention concept clearly wants one; the reference build just doesn't ship one) rather than faithfully reproducing unbounded growth. |

**Naming/labeling convention.** The newest death point gets a fixed
"latest death" label. On the *next* death, whatever currently holds that
label is renamed to a "previous death" label; in keep-history mode each
further death bumps every existing "previous death" entry's label by
appending/incrementing a counter, so you end up with a naturally-ordered
sequence (newest → oldest) purely from repeated renaming, with no
separate counter field stored.

**Visual distinction from normal waypoints.** Purely by convention, not a
rendering-layer special case: a dedicated skull-style icon, and a color
that starts near-white and — only in keep-history mode — fades a fixed
fraction of the way toward neutral gray on every subsequent death (each
death nudges every surviving "previous" entry's color about one-eighth of
the remaining distance toward gray, so colors asymptotically desaturate
toward gray the older/more-superseded a death point becomes, without ever
fully reaching it). Most-recent-only mode has no fading to observe, since
only one death point ever exists.

**Confidence note — a real quirk worth flagging explicitly.** The
rename/retire logic that hunts for "whatever currently holds the latest/
previous-death label" scans the **entire world/server waypoint
collection**, not just points in the player's current dimension or
sub-world. This is read directly from the code, high confidence. Practical
effect: die once in the Nether, then again in the Overworld (same
world/server), and the Nether death point gets silently renamed/retired by
the *second* death even though the player is nowhere near it. This is
almost certainly an accepted simplification rather than an intended
design point. **Recommendation:** scope the retention/rename logic to the
death point's own dimension (and sub-world) instead of the whole
collection — each dimension keeps its own "latest death" independently.
This seems like a strict improvement with no real downside, but flag it as
a deliberate deviation from the reference behavior if faithful parity
matters for any reason.

## 5. Waypoint list/management UI flows

**List screen actions:**

- **Create** — opens the create/edit form (§ below) pre-filled with the
  player's current position, current dimension (single-member scope, per
  §3), and current sub-world.
- **Edit** — opens the same form pre-filled with the selected waypoint's
  current values.
- **Delete** — behind a confirmation dialog naming the waypoint.
- **Toggle visibility** — a per-row control that flips the enabled flag
  directly from the list, without opening the edit form.
- **Highlight** — a distinct concept from visibility: marks one waypoint
  (or, from the fullscreen map, an arbitrary clicked-but-unsaved
  coordinate — see §8) as a temporary "ping" that renders with a
  dedicated crosshair/target marker style regardless of its own
  enabled/in-scope state, and is cleared on switching dimensions. Useful
  for "show me this one thing right now" without permanently changing
  anything.
- **Teleport** — issues a teleport to the waypoint's coordinates,
  available only when the player is actually allowed to run teleport
  commands (integrated-singleplayer-with-cheats/op, or a multiplayer
  server where such a command would be expected to succeed). Uses the
  waypoint's stored Y directly if it falls within the current dimension's
  valid height range, otherwise falls back to a sensible default height
  for that dimension (build limit, or a fixed mid-range height for a
  ceilinged dimension) — see §3's note on why this fallback exists.
- **Share** — an additional convenience action beyond the ones asked
  about here: broadcasts the waypoint's coordinates as a chat message
  (converting through the coordinate math in §3 if the waypoint is scoped
  to a single non-1.0-scale dimension), so other players can see where it
  is without needing the mod's own waypoint file.
- **Sort** — four modes: name (alphabetical, case-insensitive), distance
  from the player (3-D straight-line, not horizontal-only), creation
  order (see §1's caveat that this is really list-insertion order), and
  color (bucketed by hue). Each mode is a toggle button that also flips
  ascending/descending on a repeat click of the same mode. **There is no
  "sort by dimension" mode** — moot, given the filtering below.
- **Filter** — a live text search box matching against the waypoint name
  (case-insensitive, with any color/formatting codes stripped before
  matching). **There is no dimension or world filter control** — the list
  is already pre-restricted to the current world/sub-world/dimension scope
  before the player ever sees it (see below), so a same-screen dimension
  filter would have nothing extra to filter.

**Automatic list scoping.** The list only ever shows waypoints whose
sub-world and dimension scope match the player's *current* sub-world and
dimension — not "every waypoint ever created in this world/server," and
critically, **not gated by the enabled flag**: a disabled-but-in-scope
waypoint still appears in the list (with its visibility-toggle control
showing the disabled state), it just doesn't render in-world or on the
minimap. Waypoints scoped to a different dimension are invisible from this
screen entirely, with no way to reach them except by physically changing
dimension first.

**Edit form fields:**

- Name (free text, required non-empty to submit).
- X / Y / Z as three separate numeric text fields, pre-converted for
  display per §3 and re-converted back on save.
- Enabled toggle.
- Icon — opens a grid picker over the built-in icon set (§1); the
  currently-chosen icon is shown enlarged/highlighted in the grid, and
  hovering another shows its name as a tooltip.
- Color — opens a **continuous HSV picker**, not a fixed swatch palette.
  Two interchangeable picker layouts are offered (a combined hue/saturation
  wheel plus a separate value slider, or a hue ring plus separate
  saturation and value sliders) — purely a layout preference, both produce
  the same full RGB range. **There is no fixed palette and no "count of
  preset colors"** — every color in the full RGB gamut is reachable, and
  the numeric R/G/B (and hex) values are shown live as text while picking.
  **Recommendation:** replicate this as a free continuous color picker
  rather than inventing a fixed swatch count that the reference
  implementation doesn't actually have.
- Dimension scope — a scrollable list of every known dimension with a
  per-row checkmark/cross toggle; clicking a row's toggle adds/removes
  that dimension from the waypoint's scope set, except that the very last
  remaining dimension in the set cannot be removed while it's also the
  player's current dimension (preventing a waypoint from ever silently
  losing all scope and disappearing from view while you're looking at
  it).
- No grouping/tag field exists (consistent with §1).

## 6. In-world rendering

Two independently toggleable layers exist — a vertical beam and a
floating billboard sign — and either, both, or neither can be active at
once. **Defaults differ noticeably: the floating sign layer is on by
default, the beam layer is off by default.**

**Vertical beam.** A translucent, animated, upward-scrolling cylindrical
beam plus a wider, dimmer outer glow cylinder around it, colored per-
waypoint using the waypoint's own RGB (the outer glow at a much lower,
fixed alpha — roughly one-eighth opacity — than the core beam). The
genuinely notable behavior here: **the beam does not start at the
waypoint's own Y coordinate.** It is drawn as a full-height column from
the *dimension's absolute vertical floor* all the way up to the
*dimension's build limit*, positioned at the waypoint's X/Z — completely
independent of the waypoint's stored Y. So "does it extend from the
waypoint up through the sky" is not quite right: it extends through the
*entire* vertical span of the dimension, floor to ceiling, regardless of
where along that span the actual waypoint sits. This reads as an
intentional simplification (a full-height column is trivial to draw and
is visible from anywhere at that X/Z, vs. computing a beam segment that
starts exactly at a possibly-underground Y) rather than a bug, but it's
a real behavior worth deciding on deliberately rather than assuming
"beam starts at the pin." **Recommendation:** decide explicitly — a
full-height column is simpler and always visible; a beam segment starting
at the waypoint's actual Y reads as more precise/less noisy in a
build-dense area. Either is defensible; just don't default into one
without noticing.

**Floating billboard sign.** A camera-facing icon plus optional name/
distance labels, positioned at the waypoint (with a small fixed vertical
offset so it floats just above the ground-level point rather than
through it). Notable rendering rules:

- **Distance-scaled aiming/highlight cone.** The waypoint closest to the
  player's crosshair, *within its own distance-scaled cone*, is
  automatically treated as "pointed at" and gets its full label shown even
  without any key held; holding a modifier key (sneak/shift in the
  reference build) instead reveals full labels for *every* sign currently
  within its own cone, not just the single closest one. The cone half-
  angle itself widens at close range and narrows toward a small fixed
  minimum at distance (roughly: 5° plus up to another 5° that shrinks as
  1/distance), so up-close signs are much easier to "aim at" than distant
  ones. Signs that aren't pointed-at still render (icon + color), just
  without name/distance labels.
- **Two-pass occlusion fade.** Each sign draws twice: once depth-tested
  (full opacity, only visible with clear line of sight) and once without
  depth testing at a fixed low opacity (roughly 30%). Net effect: a sign
  behind a wall is still faintly visible through it, brightening to full
  opacity the moment line of sight opens up — an "X-ray hint, not a hard
  reveal" behavior.
- **Distance fade-in.** Alpha ramps linearly from 0 to full opacity over
  the nearest few blocks (roughly the first 5), so a sign doesn't pop in
  jarringly right next to the camera.
- **Disabled-but-in-scope waypoints** (highlighted ones aside) render at
  a further-reduced alpha rather than being skipped outright, when they
  render at all — see the render-distance-cutoff note below for when they
  don't render at all.
- **Optional distance text**, shown beside or below the name (configurable
  position, or the name can be hidden and the distance shown alone) with
  optional automatic km-conversion past a configurable distance threshold.
- **Render-distance cutoff** — an explicit config-driven maximum distance
  (3-D straight-line, in blocks; "off"/infinite is a valid setting) beyond
  which a sign is not rendered at all (not faded, not clamped — simply
  skipped), *except* for the one currently-highlighted/"pinged" waypoint,
  which always renders regardless of distance. **This cutoff applies only
  to the floating sign layer — the vertical beam layer has no distance
  cutoff of its own** and is limited only by the game's ordinary render
  distance. This is a real asymmetry in the reference implementation, not
  an oversight worth "fixing" silently — decide deliberately whether our
  beam layer should share the sign layer's cutoff or stay unbounded.
- **Toggles are global** (beam on/off, sign on/off), not per-waypoint —
  an individual waypoint has no "show my beam but not my sign" flag of
  its own, only the overall enabled/visibility flag from §1.

## 7. HUD / minimap indicators

This builds on the rotation/counter-rotation behavior already established
for the corner minimap (a waypoint's on-screen *position* orbits with the
map's own rotation, but its icon *artwork* is drawn upright via a
rotate → translate → counter-rotate transform, so it never renders
sideways) — see the sibling zoom/rotation specification for that general
mechanism. This section covers what's specific to waypoints: which icon
gets used, edge-clamping, and the render-distance cutoff.

**In-range markers.** Drawn upright (counter-rotated per the mechanism
above) at a small fixed pixel size, using the waypoint's own icon and
color. **No distance-text readout accompanies in-range minimap markers**
in the reference implementation — distance text is exclusively a
floating-sign-layer feature (§6), not a minimap feature. If per-marker
distance text on the minimap itself is wanted, treat it as a deliberate
addition beyond the reference behavior, not a gap to "restore."

**Out-of-range edge indicators.** A waypoint whose position would fall
outside the minimap's visible circle/square is redrawn clamped to the
frame's edge, using a *different* icon — a directional arrow-shaped
variant of the same waypoint's icon (falling back to a plain generic
arrow glyph if no arrow variant exists for that icon). Unlike the in-range
marker, **this arrow is deliberately *not* counter-rotated** — it's the
one element whose whole purpose is rotating to point toward the
waypoint's true bearing, so the counter-rotation step is simply skipped
for it. (The one exception: a temporary "ping"/highlight marker, drawn
with its own distinct crosshair icon, *does* stay counter-rotated/upright
even when clamped to the edge — upright-ness there matters more than
directionality, since its role is "look, right here," not "somewhere off
that way.")

**Edge-clamp geometry differs by frame shape**, consistent with the
square-vs-round distinction already established generally:

- **Round frame:** a single radius threshold; once a marker's distance
  from center would exceed it, its rendered distance is clamped to a
  fixed value just inside the frame's visible edge (not proportionally
  rescaled — every clamped marker on a round frame sits at the *same*
  fixed radius, only its angle varies).
- **Square frame:** an axis-aligned (box) threshold on each of the two
  screen axes independently; once either axis's offset would exceed it,
  *both* axes are rescaled proportionally (preserving the true bearing
  direction) so the marker lands just inside the box's edge — meaning a
  marker clamped near a corner sits farther from center than one clamped
  near an edge midpoint, tracing the square's actual outline rather than
  an inscribed circle.

```
# conceptual clamp for a marker whose unclamped screen offset is (dx, dy):
if shape == round:
    if hypot(dx, dy) exceeds the visible radius:
        rescale (dx, dy) to a fixed just-inside-the-edge radius, same angle
else:  # square
    if abs(dx) exceeds boxLimitX or abs(dy) exceeds boxLimitY:
        scale = boxLimit / max(abs(dx)/boxLimitX, abs(dy)/boxLimitY)  # proportional, same angle
        (dx, dy) *= scale
```

**Render-distance cutoff on the minimap.** A separate, config-driven
maximum distance (3-D straight-line; shared with the floating-sign cutoff
in the reference implementation, though there's no architectural reason
they couldn't be split into two settings) governs whether a waypoint is
drawn on the minimap **at all** — beyond that distance it disappears
completely, it does *not* fall back to an edge indicator. The edge-
indicator behavior above is purely about the minimap's own visible-radius
boundary (a function of current zoom level), which is a *different,
always-on* boundary from this config-driven cutoff. In other words: two
independent thresholds stack —

1. Config-driven max distance (blocks, optional/infinite by default):
   beyond this, the waypoint isn't drawn on the minimap in any form.
2. Minimap visible-radius boundary (a function of current zoom): within
   threshold 1 but beyond this, the waypoint is drawn as a clamped edge
   arrow instead of an in-range icon.

A highlighted/"pinged" waypoint (§5) bypasses threshold 1 entirely and
always shows, same as on the in-world sign layer.

## 8. Fullscreen/world-map interaction

**Right-click-to-create flow.** Right-clicking the fullscreen map does
**not** immediately create a waypoint. It opens a small context menu whose
contents depend on what's under the cursor:

- **Cursor over an existing waypoint:** Edit, Delete, Highlight/remove
  highlight, Teleport, Share.
- **Cursor over empty map space:** "New waypoint here" (opens the create
  form pre-filled with the clicked X/Z), Highlight (creates a temporary
  ping at the clicked coordinate without saving a waypoint), Teleport
  (directly, no form), Share (broadcasts the raw clicked coordinate).

For a newly-created waypoint from this flow, **Y is auto-derived from
whatever height data the persisted/cached map already has for that map
pixel** (the fullscreen map keeps its own explored-terrain height cache
independent of currently-loaded chunks), falling back to a fixed
mid-range default height if that location's height is unknown or invalid
for the current dimension. This means clicking a spot the player has
never actually visited but that the persisted map has previously scanned
(e.g. from a teammate's exploration, or a cached region file) still
produces a sensible ground-level Y, not just "the player's own current
Y" or a hardcoded sea-level guess.

**Marker rendering at world-map zoom.** Icons render at a **fixed pixel
size on screen regardless of the map's current zoom level** — zooming the
fullscreen map in/out changes how much world-space each marker's position
represents, not the marker's own on-screen size.

**Labels are not a hover-only or zoom-dependent affordance here** — this
is a meaningful correction to the intuitive assumption. A single global
"show waypoint names" toggle controls whether every in-view, non-edge-
clamped marker shows its name label *continuously*, independent of zoom
level and independent of mouse hover. Hovering a marker does two other
things instead: it shows a small coordinate tooltip (X/Y/Z, not the name)
and changes the cursor, and it's what makes that marker the target of the
right-click context menu. Edge-clamped (off-viewport) markers never show
name labels regardless of the global toggle, matching the minimap's
edge-indicator behavior in spirit.

**Off-viewport markers** are, by a separate toggle ("show distant
waypoints," default on), either clamped to the visible viewport's edge
(box-clamped, same proportional-rescale idea as the minimap's square
frame in §7) using the same directional-arrow-icon convention as the
minimap's edge indicators, or — with that toggle off — not drawn at all
once outside the viewport, with no edge indicator fallback.

**No rendering-path distinction between normal and death waypoints on
this screen** (or anywhere else, per §4): a death point is drawn through
the exact same marker code as any other waypoint, differing only in its
stored icon and color, both of which were simply chosen at creation time.
There is no death-specific branch, badge, or special treatment beyond
"it happens to look like a skull and starts out pale."

**A genuine asymmetry vs. the minimap/in-world layers:** the fullscreen
map does **not** hide disabled waypoints — it draws them at a reduced
alpha (roughly 30%, same treatment as an out-of-cone floating sign) rather
than skipping them, whereas the corner minimap and in-world beam/sign
layers hide a disabled waypoint outright (§1, §6, §7). Whether to
replicate this exact asymmetry or unify the behavior across all render
surfaces is a reasonable product decision either way; it's called out here
so it's a deliberate choice rather than an accidental divergence between
screens.

## Confidence notes

- The world-identity derivation (§2: save-folder name for singleplayer;
  `host:port`/LAN-name/realm-id for multiplayer) and its edge cases
  (address drift, LAN-name collisions) are read directly from the code
  with high confidence on the mechanism; how often those edge cases
  actually bite real users in practice wasn't independently measured.
- The sub-world auto-detection heuristic (terrain-similarity matching
  against cached region snapshots, §2) is read directly from the code and
  is high-confidence as a description of *that build's* behavior, but it
  depends on a companion server-side protocol and a persisted terrain
  cache that are both out of scope to reproduce faithfully in a clean-room
  first pass — treated here as an optional future enhancement, not a
  launch requirement.
- The "retention rename/retire logic scans the whole world/server
  collection, not just the current dimension" finding (§4) is read
  directly from the code (no dimension/sub-world filter present in that
  loop) and is high-confidence as *current reference behavior*, but reads
  like an oversight rather than an intended design point — flagged with an
  explicit recommendation to scope it per-dimension instead.
- The unbounded growth of "keep history" death-point retention (§4, no
  cap constant found anywhere in the settings or manager code) is
  high-confidence as *absence of evidence* — a thorough search found no
  cap — but absence of a cap in the searched source doesn't prove no cap
  exists anywhere in the full application if some other layer (e.g. a
  save-file size limit) incidentally bounds it in practice.
- The exact aiming-cone angle formula and edge-clamp pixel constants (§6,
  §7) are transcribed faithfully from the constants used in the reference
  build. They're tuned to that build's specific on-screen sizes; treat
  their *existence and rough shape* as reliable and the exact numbers as a
  reasonable starting point rather than a hard requirement, consistent
  with how the sibling zoom/rotation specification treats its own tuned
  constants.
- The built-in icon count (~30) was counted from the reference build's
  icon asset folder rather than from any code-level constant, so treat it
  as an approximate, illustrative figure describing the *scope* of a
  built-in icon set rather than an exact number to match.
- The "no fixed color palette, continuous HSV picker only" finding (§1,
  §5) is read directly from the picker implementation and is high
  confidence — there is no swatch-grid code path anywhere in the color-
  selection UI, only the two continuous-HSV layouts described.

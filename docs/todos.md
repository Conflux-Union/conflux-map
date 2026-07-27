# TODO

Backlog distilled from community feature requests. The original list was
collected on 2026-07-24 and the nine-proposal update below was accepted for
tracking on 2026-07-27.

## Feature proposals

### 1. Server chunk-load-state overlay - completed

Add a fullscreen-map display mode that shows which chunks the server currently
keeps loaded. This combines the multiplayer loading-range, chunk-ticket-level,
and server chunk-load-state requests into one server-authoritative feature.

#### Required behavior

- Add one server companion config switch controlling whether load-state data may
  be exposed. It must default to `false` because loaded chunks reveal player
  activity and long-running farms.
- When disabled, do not advertise the capability or send load-state payloads.
  The client must not attempt to infer other players' load ranges locally.
- Render state per chunk on the fullscreen map.
- Let each client choose between exact ticket-level detail and a simplified set
  of load-state bands. The server controls access to the data, not the client's
  presentation choice.
- Provide a readable legend. Chunk fills, range outlines, and ticket-level
  labels are all valid presentation tools; choose the combination that remains
  legible at the current zoom instead of requiring all three at once.
- Integrate this into the fullscreen display-mode selector described in item 3:
  normal terrain, chunk load state, and biome.

The existing companion handshake and hostile-input-safe protocol are the
boundary for this feature. Updates must be bounded and incremental rather than
re-sending an unbounded server-wide chunk set every frame.

### 2. Drawing and annotation tools - completed

Add a purely client-side annotation layer edited from the fullscreen map.

#### Required behavior

- Tools: line, circle, rectangle, freehand brush, and color selection.
- Optional text attached to a shape.
- Store geometry in world coordinates so it pans and scales with the map.
- Select an existing shape and move, recolor, or delete it.
- Offer local persistence as an option. Persisted drawings must be namespaced by
  world/server identity and dimension and survive leaving and rejoining.
- Render drawings on the minimap HUD as well as the fullscreen map, with a
  client setting that disables the HUD copy.
- Keep drawings private and local. Do not add sharing or companion protocol
  messages as part of this item.

Model the annotation, geometry, style, label, and persistence choice separately
from screen widgets so editing, storage, fullscreen rendering, and HUD rendering
share one deterministic data model.

### 3. Biome rendering mode

Add a client-selectable rendering mode where every biome is filled with one
solid color instead of the normal block/surface terrain colors. Switching modes
must take effect without leaving the world or rebuilding unrelated map state.

The fullscreen map will expose three display modes:

1. Normal terrain.
2. Server chunk load state, available only when item 1 is permitted by the
   companion.
3. Biome colors.

Current captured columns persist biome tint ARGB but not biome identity. A solid
per-biome palette therefore needs a stable biome-identity plane in captured and
cached map data; it must not guess the biome from tint color. Version the disk
format and migrate or safely invalidate older entries when that plane is added.

### 4. Server policy for entity radar - completed

Completed with the default-on `allowEntityRadar` server companion setting. The
handshake carries a backward-compatible negative policy flag, so older servers
preserve the existing radar behavior while cooperating clients connected to a
server that forbids radar suppress both scanning and rendering. The radar
settings page keeps the player's saved choices visible, disables every radar
control, and explains that the server operator must enable the feature.

This remains a policy for this mod's cooperating clients, not an anti-cheat
claim against modified clients. Changes to `server.json` take effect when the
server starts and are advertised to each new connection.

### 5. Modernize settings and waypoint management

Redesign both `ConfigScreen` and `WaypointListScreen` around one consistent
visual system. Choose one direction before implementation; do not ship two
independent permanent screen systems.

#### Option A: modern two-column layout

- Fixed navigation sidebar on the left.
- Active settings category or waypoint list on the right.
- Visual direction comparable to Reese's Sodium Options.

#### Option B: vanilla/Xaero-style layout

- Use the visual language of vanilla Minecraft option screens and Xaero's map
  interfaces.
- Keep navigation and controls familiar to players who avoid custom UI styles.

Whichever direction is selected must cover both screens, retain keyboard and
mouse operation, and remain usable at small window sizes and non-default GUI
scales.

### 6. Free minimap positioning - completed

Completed on 2026-07-27 with an explicit placement screen opened from the
minimap settings. Positions are stored as normalized screen coordinates and
resolved against the current scaled window, so dragging remains stable across
window resizing and GUI-scale changes.

The completed implementation also preserves the four legacy corner choices
during the schema-v1 migration, clamps invalid or off-screen positions, and
temporarily reduces the rendered size when the scaled window is too small to
keep the configured minimap fully visible.

### 7. Optional MaliLib keybind configuration - completed

Replace the in-tree modifier-key proposal with optional MaliLib integration.

- Without MaliLib, keep every Conflux Map keybind in vanilla's controls screen,
  preserving the current behavior and defaults.
- With MaliLib installed, leave only one Conflux Map entry/hint in vanilla's
  controls screen and manage the remaining bindings through MaliLib's hotkey
  UI.
- MaliLib must remain optional: the mod must load and all actions must remain
  configurable when it is absent.
- Both backends must invoke the same action handlers so installing MaliLib does
  not change map behavior.

Completed on 2026-07-27. All eleven gameplay actions now come from one action
registry and one handler. Without MaliLib they retain their existing vanilla
bindings and defaults. With MaliLib they are registered in its global hotkey UI,
stored in a separate Conflux Map hotkey file, and may use multi-key combinations;
vanilla Controls contains only an unbound shortcut to that UI. MaliLib is a
compile-only suggested dependency and is not bundled or required at runtime.

### 8. Structure icon layer and per-type filters — completed

Completed on 2026-07-27 as a seed-calculator-style map layer built on the
existing structure-marker foundation.

The completed implementation has built-in cubiomes candidate lookup,
persistent candidate state, a master `predictionShowStructures` toggle, and:

- Recognizable per-type icons backed by Minecraft's runtime item and block
  textures, without copied assets or an external icon dependency.
- Per-structure-type visibility controls alongside the master switch.
- Persistent filter profiles scoped by Minecraft version and dimension; the UI
  only offers structures available in the active profile.
- Distinct candidate and server-verified icon frames and tooltips.

Use the mod's existing internal cubiomes/companion data path. Seed-calculator
software is only a visual reference, not a runtime dependency or external data
source.

### 9. Preserve high-resolution captured data while zooming

For chunks that have already been captured at a fine LOD, render from the
finest available real map data and scale it down at wider zoom levels instead of
switching those chunks to a separately composed coarse-LOD tile.

- Apply this preference to captured/actual map data; prediction may retain its
  own LOD-aware sampling path.
- Fall back to the best available coarser data where fine data is missing, so
  partially explored areas still render.
- Avoid seams where fine and coarse sources meet and preserve unknown-pixel
  transparency over the prediction underlay.
- Keep viewport scheduling, GPU cache use, and draw-call count bounded for large
  zoomed-out views.

The result should behave like traditional map-image scaling for explored
terrain while retaining the current sparse/coarse path as a fallback for data
that was never captured in detail.

## Existing confirmed backlog

### Nether bedrock-ceiling layer split

This merges three requests: automatically ignore the Nether bedrock ceiling,
record the spaces above and below it independently, and toggle the view with a
keybind.

The Nether has `height=256` but `logical_height=128`, so generated terrain ends
at y=127 and the bedrock ceiling caps it around y=124-127. Both current Nether
scan paths stop on the top face of that cap, producing a flat grey map while the
player is on the roof.

#### Required behavior

- Add a bedrock-skipping scan mode so terrain below the cap remains visible from
  the roof.
- Keep roof-surface and below-cap views as independently persisted layers.
- Use the existing `cycle_layer` keybind to cycle the two states.
- Give both layers distinct cache IDs and confirm they cannot share a region
  file.

Primary seams: `McChunkSnapshotFactory#sampleFloorColumn`, `MapLayer`,
`LayerSelector`, `RegionCacheService`, and `RegionDiskCache`.

### Map image export

Export the current world-map view as a PNG. Tile pixel data and
`compat/NativeImages` already exist, so write the image in bounded chunks rather
than materializing an unbounded explored world in one heap buffer.

Implementation choices still to settle are export at the on-screen LOD versus
always LOD 0, and viewport export versus a user-selected rectangle.

## Confirmed bugs

### Bug 1. Fullscreen zoom label shows the inverse meaning

Affected release: `0.1.0-beta.5`.

`FullscreenMapScreen#drawScaleLabel` currently prints the internal `scale`
field directly. That field is blocks per screen pixel, so it increases as the
player zooms out even though users read the label as a zoom multiplier.

Display `1.0 / scale` as the user-facing multiplier instead. The zoom-out
sequence should read `1.00x -> 0.25x -> 0.0625x`, not `1 -> 4 -> 16`. Keep the
internal blocks-per-pixel value and all viewport/LOD math unchanged; only the
presentation and its regression test should change.

### Bug 2. Large player-built structures do not sync at low zoom

Affected release: `0.1.0-beta.5`.

At `scale=16` blocks per pixel (the corrected label is `0.0625x`, LOD 4), a
large artificial structure can cover enough area to be visible but still fail
to appear correctly through companion synchronization.

The first investigation target is the current LOD gate: `MapSyncClient` does
request coarse viewports, but `PatchBuilder.MAX_SUPPORTED_LOD` and the default
`ServerConfig#maxPatchLod` stop full correction patches at LOD 2. Higher LODs
receive presence-only data, which cannot describe player-built surface changes.
Verify this with a focused LOD-4 server/client regression before choosing the
fix; do not treat generated-chunk presence as equivalent to synchronized column
data.

The fix must make captured/synchronized construction visible at the widest
supported zoom, refresh an already visible coarse view after source chunks
change, and preserve bounded server work. Test both a large contiguous footprint
and a footprint crossing coarse tile boundaries.

## Deferred

Parked because the request still does not identify missing behavior.

| Request | Missing |
|---|---|
| Entity head icons shown by default | `ConfluxConfig#radarIconsEnabled` already defaults to `true`; the visible defect or missing entity category is not identified. |

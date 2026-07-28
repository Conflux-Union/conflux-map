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

### 3. Biome rendering mode - completed

Completed on 2026-07-27 with a client-selectable rendering mode where every
biome is filled with one stable solid color instead of the normal block/surface
terrain colors. Switching modes takes effect immediately without leaving the
world or rebuilding unrelated map state.

The fullscreen map will expose three display modes:

1. Normal terrain.
2. Server chunk load state, available only when item 1 is permitted by the
   companion.
3. Biome colors.

Captured columns now carry the biome resource identifier through the in-memory
store and a dictionary-encoded disk plane. Cache schema 3 safely quarantines
older entries that lack biome identity instead of guessing from tint color.
Captured and cubiomes-predicted tiles use separate biome texture keys, so the
fullscreen mode does not change the minimap's terrain rendering.

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
- With MaliLib installed, register Conflux Map in MaliLib's config switcher and
  manage its bindings through a dedicated MaliLib hotkey screen. Keep one
  vanilla compatibility shortcut only on versions whose MaliLib predates the
  config-screen registry API.
- MaliLib must remain optional: the mod must load and all actions must remain
  configurable when it is absent.
- Both backends must invoke the same action handlers so installing MaliLib does
  not change map behavior.

Completed on 2026-07-27. All eleven gameplay actions now come from one action
registry and one handler. Without MaliLib they retain their existing vanilla
bindings and defaults. With MaliLib they are registered as a dedicated Conflux
Map config screen, stored in a separate Conflux Map hotkey file, and may use
multi-key combinations. Minecraft 1.21.1 and newer expose that screen through
MaliLib's A+C config switcher without adding a vanilla keybind; 1.17.1 retains
an unbound compatibility shortcut because its MaliLib API has no config-screen
registry. MaliLib remains a compile-only suggested dependency and is not bundled
or required at runtime.

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

### 9. Preserve high-resolution captured data while zooming — completed

For chunks that have already been captured at a fine LOD, render from the
finest available real map data and scale it down at wider zoom levels instead of
switching those chunks to a separately composed coarse-LOD tile.

- Apply this preference to captured/actual map data and to prediction results
  whose lower-LOD cache is complete and current.
- Fall back to the best available coarser data where fine data is missing, so
  partially explored areas still render.
- Avoid seams where fine and coarse sources meet and preserve unknown-pixel
  transparency over the prediction underlay.
- Keep viewport scheduling, GPU cache use, and draw-call count bounded for large
  zoomed-out views.

The result should behave like traditional map-image scaling for explored
terrain while retaining the current sparse/coarse path as a fallback for data
that was never captured in detail.

Completed on 2026-07-27. Coarse real tiles are composed by alpha-weighted
downsampling of the finest resident LOD-0 regions. A coarse viewport now pulls
its covered persistent LOD-0 cache regions through a bounded 64-read queue, and
one region merge invalidates the affected LOD parents as a batch. Missing fine
regions remain unclaimed and transparent, so the existing prediction/correction
underlay remains the fallback without adding per-region draw calls.

Prediction now keeps a bounded CPU LRU of recent composed tiles. A coarser tile
is reconstructed recursively from four complete lower-LOD children with the
same alpha-weighted 2x2 filter, including their committed correction pixels and
cursor metadata. A child update invalidates every cached ancestor. A bounded
background reducer also streams persisted lower-LOD corrections into parent
mips, so direct LOD0-to-LOD4 reuse does not require all 256 children to fit the
64-tile CPU LRU. On viewport entry, network sync skips a coarse tile only when
every contributing child has a final server validation no older than 30 minutes.
Expiry is checked lazily on a later viewport entry and never starts background
polling. Missing, expired, future-dated, progressive, or server-invalidated
entries use the normal coarse request path.

## Confirmed bugs

### Bug 1. Fullscreen zoom label shows the inverse meaning — completed

Affected release: `0.1.0-beta.5`.

Fixed on 2026-07-27. The fullscreen label now displays `1.0 / scale` as a
user-facing multiplier with stable precision, so the zoom-out sequence reads
`1.00x -> 0.25x -> 0.0625x`. The internal blocks-per-pixel value and all
viewport/LOD math remain unchanged, and focused regression coverage locks down
the presentation boundary.

### Bug 2. Large player-built structures do not sync at low zoom — completed

Affected release: `0.1.0-beta.5`.

At `scale=16` blocks per pixel (the corrected label is `0.0625x`, LOD 4), a
large artificial structure can cover enough area to be visible but still fail
to appear correctly through companion synchronization.

The cause was the LOD gate: coarse requests above LOD 2 received generated
presence but no column corrections. The operator-configurable correction and
presence ceilings have been removed; every supported map LOD now carries full
correction data.

The fix must make captured/synchronized construction visible at the widest
supported zoom, refresh an already visible coarse view after source chunks
change, and preserve bounded server work. Test both a large contiguous footprint
and a footprint crossing coarse tile boundaries.

LOD 3-4 use a shared progressive scan. Cold source data is opened once per
32x32-chunk Anvil file and scanned by two background workers; four 16x16 summary
regions share that result instead of issuing up to 65,536 individual Minecraft
chunk-storage futures for one LOD-4 tile. When the bundled native is available,
its selective NBT parser retains only status/revision, heightmaps, section
palettes and biomes, skipping entity, structure, tick and lighting payloads
without constructing a full Java NBT tree. The Java parser remains the fallback.
Both paths retain only the four centered columns per chunk visible at LOD3 or
the single centered column visible at LOD4. Completed Anvil batches remain in
the tile task across event-driven restarts, so an invalidation reuses every
unchanged file and rescans only files whose mtime changed; live summaries are
overlaid at consumption time. The 2,048-unit/4ms server-tick slice now only
accepts completed batches and validates source stamps. Baseline sampling and
patch encoding use their own daemon worker. Replaceable revision-0 snapshots
report progress without changing the drawable committed tile; only a final
snapshot is applied atomically. Completed tiles remain silent. A capability-negotiated
viewport subscription lets the server push a bounded tile-invalidation batch
only after a watched source region changes; the client then requests that tile
once with its committed revision. Reusable `.cfs` and task-local Anvil summaries
require the current `.mca` mtime,
live summaries take priority, and every coarse source mtime/live epoch is
revalidated before a final result is reused. Regression coverage includes a
contiguous 128x128-chunk LOD-4 build and a build crossing an LOD-4 tile boundary.

When a coarse prediction is fully reconstructible from fresh lower-LOD client
tiles, the client omits that tile from `MAP_VIEW_REQ`; the server performs no
summary scan, baseline sampling, patch encoding, or response for it. The oldest
contributing final validation controls the 30-minute viewport-entry freshness
check, while server invalidations persistently expire every overlapping LOD cache.
Unchanged revalidation returns the existing content fingerprint with no patch body.
LOD3-4 progress responses are bodyless and retry at two-second intervals; the
authoritative patch is encoded once after the scan and validation pass complete.

A later chunk-range follow-up removes the remaining fixed-tile overfetch. Capable
clients derive an exact half-open chunk viewport from the fullscreen bounds, split
it into cropped 16x16-chunk summary-region pages, and request no more pages per
message than the negotiated server budget. At LOD4, one edge chunk is one sampled
column rather than a complete 256x256-pixel correction tile. The page codec keeps
the existing LOD sample density and exact correction fields while adaptively
encoding generated, evaluated, and difference masks before bounded Deflate.

Cold page scans parse only the requested chunk crop from the MCA location table.
LOD2-4 Overworld baseline prediction samples only the page's output window; page modes whose
baseline crosses that window use exact absolute fields instead of computing a whole tile.
Exact per-chunk fingerprints and validation times are persisted in correction
format v16; v15 pixels remain drawable but require revalidation. Event-driven
invalidations retain the exact viewport subscription and stale only the visible
crop of a changed summary region. Older companions remain on the previous
capability-gated tile/progressive path.

## Deferred

Parked because the request still does not identify missing behavior.

| Request | Missing |
|---|---|
| Entity head icons shown by default | `ConfluxConfig#radarIconsEnabled` already defaults to `true`; the visible defect or missing entity category is not identified. |

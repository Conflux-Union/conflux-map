# Map Teleport Y Handling (third-party reference)

How two reference minimap/world-map mods resolve the Y coordinate when the
player teleports by clicking on the map, and the surrounding behavior:
trigger UIs, permission gating, command shapes, and cross-dimension
handling. Behavior description only, in this project's own words; numeric
constants are included because they are behavior.

Sources examined (2026-10-09):

- Xaero's Minimap 23.9.7 + Xaero's World Map 1.37.8, Fabric 1.17.1 builds,
  decompiled for reading.
- VoxelMap-Updated source clone (community multi-loader continuation of
  VoxelMap).

## 1. Trigger UIs

Xaero's:

- The fullscreen world map is the only click-teleport surface: right-click
  and release opens a context menu with a "Teleport Here" entry. The minimap
  HUD itself has no click-teleport.
- Hovering a waypoint or a radar-tracked player on the world map and
  pressing T teleports to it/them.
- The waypoint management screen has a "Teleport (T)" button.

VoxelMap:

- Right-click on the fullscreen map opens a context menu with a "Teleport
  To" entry (to the clicked position, or to a hovered waypoint).
- The waypoint manager has a "Teleport To" button.
- A chat shorthand `ztp <waypoint name>` is intercepted client-side and
  teleports to the named waypoint (same-dimension/world waypoints only).

## 2. Permission gating

Neither mod uses custom packets or a server handshake for teleporting; the
vanilla server's own command permission is the real gate. Client-side they
differ:

- Xaero's sends plain chat commands. Client-side, in non-creative
  (survival-like) game modes it additionally refuses to teleport when the
  target Y is unknown (menu item greyed, "ambiguous Y") and, for
  cross-dimension teleports, requires that the destination map layer has
  been confirmed/connected (chat errors otherwise). Creative skips all
  client-side checks. A per-world client toggle (default on) can disable
  teleportation entirely; the tooltip warns it is only recoverable by
  editing the world's config files.
- VoxelMap gates only the waypoint-screen button: on an integrated
  (singleplayer) server it checks operator status, falling back to the
  world's allow-commands flag; on dedicated multiplayer it always allows and
  relies on the server refusing the command. The map right-click menu entry
  has no local gating at all. A server plugin can push a
  teleport-command-template override that supersedes the local one for the
  session.

## 3. Command shapes

Both send chat commands; neither uses custom packets.

- Xaero's uses per-world user-editable templates with placeholders.
  Defaults: same dimension `/tp @s {x} {y} {z}`, cross-dimension
  `/execute as @s in {d} run tp {x} {y} {z}` (the dimension format is
  auto-derived from the normal one on save), players `/tp @s {name}`. `{d}`
  becomes the full dimension id (e.g. `minecraft:the_nether`). A
  rotation-aware waypoint variant appends `{yaw} ~`.
- VoxelMap uses one user-editable template, default `tp %p %x %y %z` with
  `%p` the player name. X and Z are block coordinates plus 0.5 (block
  center); Y is substituted with no offset. No rotation and no dimension
  part exist.

## 4. Y sourcing for click-teleport (the core)

Both mods take Y from their map cache, not from the live world and not from
the player's current Y. The caches already store a per-column height with
"standable feet Y" semantics, so no +1 is needed at teleport time.

Xaero's:

- The map writer stores two per-column heights: the topmost opaque
  rendered block, and the topmost transparent cover above it (water
  surface, tree canopy). Missing data is a 32767 sentinel. An option
  (default on) lowers the effective height of carpet-like blocks
  (carpets, single snow layers, lily pads) by 1.
- At the cursor, the opaque height is used; if a transparent cover exists
  above it and its height is known, the cover height wins (you teleport
  onto the water/canopy surface); if it exists but is unknown and the
  "detect ambiguous Y" option is on (default), the column counts as
  unknown.
- On teleport the height is used as `blockY + 1` (feet position). When
  sent, an unknown Y becomes the literal `~` — the server keeps the
  player's current Y and resolves the new column. A known Y is sent as
  `y + 0.5` by default (the "partial Y" option; the rationale given in the
  tooltip is avoiding clipping into carpet-like blocks), so a typical
  command reads `/tp @s -123 65.5 456`. The same `+1` feet value is used
  when creating a waypoint at the clicked spot.
- Unknown Y and non-creative mode never reaches the send: the menu item is
  disabled. Uncached columns, cave layers, and the Nether all just read
  whatever height the currently viewed map layer cached; there is no
  per-dimension Y algorithm and no world-bounds clamping at send time.

VoxelMap:

- The region cache stores one signed 16-bit height per column. In surface
  mode it is the first light-blocking block found scanning down from the
  motion-blocking heightmap top: i.e. one above the top opaque block, a
  directly standable Y (water columns store surface + 1).
- In underground/Nether mode a dedicated scan runs: from Y=80, if that cell
  is open, scan down to the first blocking or lava block and store `y + 1`
  (the floor under the Nether roof); if Y=80 is solid, scan up at most to
  Y=90 for an open cell and store that Y; otherwise store the missing
  sentinel. Absent or empty regions return -32768.
- A live query exists only as a legacy repair: when underground mode reads
  a stored 255 (an old "unset" sentinel) it force-loads the chunk and
  scans around Y=64 for a cell with support below and two open cells
  above (Nether-like dimensions), or falls back to the no-leaves
  motion-blocking heightmap + 1 (normal dimensions); failure returns -1.
- At action time, any Y below the world's minimum (all sentinels included)
  falls back to: the world's maximum Y for dimensions without a ceiling,
  or the constant 64 for ceiling dimensions (the Nether). X/Z get the
  +0.5 centering; Y is an integer with no further offset or clamping.

## 5. Waypoint teleport Y

- Xaero's uses the waypoint's stored Y as-is (waypoints store a feet Y),
  optionally +0.5 via the same "partial Y" option. A 2-D waypoint without
  a Y is an error in non-creative mode (aborts with a chat message) and
  `~` in creative.
- VoxelMap uses the waypoint's stored Y as-is with no adjustment or
  validation; if it is at or below the world minimum, the same
  max-Y/constant-64 fallback applies. Neither mod snaps waypoints to the
  surface.

## 6. Cross-dimension handling

- Xaero's world map: teleporting while viewing another dimension's layer
  switches to the dimension command template; Y handling is unchanged and
  coordinates are never scaled by dimension ratios. Waypoint teleports
  across worlds prefix `execute in <dimension> run` (without `as @s`) and
  strip the `minecraft:` namespace; when the viewing dimension has a
  different coordinate scale, X/Z are divided by the scale ratio — Y is
  never scaled.
- VoxelMap has no cross-dimension teleport at all. Waypoints store master
  (Overworld-scale) coordinates, and reading them back divides X/Z by the
  current dimension's coordinate scale — so teleporting to a foreign
  waypoint actually sends same-dimension equivalent coordinates. Y is
  never scaled. The only admin lever for cross-dimension commands is the
  server-pushed template override.

## 7. Takeaways for our behavior

- Both mods prefer the map cache over live queries, because the click
  target is usually far from loaded chunks. Live heightmap queries only
  work where chunks are loaded, or require force-loading like VoxelMap's
  legacy repair.
- Cached heights are stored with standable-feet semantics at write time,
  so teleport-time math is just substitution; Xaero's adds +0.5 as
  suffocation insurance for thin blocks.
- The interesting divergence is the unknown-Y policy: Xaero refuses in
  non-creative and otherwise sends `~` (server-side resolution, player
  keeps their Y); VoxelMap guesses — top of the world, or 64 under a
  ceiling. Ours currently samples the live motion-blocking heightmap +1
  and falls back to the player's Y when the column is unavailable, which
  is more often correct at send time but only where data is loaded or
  sampled.

# Xaero's World Map Region Data Format (Read-Only Import)

On-disk terrain-map format of the third-party mod "Xaero's World Map", documented
for a read-only interoperability importer that decodes the user's own data files.
Described as byte-stream grammar, on-disk names, and data semantics only. No
implementation structure of the mod is described or implied.

Facts were extracted from these sources:

- **Code** — behavioral analysis of a decompile of Xaero's World Map 1.37.8
  (the Fabric build for Minecraft 1.17.1, save format 5.8). Minecraft-facing
  identifiers in the decompile are intermediary names; where a block/biome
  identity matters (air, grass block, water, plains), it was resolved against
  the official Yarn 1.17.1 mappings. Version-dispatch claims for other majors
  were checked against the same decompile's read-side branches.
- **Samples** — real region files written by Xaero 1.37.8 on MC 1.17.1 (format
  5.8; 22 regions), by Xaero 1.44.2 on MC 1.21.11 (format 7.8; 24 regions), and
  by Xaero 1.47.2 on MC 26.3 (format 8.8; 24 regions). Every sample decodes to
  the exact last byte with the grammar in sections 4–6.
- **Community references** — cross-checked, and overridden by code/samples
  wherever they disagree (discrepancies listed in section 9).

Confidence markers: **[certain]** verified against code and, where the claim is
byte-visible, against real samples; **[likely]** derived from code only, or
inferred with strong evidence; **[uncertain]** plausible but unverified.

---

## 1. Scope and legal note

This document covers the per-region terrain map data of Xaero's World Map: the
`region.xaero` byte stream (zip-packed in current releases, raw in legacy
files), its on-disk directory layout, and the semantics needed to convert a
decoded region into surface-map columns. It targets save format versions
**5.8 through 8.8** (major 5–8, minor ≤ 8), which spans Xaero's World Map
releases for MC 1.17.1 through the current 26.x line. Format **major 0** (and
streams with no version marker) store 1.12-era numeric block/biome ids and are
**out of scope**: the importer must recognize and skip them, not decode them
(section 7).

Xaero's World Map is an all-rights-reserved mod. This is a clean-room
specification: it was written by analyzing the observable behavior of the mod's
file reader/writer, not by copying its source or decompiled code. Byte-grammar
descriptions are in our own words. Literals that exist on disk (magic bytes,
NBT keys such as `Name`, `Properties`, `id`, `properties`, biome id strings,
folder names) are quoted because they are format facts. Community format notes
were used only as cross-checks.

What this document does **not** cover: waypoints, highlights, teleport/chat
config, `.xwmc` render caches (regenerable derived data — must be ignored), and
Xaero's Minimap files (covered separately in `waypoint-storage-formats.md`).

---

## 2. Storage layout

### 2.1 Data roots

- 1.37.8 (MC 1.17.1 era): `<gameDir>/XaeroWorldMap/` **[certain]**. The mod
  also migrates older roots (`<gameDir>/mods/XaeroWorldMap/`,
  `<gameDir>/config/XaeroWorldMap/`, `<config>/../XaeroWorldMap/`) into this
  root on startup if it does not exist yet **[certain]**.
- World Map ≥ 1.39.0: `<gameDir>/xaero/world-map/` with an identical internal
  layout **[community-attested; consistent with the 7.8 sample tree]**.
- Mod options live at `<gameDir>/config/xaeroworldmap.txt` (not map data).

An importer should scan **both** roots and treat each container folder as an
independent dataset.

### 2.2 Container folders (one per world/server)

The container folder name ("world id") is derived as follows **[certain for
1.37.8]**:

| Situation | Folder name |
|---|---|
| Singleplayer | the world save's folder name, with `_` replaced by `^us^` |
| Singleplayer, save name already starts with `Multiplayer_` | `^e^` + that name |
| Realms | `Realms_<owner uuid>.<realm server id>` |
| Multiplayer, address known | `Multiplayer_<address>` |
| Multiplayer, address differentiation disabled (option) | `Multiplayer_Any Address` |
| Multiplayer, no address known at session start | `Multiplayer_Unknown` |

Multiplayer address escaping **[certain for 1.37.8]**:

- The **port is stripped**: everything from the **first** `:` onward is cut
  (`127.0.0.1:25565` → `127.0.0.1`). For the root-folder name with an IPv6
  literal the cut is instead made after the final `]:`; trailing `.` characters
  are then trimmed.
- Remaining `:` characters (IPv6 leftovers) are replaced by the character
  `§` (U+00A7).
- `_` and `/` are **not** escaped in the 1.37.8 multiplayer name.
- `Multiplayer_Unknown` is real data: our 5.8 sample tree was written under
  `XaeroWorldMap/Multiplayer_Unknown/` although the client had connected to
  `127.0.0.1:25565` (the quick-play path does not populate the address field at
  session start). The 7.8 sample tree, written by a newer build that did
  resolve the address, uses `Multiplayer_127.0.0.1`. Both forms occur in the
  wild; do not treat `Unknown` as a literal server name.

Newer releases reportedly use `%us%` instead of `^us^` for underscores and add
`%fs%` / `%bs%` escapes for `/` and `\`; third-party tooling (XaeroPlus) can
also derive the container from the server-list display name or a bare domain
(e.g. `Multiplayer_2b2t.org`) and rename the overworld folder to `DIM0`
**[community-attested; uncertain — not verifiable from 1.37.8]**.

### 2.3 Dimension folders

Directly under the container folder **[certain]**:

| Dimension | Folder |
|---|---|
| Overworld | `null` |
| Nether | `DIM-1` |
| End | `DIM1` |
| Custom dimension `namespace:path` | `namespace$path` with every `/` in the path replaced by `%` |

The overworld folder is literally `null` (a valid directory name), not the
absence of a folder.

### 2.4 Multiworld layer folders

Directly under the dimension folder **[certain]**:

- Singleplayer: **no** multiworld folder (map data sits directly in the
  dimension folder).
- Multiplayer, one of:
  - `mw$default` — default layer when nothing else exists (our sample trees use
    this);
  - `mw$<integer>` — one layer per server-provided level id (Xaero server mod);
  - `mw<x>,<y>,<z>` — legacy automatic layers keyed by the spawn position
    divided into 64-block cells (spawn coordinate >> 6 per axis);
  - `cm$<name>` — user-created custom multiworld layers;
  - `cm$converted` — regions converted from world-save (singleplayer) mode.
- Folder discovery at load time accepts exactly: names matching
  `^mw(-?\d+),(-?\d+),(-?\d+)$`, names starting with `mw$`, and names starting
  with `cm$`. Anything else under the dimension folder is not map data.

### 2.5 Cave layer folders

Under the multiworld folder, cave map layers live in `caves/<n>/` where `<n>`
is a decimal layer number (e.g. `caves/1/`). The surface layer (layer number
equal to the integer sentinel "no cave layer") lives directly in the multiworld
folder **[certain]**. Cave regions use the identical region format (section
3–6); the only difference is the tile trailer's cave context (section 4).

### 2.6 Region files

In a layer folder **[certain]**:

- Current: `<regionX>_<regionZ>.zip` — a zip archive containing exactly one
  entry named `region.xaero` (deflated). The mod writes to a sibling
  `<...>.zip.temp` and then replaces the final file by a backup-then-move
  sequence, so crash windows can leave a stale `.zip.temp` or a `.zip` that is
  momentarily absent. `regionX = floor(blockX / 512)`,
  same for Z; negative coordinates use the plain minus sign (`-1_-2.zip`).
- Legacy: `<regionX>_<regionZ>.xaero` — the same byte stream, **uncompressed**,
  as a bare file. The mod auto-converts these on save by wrapping the raw bytes
  verbatim into a fresh zip entry and deleting the old file, so both forms
  carry an identical stream.
- Region file name grammar: `^(-?\d+)_(-?\d+)\.(zip|xaero)$`.

### 2.7 Files and folders to ignore

An importer must not treat the following as map data **[certain unless noted]**:

- `cache_<globalVersion>/` subfolders containing `<regionX>_<regionZ>.xwmc`
  files — regenerable render caches (internally: one `cache.xaero` entry with a
  *different*, cache-only format; version fields 1.24/2.24, not 5.8). Newer
  builds also nest `cache/<n>/` subfolders. Never import, never merge.
- `<fullVersion>_backup_<n>/` folders (e.g. `327688_backup_0`,
  `524296_backup_0`) — created when the mod encounters a region written by a
  newer format version than it supports and moves the file aside. The prefix is
  the raw 32-bit version integer, so the folder name leaks the file's version.
- `*.temp` (in-progress `.zip.temp` saves), `*.outdated` (stale caches renamed
  aside), `*.sync-conflict-*` (syncthing/sync-tool artifacts, not produced by
  the mod) **[uncertain — community practice]**.
- `dimension_config.txt` (per-dimension multiworld metadata) and
  `server_config.txt` (per-container teleport configuration) — configuration,
  not map data. `server_config.txt` exists in 1.37.8; `dimension_config.txt`
  becomes authoritative in newer releases **[likely]**.
- `<owner name>.owner` marker files (empty Realms ownership note, one per
  container folder).
- `states.dat` — legacy slot for modded block-state tables; empty/stubbed in
  1.37.8 (the folder is created but no file is written) **[certain]**.
- Vanilla world-save region files (`r.X.Z.mca`) referenced by singleplayer
  world-save mode — a different format entirely (Minecraft Anvil), unless a
  user converted them into `cm$converted`.

### 2.8 Observed sample layouts

5.8 (Xaero 1.37.8, MC 1.17.1):

```text
<runDir>/XaeroWorldMap/Multiplayer_Unknown/null/mw$default/0_0.zip
                                                  /-1_-2.zip ...
                                        /cache_1/0_0.xwmc
```

7.8 (Xaero 1.44.2, MC 1.21.11) and 8.8 (Xaero 1.47.2, MC 26.3) — identical
roots and container names:

```text
<runDir>/xaero/world-map/Multiplayer_127.0.0.1/null/mw$default/0_0.zip ...
                                                   /cache_1/*.xwmc
                                                   /caves/<n>/*.zip
```

---

## 3. Region container

- A `.zip` region file contains **exactly one** entry `region.xaero`
  (sample-verified across 70 files). Read that entry's bytes as the stream.
  Extra zip entries, if ever present, are not map data **[likely]**.
- A legacy `.xaero` file **is** the stream directly (no zip framing).
- Stream header:

| Offset | Type | Value |
|---|---|---|
| 0 | u8 | `0xFF` — version marker |
| 1 | i32 (big-endian) | `(major << 16) \| minor` |

  Format 5.8 writes `ff 00 05 00 08` (integer 327688); 6.8 → `ff 00 06 00 08`;
  7.8 → `ff 00 07 00 08`; 8.8 → `ff 00 08 00 08`.

- If the **first byte is not `0xFF`**, the stream is unversioned and uses
  major-0 semantics (numeric 1.12-era block ids) — the importer must skip it
  **[certain]**. (One community doc describes a two-byte `00 ff` prelude; that
  is not what 5.x–7.x files contain — byte 0 is `0xFF`, full stop.)
- Special case: major 2 with minor ≥ 5 carries one extra flag byte (value 1 =
  1.15-era colors) immediately after the version integer **[certain]**; only
  relevant to readers of major-2 files.

---

## 4. Stream framing

All integers are big-endian. Strings use Java's `writeUTF` framing: u16 byte
length followed by modified UTF-8 (MUTF-8; NUL encoded as `C0 80`, supplementary
characters as CESU-8-style surrogate pairs). Biome ids are ASCII in practice.

After the header, the stream is a sequence of **chunk blocks** terminated only
by end-of-stream (there is **no terminator**; the mod reads until EOF and
tolerates truncation mid-structure):

```text
region := header, { chunkBlock }, EOF
chunkBlock := u8 chunkMarker, 16 × tile
chunkMarker := (chunkX << 4) | chunkZ        ; chunkX, chunkZ ∈ 0..7
tile := i32(-1)                              ; absent tile
      | pixel×256, u8 interpVersion, i32 caveStart, u8 caveDepth   ; present tile
pixel := <section 5/6>
```

Details and semantics:

- **Hierarchy/geometry.** A region covers 512×512 blocks: 8×8 tile-chunks
  (chunk marker = chunk position inside the region), each tile-chunk 4×4 tiles
  (a tile is 16×16 blocks = one Minecraft chunk), each tile 16×16 pixels (one
  pixel = one block column). Block coordinates:
  `blockX = regionX·512 + chunkX·64 + tileX·16 + x`,
  `blockZ = regionZ·512 + chunkZ·64 + tileZ·16 + z`, where `tileX/tileZ` are
  the 0..3 tile indices inside the chunk. **[certain]**
- **Pixel order within a tile** is x outer (0..15), z inner (0..15) — z is the
  fastest-varying coordinate. **[certain, sample-verified]**
- **Chunk marker byte.** The high nibble is the chunk's x index, the low nibble
  the z index (values 0..7; bits 3 and 7 of the byte are always 0). The mod
  writes markers in ascending `(chunkX, chunkZ)` scan order, but the reader
  places chunks by marker value, so an importer should accept any order.
  Sample-verified: all 64 possible marker values appear across the corpus,
  strictly ascending. *Reconciliation note:* the "literal byte 4" occasionally
  reported from writer bytecode is the `<< 4` **shift constant** of the marker
  encoding, not a per-chunk field. **[certain]**
- **Tile marker.** Exactly 16 tile fields follow each chunk marker, tile order
  fixed (tileX 0..3 outer, tileZ 0..3 inner). `i32 -1` = absent tile. For a
  present tile the marker integer **is the first pixel's params value** — it is
  consumed as such. This works because a real pixel's params never equals −1
  (it would require every flag bit set simultaneously). The `-1` fields are
  per-tile, not a stream terminator. **[certain, sample-verified]**
- **Absent chunks.** A chunk with nothing to save produces no marker at all —
  absent chunks are simply not in the stream. An importer must model all 64
  chunk slots as absent by default. **[certain]**
- **Tile trailer** (present tiles only, after the 256 pixels):
  1. `u8 worldInterpretationVersion` (minor ≥ 4). Currently always 1. It marks
     tiles written with top-height semantics; 0 would mean "written by an older
     interpretation" and makes top-height data unreliable. Treat `0` as "no
     top-height information".
  2. `i32 caveStart` (minor ≥ 6). The cave context in effect when the tile was
     written: `2147483647` (`0x7FFFFFFF`) = surface/main layer data (this is
     what all our surface-layer samples contain); `-2147483648` = full-height
     cave data (nether-style); any other value = a cave layer whose view starts
     at that Y.
  3. `u8 caveDepth` (minor ≥ 7; readers of minor 6 substitute the legacy
     default 32). Depth (in blocks) of the cave view below `caveStart`.
     Surface-layer sample files carry the option default 30.
  **[certain]**
- **Palettes.** Two per-stream palettes, both empty at stream start and never
  stored in the file:
  - a **blockstate palette** shared by pixel blockstates *and* overlay
    blockstates;
  - a **biome palette**.
  A value's first occurrence is stored inline (with a flag bit, section 5) and
  appended to the palette; later occurrences store the `i32` palette index.
  Index = order of first inline occurrence. Palettes are scoped to the stream
  (one zip entry) — bytes must never be interpreted across region files.
  **[certain]**
- **Inline blockstate encoding** is an uncompressed Java NBT named compound, as
  produced by the vanilla block-state serializer: tag type `0x0A`
  (TAG_Compound), u16 root-name length (empty name), then the compound payload.
  - major < 8: keys `Name` (TAG_String, e.g. `minecraft:stone`) and, when the
    state has properties, `Properties` (TAG_Compound of TAG_String values).
  - major 8 (MC 26.3): the same structure with keys `id` and `properties`
    (`properties` present only for states that have them).
  **[certain, sample-verified for both key spellings]**
- **Inline biome encoding**: `writeUTF` string of the biome id, e.g.
  `minecraft:birch_forest`. **[certain, sample-verified]**
- **Truncation.** A stream that ends mid-tile is tolerated by the mod (the
  partially loaded region is used/discarded without crashing). Importers should
  flag truncation rather than fail the whole scan. **[certain]**

---

## 5. Pixel encoding

Each pixel starts with a signed 32-bit params bitfield (read as unsigned bits):

| Bits | Meaning (format 5.8, minor 8) |
|---|---|
| 0 | blockstate follows (0 = grass surface, no state bytes) |
| 1 | overlay list follows |
| 2–3 | legacy color type (read only for minor < 5, or major ≤ 2 without the 1.15 flag; type 3 = `i32` custom color follows) |
| 6 | height as a separate u8 (legacy writer form; never set by 5.x+ writers, read-side only) |
| 8–11 | light, 0–15 |
| 12–19 | height, low 8 bits |
| 20 | biome follows |
| 21 | blockstate is inline (new blockstate-palette entry) |
| 22 | biome is inline (new biome-palette entry) |
| 23 | biome is inline as an `i32` registry id (read-side branch only; never set by current writers) |
| 24 | top height byte follows (top height ≠ height); minor ≥ 4 only |
| 25–28 | height, high 4 bits (minor ≥ 4; for minor < 4 these sit at bits 24–27) |

Payload fields are read in a fixed order, each only if its flag is set
**[certain]**:

1. **Blockstate** (bit 0 set): bit 21 set → inline NBT compound (section 4),
   appended to the blockstate palette; bit 21 clear → `i32` blockstate palette
   index. Bit 0 **clear** → no bytes; the pixel is a *grass surface* (see
   below).
2. **Height**: bit 6 set → `u8` (legacy). Otherwise the signed 12-bit height is
   taken from the params itself: `h = bits(12..19) | bits(25..28) << 8`,
   sign-extended from bit 11 (range −2048..2047). Height is the Y of the
   opaque surface block; for water columns it is the floor.
3. **Top height** (bit 24 set, minor ≥ 4): `u8`, the Y of the highest
   translucent block in the column (water surface, ice, glass top). Bit 24
   clear → top height = height (no translucent stack). Semantics of the pair:
   `topHeight − height` = thickness of the translucent stack above the floor
   (section 6). **Quirk:** top height is a 16-bit value in memory but only a
   u8 on disk; for worlds with Y > 255 (formats 6.x+) the stored byte is the
   value modulo 256, and the mod's own reload is lossy there. For MC 1.17.1
   data (Y ≤ 255) it is exact. **[certain]**
4. **Overlays** (bit 1 set): see section 6.
5. **Legacy color** (only when color types are in use): color type 3 → `i32`
   follows (skipped on load).
6. **Biome** (bit 20 set):
   - major ≥ 4: bit 22 set → inline; bit 23 set → `i32` numeric biome registry
     id (reader-side branch, never produced by current writers — biome keys
     without an id fall back to `minecraft:plains` as a string instead); bit 23
     clear → `writeUTF` biome id string. Inline entries are appended to the
     biome palette. Bit 22 clear → `i32` biome palette index.
   - major < 4: `u8` numeric biome id; if minor ≥ 3 and the byte is 255, an
     `i32` follows instead.
7. **Light** is bits 8–11 of params (no separate bytes): block light sampled at
   the Y just above the surface block; cave layers without overlays store
   `max(blockLight, skyLight)`.

**Grass surfaces.** Bit 0 clear means the mapped surface block was the grass
block (in any state, including snowy). On load, such pixels become the default
grass state; grass color comes from the biome, so the state is deliberately not
stored and re-saving normalizes snowy grass to plain grass. **Void/air columns
are different**: a column with no opaque surface stores an explicit inline
`minecraft:air` blockstate (bit 0 set) with height at the world bottom — bit 0
clear is *only* grass. **[certain; air columns not present in our samples]**

---

## 6. Overlays

If pixel bit 1 is set: `u8 count`, then `count` overlay entries. Count is 0
never written (bit 1 would be clear). The list holds at most 10 entries
**[certain]**. Order is **topmost first**: entries are appended while the map
writer scans the column downward, so entry 0 is the highest translucent block
run (its light is what the renderer treats as the pixel's top light).

Each overlay:

| Bits | Meaning (minor 8) |
|---|---|
| 0 | non-water: blockstate follows (0 = water, no state bytes) |
| 2 | legacy: `i32` legacy color follows (read-side skip) |
| 3 | legacy (minor < 8): `i32` opacity follows |
| 4–7 | light, 0–15 |
| 8–9 | legacy color type (only when color types are in use; type 2 → `i32` follows) |
| 10 | blockstate inline (new entry in the **shared** blockstate palette) |
| 11–14 | opacity, 0–15 (minor ≥ 8) |

Overlay payload order: blockstate first (bit 0 set: bit 10 → inline NBT, else
`i32` palette index; bit 0 clear → nothing), then the legacy `i32` fields in
this order, each only when its condition holds: (a) minor < 1 and bit 1 set →
`i32`; (b) color type 2 (legacy color-type eras) **or** bit 2 set → `i32` legacy
color; (c) minor < 8 and bit 3 set → `i32` opacity, with opacity defaulting to
1 when absent. For minor ≥ 8 the opacity is bits 11–14 and no explicit opacity
field exists. **[certain]**

**Water semantics (the important part).**

- Bit 0 clear identifies a **water** overlay; the actual block identity is not
  stored — water is the reader-side default. Every other translucent block
  (ice, glass, stained glass) stores its state via the shared blockstate
  palette. **[certain]**
- The map writer scans a column downward and accumulates translucent blocks
  above the opaque surface into overlays, **run-length merging consecutive
  blocks with the same icon into one overlay** and accumulating their per-block
  light opacity (capped at 15). A lake or ocean column therefore carries
  **exactly one water overlay**, not one per block. Distinct bodies separated
  by another block (e.g. ice over water) produce separate entries.
  **[certain]**
- **Depth recovery.** The exact water depth is `topHeight − height` (pixel
  bit 24 + u8 top height), because top height is the water surface Y and
  height is the floor Y. The overlay **opacity is only a light-attenuation
  proxy**: it accumulates per-block opacity and saturates at 15. Sample
  evidence: 5.8 river data shows depth 1–10 with opacity = depth (±1 on ~1.8 %
  of pixels, an artifact of the writer's bulk-count fast path); 7.8 ocean data
  shows depths up to 61 while opacity never exceeds 15 (depth 25 → opacity 15).
  **Never reconstruct depth from opacity.** **[certain, sample-verified]**
- A pixel with overlays but **no** top-height byte (bit 24 clear) cannot occur
  in practice: any translucent block above the surface makes top height
  ≠ height. Treat that combination as malformed-but-tolerable. **[likely]**

---

## 7. Version dispatch rules

Dispatch on the first byte and the version integer:

| Stream start | Interpretation | Importer action |
|---|---|---|
| first byte ≠ `0xFF` | unversioned, major-0 semantics (numeric 1.12 block ids, numeric biomes, no tile trailers, legacy overlay layout) | **skip** |
| `0xFF` + i32, major 0 | numeric block/biome ids (1.12-era) | **skip** |
| `0xFF` + i32, major 1 | NBT blockstates (`Name`/`Properties`), numeric biome bytes, legacy color types (minor < 5) | skip (outside target window) |
| `0xFF` + i32, major 2 | as major 1; minor ≥ 5 adds the 1.15-flag byte after the header | skip |
| `0xFF` + i32, major 3–4 | NBT blockstates; modern pixel framing at minor ≥ 5; major 4 adds biome palettes (bit 20/22/23) | optional |
| `0xFF` + i32, major 5 | MC 1.17.1-era spelling; the grammar of sections 4–6 | **decode** |
| `0xFF` + i32, major 6 | 1.18+ worlds; identical grammar; heights may exceed 255 (top-height u8 truncation applies) | **decode** |
| `0xFF` + i32, major 7 | same; attested for 1.21.8–26.2 | **decode** |
| `0xFF` + i32, major 8 | MC 26.3: blockstate NBT keys renamed to `id`/`properties` (sample-verified; older entries are renamed on load by the mod) | **decode** with key aliasing |
| `0xFF` + i32, major > 8 or minor > 8 | newer than this spec | **skip** (the mod itself refuses and moves the file to `<version>_backup_<n>/`) |

Notes:

- Minor drives framing (tile trailer ≥ 4/6/7, opacity nibble ≥ 8, height high
  bits 25–28 ≥ 4); major drives identity encoding (numeric vs NBT keys,
  numeric vs palette biomes). All attested modern files use minor 8, uniformly
  across our supported MC range **[certain]**.
- Major-boundary semantics for 1/2/3/4 are inferred from the reader's
  compatibility fix branches (block rename tables applied for major < 5, biome
  palette only for major ≥ 4, color types for minor < 5 or major ≤ 2) **[likely]**.
- Attested writer versions: 5.8 = World Map 1.37.8 / MC 1.17.1 (sample);
  6.8 = 1.21.4-era (community); 7.8 = 1.21.8–26.2 (sample at 1.21.11);
  8.8 = 26.3 (sample at World Map 1.47.2).

---

## 8. Mapping to Conflux Map data

Coordinate mapping: region id from the file name; within a region,
`blockX = regionX·512 + chunkX·64 + tileX·16 + x`,
`blockZ = regionZ·512 + chunkZ·64 + tileZ·16 + z`. Each pixel is one column at
that (x, z).

Per decoded column:

| Xaero field | Conflux Map use |
|---|---|
| blockstate `Name`/`id` | block identity for kind inference (key spelling depends on the file's major version: `Name` for major < 8, `id` for major 8; accept both). Grass/leaves → vegetation-covered terrain, sand/stone/gravel → exposed terrain, `minecraft:air` → void column, water-adjacent kinds via overlay presence. Unknown/unresolvable states (cross-version palettes can contain arbitrary compounds) degrade to a generic terrain kind, keyed by the raw id string. |
| height (12-bit signed) | surface Y of the column (the opaque surface block). For water columns this is the floor. |
| top height − height | fluid/overlay stack thickness; with a water overlay present this is the **water depth** → fluid depth. When bit 24 is clear, depth = 0. |
| overlays | stacked translucent layers; entry 0 is the topmost. A water overlay (bit 0 clear) marks a water column; ice/glass entries refine the column kind. |
| light (bits 8–11) | block light near the surface; 0–15. |
| biome string | biome id (e.g. `minecraft:birch_forest`); namespaces other than `minecraft` may appear for modded biomes. |

Edge cases the importer must handle:

- **Absent tiles/chunks** (no marker, or `i32 -1`): no data — treat as
  unexplored, not as void terrain.
- **Void columns**: explicit `minecraft:air` blockstate with height at the
  world bottom; produce an empty column, not a floor at that height. Not
  present in our samples (ocean floors are always mapped) but code-guaranteed.
- **Grass columns** (bit 0 clear): treat as grass-topped terrain with the
  recorded biome; the exact grass variant (snowy etc.) is not preserved by the
  format.
- **Negative / large heights**: the 12-bit height is signed (−2048..2047) and
  covers 1.18+ build ranges; the u8 top height is lossy above Y 255 in 6.x+
  files (recover depth modulo 256, or fall back to the overlay opacity when
  depth ≤ 15 and exactness is not needed).
- **Cave layers** (`caves/<n>/`) are separate datasets over the same columns:
  their `caveStart`/`caveDepth` trailer says which vertical slice the heights
  refer to (`0x7FFFFFFF` = surface; negative minimum = full-height cave;
  otherwise the slice starts at `caveStart` with the given depth). Do not mix
  them into surface imports.
- **Palette scoping**: blockstate/biome palettes are per region stream; a
  palette index from one file must never be resolved against another.
- **Region written empty**: the mod deletes the file instead of writing an
  empty region — an existing file always contains at least one chunk block
  **[likely]**.
- **Opacity is not depth** (section 6); using it for fluid depth will
  systematically truncate deep oceans.
- **Overlays vs. rendered surface**: the *state* of a water column is the floor
  block (sand, gravel, dirt...), not water; water exists only as overlays. Kind
  inference that looks only at the blockstate will misclassify every ocean
  column.

---

## 9. Verification status

**Code-verified** (against the 1.37.8 decompile; block/biome identities
resolved via Yarn 1.17.1 mappings):

- Header marker and version integer layout; version dispatch and the
  newer-version refusal/backup behavior; the major-2.5 flag byte.
- Chunk/tile framing: marker encoding, 16 fixed tile slots, `-1` tile markers
  (first pixel params doubling as the tile marker), tile trailer fields and
  their minor gates (4/6/7), EOF-terminated streams, truncation tolerance,
  absent chunks producing no marker.
- The two per-stream palettes (blockstates shared with overlays, biomes
  separate), inline NBT (named compound, `Name`/`Properties`) and `writeUTF`
  biome encoding, palette-index references.
- Full params bitfield for 5.8 and every read-side legacy branch (bits 2–3, 6,
  20 legacy path, 23, overlay bits 2/3/8–9), including that bit 23 and bit 6
  are never produced by current writers.
- Grass = bit 0 clear ⟺ surface block is the grass block; reader reconstructs
  the default grass state; air columns carry explicit air NBT.
- Water overlay semantics: default-water identity (bit 0 clear), run-length
  merging of translucent runs with opacity accumulation capped at 15, top
  height = topmost translucent block Y, depth = top height − height, overlay
  list ordered topmost-first, max 10 overlays.
- Light sampling semantics (block light above surface; sky-light max on cave
  layers).
- Storage layout: `XaeroWorldMap/` root and legacy-root migration; container
  naming including port stripping, `§` replacement, `^us^`, `^e^`, `Realms_`,
  `Multiplayer_Any Address`, `Multiplayer_Unknown`; dimension naming (`null`,
  `DIM-1`, `DIM1`, `namespace$path` with `/`→`%`); multiworld naming
  (`mw$default`, `mw$<levelId>`, `mw<x>,<y>,<z>` regex, `cm$` prefix,
  `cm$converted`); `caves/<n>/` layers; `<rx>_<rz>.zip`/`.xaero` naming and the
  `.zip.temp`/`.outdated`/backup-folder conventions; `cache_<n>/*.xwmc` as
  foreign render caches; `server_config.txt`, `states.dat` (stub), `.owner`.

**Sample-verified** (22 regions at 5.8, 24 at 7.8, 24 at 8.8; every file decodes
to the exact last byte):

- Header bytes `ff 00 05 00 08` / `ff 00 07 00 08` / `ff 00 08 00 08`; single
  `region.xaero` zip entry in every file.
- Chunk markers: only values 0..63, strictly ascending scan order; all 64
  values occur across the corpus.
- Tile trailers in surface-layer data: interpretation version 1, cave start
  `0x7FFFFFFF`, cave depth 30, on every present tile (1740 tiles at 5.8, all
  7.8/8.8 tiles likewise).
- Pixel grammar end-to-end: heights in plausible terrain ranges (22–154 at
  5.8; 21–139 at 7.8; −24..140 at 8.8, including negative heights that the
  signed 12-bit field handles), light values 0–14 observed at 5.8 (15 never
  occurs in the corpus), biome strings (`minecraft:*`), MUTF-8 length
  prefixes, inline NBT with exactly the expected key sets: `Name`/`Properties`
  at 5.8/7.8 (with and without `Properties`), `id`/`properties` at 8.8 (with
  and without `properties`).
- Water recovery: one water overlay per water column; depth = top height −
  height (observed 1–10 at 5.8, 1–61 at 7.8, 1–86 at 8.8); opacity = depth for
  shallow columns (±1 on ~1.8 % of 5.8 pixels) and saturated at 15 for deep
  columns (7.8/8.8: every pixel deeper than 15 sits at opacity 15 — the bits
  11–14 nibble carries the value on all modern files).
- Overlay population: water dominates; non-water overlays observed are
  `minecraft:ice` (5.8); at 5.8, pixels with two overlays occur and no pixel
  carries two separate water overlays (consistent with run-length merging);
  at 8.8, every overlay-bearing pixel carries exactly one overlay.
- Top-height bytes stay below height in no sample (no wrap observed); at 8.8
  top-height bytes max out at 105, so the u8 truncation for Y > 255 remains
  untested in-sample.
- Layout roots: `<runDir>/XaeroWorldMap/Multiplayer_Unknown/null/mw$default/`
  (5.8) and `<runDir>/xaero/world-map/Multiplayer_127.0.0.1/null/mw$default/`
  with `cache_1/` and `caves/` siblings (7.8 and 8.8).

**Community-claimed, not independently verified:**

- The data-root move to `<gameDir>/xaero/world-map/` at World Map ≥ 1.39.0
  (consistent with our 7.8 sample tree, but the exact release boundary was not
  bisected).
- Newer container escaping: `%us%` replacing `^us^`, `%fs%`/`%bs%` for `/` and
  `\`; XaeroPlus-derived container variants (server-list name, bare domain,
  `DIM0` overworld folder).
- Writer version table: 6.8 = 1.21.4-era attribution; the 26.3 loader's
  forward-renaming of pre-8.8 NBT keys on load (the 8.8 rename itself and the
  8.8 = 26.3 attribution are sample-verified above).
- `dimension_config.txt` as the authoritative multiworld/dimension metadata in
  newer releases.

**Pending real-sample verification:**

- Major-0/unversioned streams and major 1–4 files (skip paths are code-derived;
  no samples in the corpus).
- Cave-layer (`caves/<n>/`) region files from the wild: same format per code,
  but only the folder's existence is sample-verified, not tile contents
  (expected: `caveStart` ≥ 0 / negative-minimum sentinels, non-zero cave
  depths).
- Void/air columns (`minecraft:air` inline state) — code-guaranteed, absent
  from our samples.
- Top-height u8 truncation on files with Y > 255 (code-derived: top height is
  written as a u8; our corpora only reach top-height byte 105).

**Discrepancies vs. community docs** (code/samples win):

- Gjum's `xaero-format.md` (oldest, ~format 1.x era) describes a `00 ff`
  prelude — 5.x+ files start with `ff` only. Its pixel bits 4–5
  ("heightShade") and bit 7 ("isCaveBlock") do not exist in the 5.x reader;
  its biome-as-byte and overlay layouts match the pre-palette legacy paths
  only.
- RuoChennn's `region-xaero-format.md` (derived from 1.40.11, format 6.8)
  matches our grammar overall, but its overlay bit table is off: bits 11–14
  are the opacity nibble (not "bit 11 reserved"), there is no bit-15
  "explicit opacity" flag (the legacy explicit opacity flag is bit 3, minor
  < 8 only), and the legacy color type field is 2 bits at 8–9, not "8–10".

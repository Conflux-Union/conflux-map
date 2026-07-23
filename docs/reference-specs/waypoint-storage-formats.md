# Waypoint Storage Formats (Read-Only Import)

On-disk waypoint formats of two third-party minimap mods, documented for a
read-only importer. Described as file paths, naming rules, and line grammars
only — no implementation structure is described or implied. Facts were
originally extracted for these versions:

- **Format X** — Xaero's Minimap 23.9.7 for Minecraft 1.17.1 (our target
  version), cross-checked against a real data file written by a 1.21.4-era
  release.
- **Format V** — VoxelMap ("VoxelMap Updated" line), observed at a 1.21-era
  release. Our target is MC 1.17.1; items that are plausibly
  version-dependent are flagged.

A second, multi-version verification pass cross-checked these facts against
many more releases:

- **Format X** — the full Modrinth release catalog was used to fetch and
  decompile 23.4.0 (the first Fabric 1.17.1 build, April 2023) through 23.9.7
  (the last Fabric 1.17.1 build, January 2024) at several points in between,
  plus the oldest cataloged build overall (21.10.31, Forge 1.7.10/1.8.9,
  October 2021, predating the flattening-era dimension identifiers) and
  several post-1.17.1 Fabric 1.20/1.20.1 builds up to the current release
  (mid-2026), to see what changed before and after our target's support
  window.
- **Format V** — the "VoxelMap Updated" repository's full git history was
  fetched (it ships shallow by default) and walked back to its actual
  `Initial commit` (24 Dec 2021), which imports MamiyaOtaru's own last
  Minecraft-1.17.1 release (`mod_version=1.10.16`, package
  `com.mamiyaotaru.voxelmap`, Fabric, `minecraft_version=1.17.1`) verbatim —
  i.e. this commit **is** the original 1.17.1-era mod, not a later
  reconstruction. History was walked forward from there to the current head
  to date every behavior change relevant to import. MamiyaOtaru's own public
  GitHub repository was also checked; it stops at a 2016 archival dump of the
  pre-rename "Zan's Minimap" era (~MC 1.4.7) and was never kept current
  through 1.17.1, so it adds nothing beyond the fork's initial commit for our
  purposes.

Confidence markers: **[certain]** verified directly for the stated version;
**[likely]** inferred with strong evidence; **[uncertain]** plausible but
unverified for 1.17.1.

---

## Format X — Xaero's Minimap

### 1. Storage roots

- 23.9.7 root: `<gameDir>/XaeroWaypoints/` **[certain]**.
- This root is stable across the entire Fabric 1.17.1 support window: the
  first Fabric 1.17.1 build (23.4.0, April 2023) through the last one (23.9.7,
  January 2024) all use `XaeroWaypoints/` — confirmed by decompiling both
  endpoints **[certain]**. It is also what the oldest cataloged build overall
  (21.10.31, Forge 1.7.10/1.8.9, October 2021) uses, so as far back as this
  mod's release history can be checked, `XaeroWaypoints/` has always been the
  root **[certain]**.
- The root moves to `<gameDir>/xaero/minimap/` (identical internal layout)
  starting with mod version **24.3.0** (published 2024-08-17); mod version
  24.2.0 (2024-06-03) is the last one still on `XaeroWaypoints/`
  **[certain — bisected by decompiling both builds]**. This is well after
  Xaero dropped Fabric 1.17.1 support (last 1.17.1 build was 23.9.7, January
  2024) — **1.17.1 itself never shipped a build that uses the new root**. An
  importer should still probe `xaero/minimap` first, then `XaeroWaypoints`,
  since a game directory can be shared with a later-MC-version profile of the
  same mod.
- 23.9.7 (and every other build checked, from 21.10.31 through the current
  release) migrates several legacy root locations into the current root at
  startup if the new root does not exist yet: `mods/XaeroWaypoints`,
  `config/XaeroWaypoints`, a `XaeroWaypoints` folder next to the mod jar, and
  a `XaeroWaypoints` folder one level above the config folder **[certain]**.
  An importer does not need to replicate the migration; probing the current
  and previous roots above is enough.
- A one-time backup copy of the whole root may exist as sibling folder
  `XaeroWaypoints_BACKUP032021` (pre-24.3.0 builds) or
  `XaeroWaypoints_BACKUP240807` (24.3.0 onward, taken of the new
  `xaero/minimap` root) — ignore it either way **[certain]**.
- A very old single-file format exists at `<configDir>/xaerowaypoints.txt`
  (see §9) **[certain]**; only needed if importing pre-folder-era data. The
  migration check for this exact path is still present verbatim in the
  current (2026) release, so it has never been removed **[certain]**.

### 2. World container folder (first level under the root)

One folder per "server or save", produced from the current game context:

| Context | Folder name rule |
|---|---|
| Singleplayer | The **save directory name** (the folder under `saves/`, not the display name), with `_` → `%us%`, `/` → `%fs%`, `\` → `%bs%` (applied in that order). |
| Multiplayer | `Multiplayer_` + transformed server address (below). |
| Realms | `Realms_` + realm owner UUID + `.` + numeric realm id. |
| Undetectable | `Unknown`. |

The Realms row's field order was independently confirmed by decoding 23.9.7's
Fabric intermediary names against the matching Yarn mappings (`1.17.1+build.65`):
the identifier's two fields resolve to `ownerUUID` (a string) and `id` (a
`long`) in that order, and the code concatenates them as
`"Realms_" + ownerUUID + "." + id` — exactly the table above **[certain]**.
This exact concatenation is present unchanged from the first Fabric 1.17.1
build (23.4.0) through the current (2026) release. It did **not** exist at
the oldest cataloged build (21.10.31, October 2021, Forge 1.7.10/1.8.9) —
that build has no Realms branch at all (MC 1.7.10 predates client Realms
support), so `Realms_` container naming should be treated as **[certain from
23.4.0 onward, not applicable to pre-Realms MC versions]**.

Multiplayer address transformation **[certain]**:

1. Start from the address string exactly as entered in the server list.
2. Strip the port: if the string contains more than one `:` (IPv6), cut at
   the last `]:`; otherwise cut at the first `:`. If no port separator, keep
   the whole string.
3. Strip any trailing `.` characters.
4. Replace, in order: `:` → `§`, `_` → `%us%`, `/` → `%fs%`, `\` → `%bs%`.
   (After step 2 a `:` can remain only in an unbracketed IPv6 literal.)
5. Prefix `Multiplayer_`.

Notes:

- The address is **not lowercased**. When a folder that differs only by case
  already exists, the existing folder's casing wins (matching is
  case-insensitive) **[certain]**. Importers should match container folders
  case-insensitively.
- If the mod's "differentiate by server address" option is off, the address
  is the literal string `Any Address`, giving folder
  `Multiplayer_Any Address` **[certain]**.
- Pre-IPv6-fix folders (port cut at the *first* `:` even for IPv6) are
  renamed to the fixed name at startup **[certain]**; not import-relevant.
- The IPv6-aware `]:`-based cut (step 2 above) is a **late** addition: it is
  absent from every Fabric 1.17.1 build checked from 23.4.0 (April 2023)
  through 23.9.4 (December 2023) — those builds always cut at the first `:`,
  the same as the October-2021 build — and first appears in 23.9.7 (January
  2024), which is also the very last Fabric 1.17.1 release
  **[certain — bisected across nine 1.17.1 builds]**. In practice this means
  most real 1.17.1 installs (anyone who didn't update to the final release)
  produced non-IPv6-aware container folders for IPv6 servers; an importer
  should apply the modern (bracket-aware) rule when reading regardless, since
  it degrades to the old behavior whenever there are no brackets.
- The port is stripped **unconditionally** (any port number, not just the
  default), in every build checked back to October 2021 — unlike VoxelMap,
  Xaero has never special-cased port `25565` **[certain]**.

### 3. Dimension subfolder (second level)

Inside each container folder, one subfolder per dimension **[certain]**:

| Dimension | Folder |
|---|---|
| `minecraft:overworld` | `dim%0` |
| `minecraft:the_nether` | `dim%-1` |
| `minecraft:the_end` | `dim%1` |
| any other key `ns:path` | `dim%` + `ns` + `$` + `path` with every `/` replaced by `%` |

Reverse mapping when reading: strip the 4-character prefix `dim%`; `0`, `-1`,
`1` map to the three vanilla dimensions; any other numeric-only remainder is
an unknown legacy numeric id; otherwise split on the first `$` into
namespace and path, replacing `%` back to `/` in the path **[certain]**.

The "any other numeric-only remainder is an unknown legacy numeric id" case
is not theoretical: at the oldest cataloged build (21.10.31, October 2021,
Forge 1.7.10 — pre-1.13, before Minecraft had namespaced dimension
identifiers), the folder name is generated as literally `"dim%" + <raw
integer dimension id>` with no `$`-path form possible at all, so `dim%0`,
`dim%-1`, `dim%1` and any other modded integer id are the **only** shapes
that era could produce **[certain]**. The `ns:path` / `$`-encoded form only
became possible once Minecraft moved to identifier-based dimensions (1.16+),
and both forms have been read/written unchanged from then through the
current (2026) release.

Legacy names that 23.9.7 still recognizes and renames on load
**[certain]**: a dimension folder literally named `Overworld` → `dim%0`,
`Nether` → `dim%-1`, `The End` → `dim%1`.

Other entries at these levels — all to be ignored by an importer:

- `config.txt` directly inside the container folder: per-server options
  (plain `key:value` lines). Two keys are useful for import **[certain]**:
  - `defaultMultiworldId:<id>` — which multiworld id (§4) is the "default"
    sub-world when multiworld detection is off.
  - `dimensionType:<ns>$<path>:<...>` lines — bookkeeping, ignore.
- `backup/` folders (at root level and below) — conversion backups.
- `temp_to_add/` folders — crash-recovery staging; the mod merges them back
  on load. An importer can ignore them (contents mirror normal files).
- `*.txt.temp` files — in-progress atomic saves; ignore.

### 4. Waypoint files inside a dimension folder

Extension is always `.txt`. Two naming shapes **[certain]**:

- `waypoints.txt` — the single default sub-world. Used for singleplayer
  (integrated server) sessions.
- `<multiworldId>_<displayName>.txt` — one file per detected sub-world
  (multi-world servers). All waypoint sets of that sub-world live in this
  one file.

`<multiworldId>` shapes **[certain]**:

- `mw$<int>` — the sub-world id is a 32-bit integer pushed by a server-side
  companion (e.g. `mw$793433485`).
- `mw<a>,<b>,<c>` — spawn-based auto id when no server id exists: the world
  spawn block position with each of x, y, z arithmetically shifted right by
  6 (floor-division by 64), joined with commas (e.g. `mw0,1,-1`).
- Anything else — ids supplied by the companion world-map mod; treat as
  opaque strings.

Both the `mw$<int>` and spawn-shift-by-6 `mw<a>,<b>,<c>` generation rules are
present, byte-for-byte identical (modulo obfuscated identifier names), in the
oldest cataloged build (21.10.31, October 2021) as well as every 1.17.1
Fabric build and the current (2026) release — this has been stable for the
whole checkable history **[certain]**.

`<displayName>` is a user-facing sub-world label with `_` → `%us%` and
`:` → `§§` applied **[certain]**. Auto-assigned labels are decimal integers
starting from (count of known labels + 1), skipping taken values — hence the
common trailing `_1`. When splitting a file name, split on `_`: the part
before the first `_` is the id, the part after it is the label (the label
itself cannot contain a raw `_` because of the escape) **[certain]**.

There is no "one set per file": a file holds **all** waypoint sets of its
sub-world (§6).

### 5. File encoding and line structure

- Encoding: UTF-8, both read and write **[certain]**.
- Line separator: `\n` on write; any standard line reader works.
- Each line is split on `:` into tokens; the first token selects the line
  type. Unrecognized first tokens (including `#`, `#waypoint`, empty lines)
  are ignored **[certain]**. A line that fails to parse (bad number, too few
  tokens) is skipped individually; the rest of the file still loads
  **[certain]**.

A freshly saved file looks like **[certain — verified against real data]**:

```
sets:<currentSet>:<otherSet>:<otherSet>...      (only when 2+ sets exist)
#
#waypoint:name:initials:x:y:z:color:disabled:type:set:rotate_on_tp:tp_yaw:visibility_type:destination
#
waypoint:...                                    (zero or more)
server_waypoint:<key>:<true|false>              (zero or more)
```

Line types:

- `sets:` — token 1 is the currently selected set; every token from 1
  onward is a set name. Written only when more than one set exists
  **[certain]**.
- `waypoint:` — one waypoint (§6).
- `server_waypoint:<key>:<bool>` — persisted disabled-state for
  server-pushed waypoints; safe to ignore on import **[certain]**.

### 6. The `waypoint:` line

Colon-separated tokens, in exactly this order (token 0 is the literal
`waypoint`) **[certain]**:

| # | Field | Type | Meaning / rules |
|---|---|---|---|
| 1 | name | string | Waypoint name. `:` stored as `§§`. May be a translation key (see below). |
| 2 | initials | string | 1–2 char label drawn on the map. Same `§§` escape. Death waypoints use `D`; quick "instant" waypoints use `X`. |
| 3 | x | int | Block X, in the coordinate space of the **containing dimension** (nether files store nether coordinates). |
| 4 | y | int or `~` | Block Y. Literal `~` means "Y unknown/not included". |
| 5 | z | int | Block Z, dimension-local like x. |
| 6 | color | int | Palette index (§7). Values ≥ 16 or < 0 occur; renderer uses `max(0, index) mod 16`. |
| 7 | disabled | bool | `true` = hidden. Optional in principle, but see the minimum-length rule below. |
| 8 | type | int | 0 = normal, 1 = latest death point, 2 = old death point. |
| 9 | set | string | Name of the waypoint set this entry belongs to. **Not escaped** in this position (set names can never contain raw `:`, see §8). |
| 10 | rotate_on_tp | bool | Teleport also applies the stored yaw. Optional; default `false`. |
| 11 | tp_yaw | int | Yaw in degrees used when field 10 is true. Optional; default 0. |
| 12 | visibility_type | int | 0 = local, 1 = global, 2 = world-map local, 3 = world-map global. Legacy values `true`/`false` mean 1/0. Optional; default 0. |
| 13 | destination | bool | One-off destination flag (auto-delete when reached). Optional; default `false`. |

Minimum-length rule **[certain]**: the loader reads the set field (token 9)
unconditionally before anything else, so a line with fewer than 10 tokens
fails entirely and is skipped. Fields 10–13 are genuinely optional (present
since various older versions; absent in old files). An importer should
require tokens 1–9 and default tokens 10–13.

Field evolution, bisected across cataloged builds **[certain]**: fields 10
(`rotate_on_tp`) and 11 (`tp_yaw`) are already present at the oldest cataloged
build (21.10.31, Forge 1.7.10/1.8.9, October 2021). At that same build, field
12 is a plain boolean called `global` (not the 4-value `visibility_type`) and
there is **no field 13 at all** — the header of that era reads
`#waypoint:name:initials:x:y:z:color:disabled:type:set:rotate_on_tp:tp_yaw:global`
(12 fields, not 13). The 4-value `visibility_type` plus the `destination`
field are both already present, unchanged, at 23.4.0 — the first Fabric
1.17.1 build (April 2023) — so the transition happened sometime in the
~18-month gap the Modrinth catalog does not cover (October 2021 – April
2023). Practical consequence for import: **every Fabric 1.17.1 build ever
released** (23.4.0 through 23.9.7) already writes the full 13-field format,
so real 1.17.1 waypoint data will only ever be missing fields 12–13 if it was
carried over from a much older, pre-Fabric-support install (Forge, or some
MC version far earlier than 1.17.1) without ever being resaved. An importer
that treats a bare boolean in field-12's position as the legacy `global`
flag (`true`→1, `false`→0, matching the spec's existing "legacy values
true/false mean 1/0" handling) and treats a missing field 13 as `destination
= false` covers both eras correctly.

Translation-key names **[certain]**: some names are stored as keys and shown
localized. Keys observed in files and their en_us strings:

| Stored literal | English display |
|---|---|
| `gui.xaero_default` | `Default` (set name) |
| `gui.xaero_deathpoint` | `Latest Death` |
| `gui.xaero_deathpoint_old` | `Old Death` |

An importer should map at least these three; any other name is displayed
verbatim.

Death points **[certain]**: created with type 1, name `gui.xaero_deathpoint`,
initials `D`, color 0 (black), inserted at the top of the current set. On
the next death the previous one is either deleted (option off) or converted
to type 2 with name `gui.xaero_deathpoint_old`. Types 1 and 2 force
visibility_type 1 and destination `true` at load time regardless of the
stored tokens (confirmed unchanged from 23.4.0 through the current release).
At the oldest cataloged build (21.10.31, October 2021, pre-dating the
`visibility_type`/`destination` fields — see above), the equivalent forcing
only applied to type 1 (`global = true`); type 2 was not yet specially
handled at construction time, consistent with `destination` not existing yet
**[certain]**.

Y-unset semantics **[certain]**: `~` in the y field means the waypoint has
no known height (e.g. created from the world map). Renderers treat it as
"any height"; teleport substitutes `~`.

### 7. Color palette

The color field is an index into the 16-entry Minecraft chat color palette.
Exact opaque RGB values used for rendering **[certain]**:

| Index | Hex RGB | Name |
|---|---|---|
| 0 | `#000000` | black |
| 1 | `#0000AA` | dark blue |
| 2 | `#00AA00` | dark green |
| 3 | `#00AAAA` | dark aqua |
| 4 | `#AA0000` | dark red |
| 5 | `#AA00AA` | dark purple |
| 6 | `#FFAA00` | gold |
| 7 | `#AAAAAA` | gray |
| 8 | `#555555` | dark gray |
| 9 | `#5555FF` | blue |
| 10 | `#55FF55` | green |
| 11 | `#55FFFF` | aqua |
| 12 | `#FF0000` | red |
| 13 | `#FF55FF` | light purple |
| 14 | `#FFFF55` | yellow |
| 15 | `#FFFFFF` | white |

Normalization on read: `index < 0` → 0; otherwise `index mod 16`
**[certain]**.

### 8. Escaping summary

| Where | Raw | Stored as |
|---|---|---|
| waypoint name, initials (fields 1–2) | `:` | `§§` |
| set names | `:` | `§§` — but applied **once at creation**; the stored set name permanently contains `§§` and is written/compared verbatim thereafter. Decode `§§` → `:` for display only. |
| sub-world display label in file names | `_` | `%us%` |
| sub-world display label in file names | `:` | `§§` |
| container folder names | `_` / `/` / `\` | `%us%` / `%fs%` / `%bs%` |
| container folder names (multiplayer) | `:` (IPv6 remainder) | `§` |
| custom dimension folder names | `/` (in the key path) | `%` |

No other characters are escaped. Decoding is a plain string replacement of
the token back to the character.

### 9. Legacy single-file format (pre-folder era) [certain, low import priority]

`<configDir>/xaerowaypoints.txt` mixed all worlds in one file:

- `world:<containerId>:<set>[:<set>...]` — declares a world and its sets.
- `waypoint:<containerId>:<name>:<initials>:<x>:<y>:<z>:<color>[:<disabled>][:<type>][:<set>][:<rotate>][:<yaw>]`
  — name/initials use the same `§§` escape; y is always numeric here.
- `<containerId>` ends with `_null` (overworld) or `_DIM<n>` (numeric
  dimension id); a missing suffix means `_null`.
- After conversion the file is renamed to `xaerowaypoints.txt.backup`.

Intermediate layouts the 23.9.7 loader still converts on sight (ignore for
import unless targeting untouched ancient data): flat
`<container>_<dim>.txt` files directly in the root (moved to
`<container>_<dim>/waypoints.txt`), and per-dimension folder names ending in
`_null` / `_DIM<n>` (split into `<container>/dim%<n>`).

### 10. Cross-check against real data

File `xaero/minimap/Multiplayer_game.rms.net.cn/dim%0/mw$793433485_1.txt`
written by a 1.21.4-era release matches this spec byte-for-byte in comment
header, field order, and count (14 tokens), and confirms: root moved to
`xaero/minimap`, container/dimension/file naming unchanged, `sets:` line
omitted for a single set, set literal `gui.xaero_default` unchanged
**[certain]**.

### 11. Version caveats

- 23.9.7 root is `XaeroWaypoints`; releases from 24.3.0 (August 2024) onward
  use `xaero/minimap`. Probe both **[certain]**.
- Fields 10–13 were added over time; old files may end at token 9 (field 12
  was a plain boolean called `global` and field 13 did not exist yet, at
  least as far back as October 2021), or, per the guards, at token 7/8 —
  such lines fail to load in 23.9.7 itself and can be rejected **[certain]**.
- The waypoint line grammar is unchanged between 23.9.7 and the 1.21.4 era
  **[certain for the observed file]**, and in fact unchanged all the way to
  the current (2026) release (same 13-field header string byte-for-byte)
  **[certain]**.

### 12. Version matrix

Bisected by decompiling cataloged Modrinth releases (Forge or Fabric, the
same shared codebase across loaders for a given mod version). MC-version
columns matter less than the mod version, since Xaero ships one codebase
across many concurrent Minecraft targets.

| Behavior | 21.10.31 (Oct 2021, Forge 1.7.10/1.8.9) | 23.4.0–23.8.4 (Apr–Dec 2023, incl. all Fabric 1.17.1 before the last) | 23.9.0–23.9.7 (Nov 2023–Jan 2024, incl. last Fabric 1.17.1) | 24.2.0 (Jun 2024) | 24.3.0–current (Aug 2024–2026) |
|---|---|---|---|---|---|
| Root | `XaeroWaypoints` | `XaeroWaypoints` | `XaeroWaypoints` | `XaeroWaypoints` | `xaero/minimap` |
| Realms container | not applicable (pre-Realms MC) | `Realms_<ownerUUID>.<id>` | `Realms_<ownerUUID>.<id>` | `Realms_<ownerUUID>.<id>` | `Realms_<ownerUUID>.<id>` |
| Multiplayer port strip | first `:`, no IPv6 awareness | first `:`, no IPv6 awareness | IPv6-aware `]:` cut + folder auto-rename | IPv6-aware | IPv6-aware |
| Dimension folder | bare integer `dim%<n>` only (no identifiers yet) | `dim%0/-1/1` + `dim%ns$path` | same | same | same |
| Waypoint line field 12/13 | `global` (bool), no field 13 | `visibility_type` (0–3) + `destination` | same | same | same |
| Death type-2 forces visibility/destination | no (type 1 only forced `global`) | yes | yes | yes | yes |
| `mw$<id>` / `mw<a,b,c>` multiworld id | present | present | present | present | present |
| Legacy `xaerowaypoints.txt` single-file migration | present | present | present | present | present (still in the 2026 build) |

**Our target (Fabric 1.17.1) only ever existed in the "23.4.0–23.8.4" and
"23.9.0–23.9.7" columns** — it never shipped with the `xaero/minimap` root,
and for all but its last two months of support it also never had the
IPv6-aware port fix. Everything else in this table was already stable and
mature for the entirety of 1.17.1's Xaero support window.

---

## Format V — VoxelMap

### 1. Storage locations and file naming

- Current location: `<gameDir>/voxelmap/<worldFileName>.points` — one flat
  file per server/save; no per-dimension folders **[certain]**. Confirmed
  identical in the mod's actual `Initial commit` (24 Dec 2021,
  `mod_version=1.10.16`, MC 1.17.1) as well as the current (2026) head — the
  literal path `"/voxelmap/"` and the `.points` extension have never changed.
- Old location still read (only used if the new file does not exist):
  `<gameDir>/mods/mamiyaotaru/voxelmap/<worldFileName>.points` **[certain]**
  — also present verbatim at the 1.17.1 initial commit, so this fallback
  predates that commit (it is the *original*, pre-`voxelmap/`-folder save
  location, from whatever version came before the current layout).
- If both exist, the new location wins **[certain]**.

`<worldFileName>` construction, current (2026) behavior:

| Context | Base name |
|---|---|
| Singleplayer | The save directory name (folder under `saves/`). **Not lowercased.** |
| Multiplayer | The server address exactly as in the server list, **lowercased**. |
| LAN game | The server list display name of the LAN entry, **also lowercased** (LAN goes through the same lowercasing branch as multiplayer — it is not a separate case). |
| Realms | `Realm_` + numeric realm id + `.` + owner UUID. |
| Fallback (no server entry) | `host:port` from the live connection, **lowercased** (same branch). |

Then:

1. If the base name ends with the literal `:25565` (default port), that
   suffix is removed. **Any other port stays in the name.** This check is a
   plain string suffix match with **no IPv6 bracket-awareness** at all — it
   has never been needed since it only ever fires for the literal default
   port, and no other version of this check has ever existed **[certain]**.
2. File-name escaping is applied (§4, file-name set): e.g.
   `play.example.com:1234` → `play.example.com~colon~1234.points`.

**Singleplayer not lowercased, multiplayer/LAN/fallback lowercased, and the
`:25565` strip: all confirmed *unchanged* from the 1.17.1 initial commit
(Dec 2021) through the current head [certain]** — this exact code has been
touched by only one commit in the repository's entire history (the commit
that introduced it), and it was never revisited.

**Realms naming has changed shape multiple times and the current
`Realm_<id>.<ownerUUID>` form is recent — it is *not* what 1.17.1-era
VoxelMap wrote.** Bisected via the mod's own commit history:

| Period | Realms naming |
|---|---|
| 1.17.1 initial commit (Dec 2021) through ~April 2023 | Literal string `"Realms"` (lowercased to `"realms"`) for **every** Realms world — all Realms ever played share one waypoint file, with no way to tell them apart **[certain]**. |
| ~April 2023 (MC snapshot 23w14a) through 16 Jun 2024 | The Minecraft client/launcher session id (`session.getSessionId()`) — effectively an unstable, session-scoped value unrelated to which Realm is being played **[certain]**. |
| 16 Jun 2024 (same day, two commits) | Briefly the Realm's display name (shared with the LAN branch), then within the hour replaced by `"Realm_" + realmId + "." + ownerUUID` **[certain]**. |
| 16 Jun 2024 onward (current) | `Realm_<id>.<ownerUUID>` — stable and unique per Realm **[certain]**. |

Since 1.17.1 predates all of these transitions, **any real 1.17.1-era
VoxelMap Realms data is named exactly `realms.points`** (or under the old
`mods/mamiyaotaru/voxelmap/` location as `realms.points`), and an importer
targeting 1.17.1 should treat that literal filename as "all Realms worlds,
undifferentiated" rather than trying to decode it as `Realm_<id>.<uuid>`.

### 2. File structure

Plain text, UTF-8 **[certain]** (note: one code path re-reads the header
lines with a platform-default-charset reader via `java.util.Properties`, so
non-ASCII sub-world names may load inconsistently in the mod itself; an
importer should just use UTF-8). This dual-reader quirk — `Properties.load`
over a plain `FileReader` (platform charset) for the three header keys,
followed by a second pass over the same file with an explicit UTF-8 reader
for the waypoint lines — is present verbatim, statement for statement, in
the 1.17.1 initial commit (Dec 2021) and completely unchanged at the current
head **[certain]**. The file is fully rewritten on every change; there are
no backup or temp files **[certain]**.

Three header lines are always written first, even when empty **[certain,
confirmed present at the 1.17.1 initial commit]**:

```
subworlds:<name>,<name>,...,
oldNorthWorlds:<name>,...,
seeds:<name>#<seed>,...,
```

- `subworlds:` — known sub-world (multiworld) names, each escaped with the
  value escape set (§4) and each followed by a trailing comma.
- `oldNorthWorlds:` — sub-world names (or the literal `all`) whose maps use
  the pre-1.0 "old north" orientation. Ignore for import.
- `seeds:` — `name#seed` pairs (world seed strings per sub-world, key `all`
  when no sub-worlds). Ignore for import. **[certain — the line is written
  unconditionally by 1.17.1-era VoxelMap too, upgraded from the earlier
  uncertain marker]**

Since these three lines are parsed with `java.util.Properties`, which reads
the **entire file** (every waypoint line is also seen as a `key:rest-of-line`
property, harmlessly overwritten line by line) and applies backslash escape
processing to values, a waypoint or sub-world value containing a raw `\` at
the end of a header-relevant line could in principle merge with the next
line under `Properties`' line-continuation rule. This has always been true
and is not 1.17.1-specific; it is a low-priority edge case since VoxelMap's
own escape set never produces a trailing backslash.

Every other meaningful line is one waypoint: a comma-separated list of
`key:value` pairs.

### 3. Waypoint line grammar

Write order (fixed) **[certain]**:

```
name:<v>,x:<v>,z:<v>,y:<v>,enabled:<v>,red:<v>,green:<v>,blue:<v>,suffix:<v>,world:<v>,dimensions:<v>
```

Parsing is key-driven and order-insensitive **[certain]**: split the line on
`,`; require at least 2 pairs; split each pair at its **first** `:`;
lowercase and trim the key; trim the value; unknown keys are ignored. A line
whose parsed name is empty is discarded (this makes the three header lines
parse as non-waypoints naturally). Exact duplicates are dropped on load —
precisely, two waypoints are equal (and the second is dropped) when `name`,
`suffix` (post-lowercasing), `world`, `x`, `y`, `z`, `red`, `green`, `blue`,
and `dimensions` all match; **`enabled` is not part of the equality check**
**[certain]**. Lines that throw during parsing are skipped individually.
This entire grammar — key set, defaults, and the equality rule above — is
present unchanged (down to the exact field list compared) in the 1.17.1
initial commit (Dec 2021) and the current head.

| Key | Type | Default if absent | Meaning |
|---|---|---|---|
| `name` | string (escaped §4) | `""` (line discarded) | Display name. Names beginning with `^` are runtime-only and never saved. |
| `x` | int | 0 | Block X **in overworld-equivalent scale**: the raw stored value is dimension-local X multiplied by that dimension's coordinate scale (nether ×8). Divide by the viewing dimension's scale to display. |
| `z` | int | 0 | Block Z, same scaling as x. |
| `y` | int | `-1` | Block Y, unscaled. `-1` conventionally means "unknown". |
| `enabled` | bool (`true`/`false`) | `false` | Visibility toggle. |
| `red` | float 0..1 | `0.5` | Color component. |
| `green` | float 0..1 | `0.0` | Color component. |
| `blue` | float 0..1 | `0.0` | Color component. |
| `suffix` | string | `""` | Icon name; lowercased on load. Values match built-in icon names (e.g. `skull`, `point`, `house`, `diamond`, ...). Not escaped (icon names are plain words). |
| `world` | string (escaped §4) | `""` | Sub-world name this waypoint belongs to; empty = visible in every sub-world. Also implicitly registers the sub-world name on load. |
| `dimensions` | `#`-separated list | empty → overworld | See §5. |

**The overworld-equivalent x/z scaling (multiply by the dimension's
coordinate scale on save, divide on display/read) is confirmed present,
unchanged, in the 1.17.1 initial commit (Dec 2021) [certain]** — this was
not a later addition; every code path that constructs a `Waypoint` from live
player coordinates (manual add, death point) has always applied
`* dimensionScale` at that commit, matching the current head exactly.

Color notes: a fully missing color triple renders as the dark-red default
(0.5, 0, 0). Values are written with standard shortest-float formatting
(`1.0`, `0.5`, `0.85`, ...) **[certain]**.

### 4. Escape sequences

Two distinct escape sets **[certain]**:

**Value escape set** — applied when writing `name`, `world`, and the names
in the `subworlds:` / `oldNorthWorlds:` / `seeds:` headers:

| Raw | Stored |
|---|---|
| `,` | `~comma~` |
| `:` | `~colon~` |

**File-name escape set** — applied to the `.points` file base name
(Windows-hostile characters):

| Raw | Stored |
|---|---|
| `<` | `~less~` |
| `>` | `~greater~` |
| `:` | `~colon~` |
| `"` | `~quote~` |
| `/` | `~slash~` |
| `\` | `~backslash~` |
| `\|` | `~pipe~` |
| `?` | `~question~` |
| `*` | `~star~` |

Decoding applies the union of both sets (all nine `~word~` tokens plus
`~comma~`), and additionally maps the fullwidth substitutes `﹐` → `,`,
`⟦` → `[`, `⟧` → `]` (used by a separate runtime path; harmless to support)
**[certain]** — the decode function containing all twelve replacements
(nine file-name tokens, `~comma~`, `~colon~` again, and the three fullwidth
substitutes) is present character-for-character in the 1.17.1 initial commit
(Dec 2021), so this has never been 1.21-only; it was already fully mature at
the very start of the tracked history.

### 5. The `dimensions` value

- Encoding: one or more dimension storage names joined by `#`, with a
  trailing `#` (e.g. `overworld#`, `the_nether#the_end#`) **[certain]**.
- Storage name rule **[certain]**: for `minecraft`-namespace dimensions the
  bare path is used (`overworld`, `the_nether`, `the_end`); for modded
  dimensions the full `namespace:path` form is used (the `:` is **not**
  escaped here — split the value on `#` first, then parse each element).
  The literal `UNKNOWN` appears when the dimension had no identifier.
- Legacy numeric ids are converted on read: `0` → `overworld`, `-1` →
  `the_nether`, `1` → `the_end`. **This conversion is a comparatively recent
  addition, not something that has always been there**: it was added in a
  single, precisely dated commit ("load old waypoints", 27 Jul 2023, when
  the codebase had already moved on to MC 1.20.1) **[certain — bisected in
  the mod's own git history]**. Before that commit — including the entire
  1.17.1 initial-commit state (Dec 2021) — `dimensions` values were parsed
  by handing the raw string straight to the dimension-identifier lookup with
  **no special-casing of `"0"`/`"-1"`/`"1"` at all**, so a genuinely
  ancient numeric-id file loaded into 1.17.1-era VoxelMap (or any version
  before Jul 2023) would silently create a bogus, unmatched pseudo-dimension
  rather than resolve to the real overworld/nether/end. Since 1.17.1 itself
  always used identifier-based dimension storage names (`getStorageName()`
  producing bare path / `namespace:path`, confirmed present verbatim at the
  1.17.1 initial commit), no file *written* by 1.17.1-era VoxelMap ever
  contains a numeric id — the numeric form can only appear in a file
  inherited from a pre-1.13 install and never resaved since. An importer
  that always applies the modern conversion (as the current spec's importer
  assumption does) is more permissive than any single historical VoxelMap
  release ever was, which is fine for read-only import but should not be
  read as "VoxelMap has always supported this."
- Empty list (key missing or value empty) means overworld; on save an empty
  list is written as the overworld storage name **[certain]**.
- Semantics: the waypoint is shown in **every** dimension listed. Combined
  with the overworld-scaled x/z storage (§3), one waypoint can be displayed
  at corresponding coordinates in several dimensions (classic
  nether-portal-pair use) **[certain]**.

### 6. Death points

Persisted as ordinary waypoints — no dedicated type key **[certain]**:

- Name `Latest Death`, icon `suffix:Skull` (stored capitalized, compared
  lowercase), color white (1.0, 1.0, 1.0), y = death Y − 1, x/z scaled by
  the death dimension's coordinate scale, dimensions = the dimension of
  death, world = current sub-world name.
- On the next death, the existing `Latest Death` is renamed `Previous Death`
  and then either deleted (option) or renamed `Previous Death <n>` with its
  color faded one step toward mid-gray (each component moves 1/8 of the way
  toward 0.5) **[certain]**.
- An importer can only recognize death points by these name patterns
  **[certain]**.
- All of the above, including the exact `/8` fade formula and the `Skull`
  capitalization, is present unchanged in the 1.17.1 initial commit (Dec
  2021) and the current head — this behavior has never changed
  **[certain]**.

### 7. Meaning of `world` and `suffix`

- `world`: the sub-world (multiworld) label the waypoint is tied to, as
  announced by a server companion or entered by the user; empty string means
  "all sub-worlds". Names in `subworlds:` and in `world:` values refer to
  the same namespace of labels.
- `suffix`: the icon image name appended to the waypoint marker, chosen from
  the mod's built-in icon set; not a file path. Unknown values fall back to
  a default dot icon.

### 8. Version caveats (1.21-era source vs 1.17.1 target)

- Line grammar, key names, escapes, `#` separator, float colors, x/z
  overworld-equivalent scaling, the dual-reader header-parsing quirk, and
  death-point handling are all confirmed **identical** at the 1.17.1 initial
  commit (Dec 2021) and the current (2026) head — none of this was ever
  1.21-only **[certain, upgraded from likely]**.
- Dimension storage names via bare path / `namespace:path` require the
  identifier-based dimension system (MC 1.16+); confirmed the 1.17.1-era
  code already produces exactly these names via the same bare-path-for-
  `minecraft`-namespace rule **[certain, upgraded from likely]**.
- `seeds:` header: confirmed written unconditionally by 1.17.1-era VoxelMap
  too **[certain, upgraded from uncertain]**.
- Realms naming: **corrected, not merely uncertain** — 1.17.1-era VoxelMap
  used the flat literal `"realms"` for every Realms world (see §1); the
  `Realm_<id>.<ownerUUID>` form did not exist until mid-2024, long after
  1.17.1 support.
- Legacy numeric dimension-id conversion: **corrected** — this conversion
  did not exist yet in 1.17.1-era VoxelMap (added Jul 2023, MC 1.20.1 era);
  see §5.
- The community-continuation fork's `Initial commit` (24 Dec 2021) targets
  MC 1.17.1 with `mod_version=1.10.16` under the original
  `com.mamiyaotaru.voxelmap` package — this **is** effectively the original
  author's 1.17.1-era mod (the fork had not yet made any changes at that
  point), not a later reconstruction, resolving the earlier "community
  continuation may differ" uncertainty for every fact checked in this pass.
  MamiyaOtaru's own public GitHub history stops at a 2016 dump of the
  pre-rename "Zan's Minimap" era and does not reach 1.17.1, so this fork
  commit is the earliest available authentic source for our target version.

### 9. Version matrix

Behaviors that are stable across the entire checked history (1.17.1 initial
commit, Dec 2021, through the current 2026 head), with no changes found at
any point:

| Behavior | Status |
|---|---|
| `voxelmap/*.points` root + `mods/mamiyaotaru/voxelmap/` fallback | unchanged |
| Singleplayer not lowercased; multiplayer/LAN/fallback lowercased; `:25565` strip (no IPv6 awareness, never needed) | unchanged |
| File-name escape set (9 tokens) + value escape set (`~comma~`/`~colon~`) + fullwidth trio decode | unchanged |
| x/z overworld-equivalent scaling on save/read | unchanged |
| `subworlds:`/`oldNorthWorlds:`/`seeds:` headers, dual-reader (`Properties`+platform charset, then UTF-8) quirk | unchanged |
| Waypoint line grammar, key set, defaults, and the exact duplicate-equality field list | unchanged |
| Death point name/fade/icon rules | unchanged |

Behaviors that changed, with the two independent timelines that matter for
1.17.1 shown separately (they do not share boundaries):

| Period | Legacy numeric dimension id (`0`/`-1`/`1`) conversion on read |
|---|---|
| 1.17.1 initial commit (Dec 2021) – 26 Jul 2023 | **absent** — a numeric-id file loads as a bogus unmatched dimension |
| 27 Jul 2023 ("load old waypoints" commit, codebase already at MC 1.20.1) onward | present |

| Period | Realms container/file naming |
|---|---|
| 1.17.1 initial commit (Dec 2021) – ~20 Apr 2023 | flat literal `"realms"` for every Realms world (no per-Realm distinction) |
| ~21 Apr 2023 (MC snapshot 23w14a) – 16 Jun 2024 | `session.getSessionId()` — unstable, session-scoped, unrelated to which Realm |
| 16 Jun 2024 onward | `Realm_<id>.<ownerUUID>` — stable and unique per Realm |

**1.17.1-era VoxelMap (the entire practical support window for our target)
falls in the leftmost cell of both changed-behavior tables**: it never
gained the numeric-id conversion (that landed on a codebase already past MC
1.20), and it only ever wrote the flat `"realms"` name for Realms data.

---

## Importer checklist (both formats)

1. Probe roots: `xaero/minimap`, `XaeroWaypoints`, `voxelmap`,
   `mods/mamiyaotaru/voxelmap`. For a real 1.17.1 install, `xaero/minimap`
   and the numeric legacy dimension-id form in Format V will essentially
   never be encountered (neither existed yet during 1.17.1's support
   window) — support them anyway for imported/copied data from other
   profiles, but do not expect them from genuinely native 1.17.1 files.
2. Match Format X container folders case-insensitively; decode the folder
   and file-name escapes before display.
3. Format X: coordinates are dimension-local per `dim%…` folder; Format V:
   x/z are overworld-scaled and must be divided by the target dimension's
   coordinate scale.
4. Treat unknown line types, unknown keys, and unparsable lines as
   skippable, never fatal.
5. Realms data needs special handling for our target version: Format X
   `Realms_<ownerUUID>.<id>` is correct for 1.17.1 (present from the first
   Fabric 1.17.1 build onward); Format V's equivalent for 1.17.1 is the flat
   literal file `realms.points`, shared by every Realm the player has ever
   joined — do not try to parse a VoxelMap Realms filename as
   `Realm_<id>.<uuid>`, that shape postdates 1.17.1 by over two years.
6. Never write to these files — import is read-only.

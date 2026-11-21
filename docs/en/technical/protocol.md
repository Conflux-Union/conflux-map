# Conflux Map server protocol

Conflux Map clients talk to a server through two Minecraft plugin-message channels:

- `confluxmap:map_sync` — handshake, policy, client feature restrictions, the entity radar,
  and the authoritative map-correction data plane.
- `confluxmap:waypoints_v1` — the server-owned shared waypoint catalog.

This document is the complete wire reference for both channels, written so a third-party
server-side implementation (a Paper plugin, a Fabric server mod, a proxy-adjacent service)
can interoperate with Conflux Map clients **without installing the Conflux Map companion**.
The shipped Fabric companion and Paper plugin are themselves just implementations of this
protocol.

Scope note: the message layer is documented byte-exactly. The compressed correction bodies
inside `MAP_PATCH`/`MAP_REGION_PATCH` carry their own versioned container (a DEFLATE stream
whose first decompressed byte is a format version); its internal layout is defined by
`PatchCodec`/`ChunkPatchCodec` in the client source and is only needed by an implementation
that wants to serve correction data.

## Contents

1. [Common framing rules](#1-common-framing-rules)
2. [`map_sync`: handshake and policy](#2-map_sync-handshake-and-policy)
3. [`map_sync`: client feature restrictions](#3-map_sync-client-feature-restrictions)
4. [`map_sync`: negotiation, capabilities, radar](#4-map_sync-negotiation-capabilities-radar)
5. [`map_sync`: message reference](#5-map_sync-message-reference)
6. [`waypoints_v1`: session and versioning](#6-waypoints_v1-session-and-versioning)
7. [`waypoints_v1`: message reference](#7-waypoints_v1-message-reference)
8. [Versioning and compatibility](#8-versioning-and-compatibility)
9. [Implementing a third-party server](#9-implementing-a-third-party-server)

## 1. Common framing rules

Both channels use Minecraft custom payloads ("plugin messages"). **One plugin message carries
exactly one frame**; there is no extra length prefix and no batching.

```
u8      typeId
...     type-specific body, big-endian
```

| Rule | Detail |
| --- | --- |
| Endianness | All multi-byte integers are big-endian (`u` = unsigned, `i` = signed). |
| String (`utf8`) | `u16 byteLength` + UTF-8 bytes. Hard cap **256 bytes** on both channels; malformed UTF-8 is rejected. |
| Byte blob (`bytes`) | `u32 length` + raw bytes, bounded per message (see each message). |
| Boolean (`bool`) | One byte, `0` or `1`. `waypoints_v1` rejects any other value; `map_sync` validates booleans per message. |
| UUID | 16 bytes: `i64 mostSignificantBits` + `i64 leastSignificantBits`. |
| Trailing bytes | Rejected: the frame must consume the payload exactly. |
| Unknown type ids | Rejected. |
| Direction | Each type id is valid in exactly one direction; a mismatch is rejected as a protocol error (the server counts it as a strike). |
| Payload cap | Server→client **1 MiB**; client→server **8 KiB** (both channels). |

Hostile-input discipline is expected on both ends: every count/length is checked against a
hardcoded cap before allocation, and violations are protocol errors, never crashes. The
shipped companion mutes a client after **3 malformed strikes** (until disconnect); a
third-party server should enforce something equivalent.

The client sends its handshake on **both** channels immediately on joining a server. On
`map_sync` it waits **100 ticks (5 s)** for a policy reply before concluding no compatible
server is present; on `waypoints_v1` the same 100-tick timeout applies. Everything resets on
disconnect.

## 2. `map_sync`: handshake and policy

### 2.1 HELLO (0x01, C2S)

Sent by the client once, at join:

```
u8      typeId = 0x01
utf8    modVersion          Conflux Map version string, e.g. "0.1.15"
utf8    predictorVersion    baseline id + embedded capability advertisement (below)
```

`predictorVersion` is a packed field. The current client sends:

```
<predictor id>|sync:1|wire:4.0|patch:3|region:1|patch:4|region:2|source-light:1|server-view:1|caps2:<base64url>
```

- `<predictor id>` (everything before the first `|`) identifies the native prediction
  baseline, e.g. `cb:9afc1038ea5a|shim:9|base:14`. Two peers with identical ids can exchange
  residual (delta) corrections; anything else falls back to absolute records.
- The `|token` list is the **legacy advertisement**: the presence of `sync:1` means the client
  understands negotiation, `wire:4.0` its protocol version, `patch:N`/`region:N` the patch
  codec versions it can decode, `server-view:1` the server-view-distance frame.
- `|caps2:<base64url>` (URL-safe alphabet, no padding) carries the modern offer:

```
u8      negotiationVersion = 2
u8      profileCount               correction profile ids, highest first (4,3,2,1)
u8 × N  profileId
u8      capabilityCount
u8 × M  capabilityId, capabilityVersion
```

A third-party server can **ignore the entire advertisement** and still deliver policy and
restrictions (see [§9](#9-implementing-a-third-party-server)). Parsing it is only needed to
select correction profiles or to advertise capabilities (e.g. radar positions).

### 2.2 Server reply order

The companion replies to HELLO with this sequence; only the last frame is mandatory:

| Order | Frame | Sent when | Purpose |
| --- | --- | --- | --- |
| 1 | `0x12` MAP_CAPABILITIES | client advertised `caps2` | extensible selection (modern clients) |
| 1' | `0x10` MAP_COMPATIBILITY | legacy-negotiation client only | fixed selection (released peers) |
| 2 | `0x07` FLAT_BASELINE | a dimension is superflat + capability 1 | flat-surface baseline |
| 3 | `0x15` QUADRA_LAYOUT | quadra-gen dims present + capability 9 | quadrant layouts |
| 4 | `0x11` SERVER_VIEW_DISTANCE | capability 6 | server chunk-send radius |
| 5 | `0x13` SERVER_INSTANCE | capability 7 | server-process identity |
| 6 | `0x19` FEATURE_RESTRICTIONS | at least one feature forbidden | client feature restrictions |
| 7 | `0x02` HELLO_POLICY | **always** | activates the client session |

Frames sent **before** `0x02` are decoded without capability checks. Once `0x02` arrives the
client session is ACTIVE and every capability-gated frame (see
[§4.2](#42-capability-gating)) must be covered by the negotiated selection, or the client
rejects it. A silent server (no `0x02` within 100 ticks) is treated as "no companion":
the client keeps working with local features only, but **restrictions already delivered are
still enforced** — `0x19` sent before the timeout binds even if no policy ever follows.

### 2.3 HELLO_POLICY (0x02, S2C)

```
u8      flags                       see bit table
utf8    worldId                     stable world identity (UUID string); client cache namespace
utf8    worldgenVersion             vanilla worldgen version string, e.g. "1.21.4"; "" is tolerated
i32     maxBytesPerSec              correction-stream budget (bytes/s per player)
u16     maxTilesPerReq              max tiles one view request may carry
u16     minReqIntervalMs            min spacing between client requests
u8      maxPatchLod                 highest LOD the server will patch (default 4)
u8      dimCount                    0..8
dimCount × {
    utf8    dimId                   resource id, e.g. "minecraft:overworld"
    utf8    dimType                 path component only, e.g. "overworld"
    u8      dimBits                 bit 0 predictable · bit 1 hasSeed · bits 2–4 preset wire id
    i64     seed                    meaningful only when hasSeed (and flags bit 0)
}
```

Flags byte:

| Bit | Name | Meaning |
| --- | --- | --- |
| `0x01` | seedGranted | per-dim seeds are real; otherwise clients must ignore seed fields |
| `0x02` | correctionsEnabled | the correction data plane is on |
| `0x04` | biomeMapForbidden | hide the biome map layer |
| `0x08` | chunkLoadStateEnabled | chunk load-state overlay is served |
| `0x10` | entityRadarForbidden | radar off; also stops the position stream |
| `0x20` | correctionInvalidationEnabled | server will push `0x0B` invalidations |
| `0x40` | chunkRangeCorrectionEnabled | region pages (`0x0C`–`0x0F`) are served |
| `0x80` | structureSearchForbidden | hide the structure finder |

All three "forbidden" bits are **negative**: an absent bit (or an absent frame from an older
server) means *allowed*. `dimIndex` used by later messages is the index in this dim list;
the list is stable for the whole connection. Preset wire ids: `0` default, `1` large biomes,
`2` amplified, `3` superflat, `4` debug, `5` custom/datapack.

Minimal valid reply for a restrictions-only server: flags as configured, a stable `worldId`,
any `worldgenVersion`, the default budgets (`262144, 8, 100, 4`), `dimCount = 0`. The client
activates with corrections disabled and enforces every restriction bit you sent.

### 2.4 POLICY_UPDATE (0x05, S2C)

Flags and budgets can change mid-session; `worldId`, `worldgenVersion`, and the dim list are
connection-stable and never appear here:

```
u8      flags       same bit table as HELLO_POLICY
i32     maxBytesPerSec
u16     maxTilesPerReq
u16     minReqIntervalMs
u8      maxPatchLod
```

Ignored if it arrives before the first `0x02`. Disabling flags (e.g. radar) makes the client
drop the corresponding state immediately. Note the shipped companion currently delivers
policy only at handshake — mid-session updates are part of the wire contract, and clients
honor them.

## 3. `map_sync`: client feature restrictions

### 3.1 FEATURE_RESTRICTIONS (0x19, S2C)

```
u8      bits        bit 0x01 minimap · bit 0x02 world map · bit 0x04 waypoints · bit 0x08 prediction
                    bits 0x10–0x80 are ignored by current clients
[u16 + utf8 message] present only when non-empty; ≤ 256 UTF-8 bytes
```

The trailing `message` is optional: encode it only when non-empty. A frame with `bits = 0`
and no message clears all restrictions.

| Bit | Restriction | What a cooperating client hides |
| --- | --- | --- |
| `0x01` | minimap | the HUD minimap **and** its info text (coordinates, biome, zoom) |
| `0x02` | world map | the fullscreen map and everything reached through it: export, annotations, custom map markers |
| `0x04` | waypoints | local waypoints: list and editor screens, in-world beams and labels, death points, waypoint teleport |
| `0x08` | prediction | the seed-prediction underlay, even on servers that share the seed |

Delivery rules:

- Send it **any time** — before `0x02` in the handshake (the companion slot) or mid-session.
  Each frame replaces the previous state; re-send on config changes for live effect.
- Absence of the frame (older server, nothing forbidden) means all features stay allowed.
- When a player tries to open a disabled surface, the client shows a restriction screen:
  your `message` if present, otherwise a translated default. Plain text only; single
  message for all locales.
- Enforcement is **honest-client only** and offers no in-game override: the official client
  obeys; a modified client can always ignore it. This matches the radar-restriction
  precedent.
- Client support is **not advertised** in HELLO. Clients older than the restriction release
  silently drop the frame; you cannot detect this at handshake. Plan deployments
  accordingly (see [§8.3](#83-client-release-support)).

Related restriction bits that predate `0x19` and live in the policy flags byte
(`0x02`/`0x05`): `entityRadarForbidden` (`0x10`), `biomeMapForbidden` (`0x04`),
`structureSearchForbidden` (`0x80`). A restriction server should set those in the flags byte
and use `0x19` for the four surfaces above — the two mechanisms are independent and compose.

### 3.2 Worked example

Forbid the minimap and world map with the message "Maps are disabled on this server"
(32 bytes):

```
19 03 00 20 4D 61 70 73 20 61 72 65 20 64 69 73 61 62 6C 65 64 20 6F 6E 20 74 68 69 73 20 73 65 72 76 65 72
```

Forbid everything, translated default message:

```
19 0F
```

## 4. `map_sync`: negotiation, capabilities, radar

### 4.1 MAP_CAPABILITIES (0x12, S2C) and MAP_COMPATIBILITY (0x10, S2C)

The selection frame answers the client's offer. Modern (`caps2`) clients get `0x12`:

```
u8      negotiationVersion = 2
utf8    serverModVersion
utf8    serverPredictorVersion
u8      correctionMode       0 residual · 1 absolute · 2 disabled
u8      reasonCode           0 none · 1 baseline mismatch · 2 no common wire
u8      correctionProfileId  1..4 (any known id; must match a mode the client offered)
u8      capabilityCount      ≤ 32
u8 × N   { u8 capabilityId, u8 version }   version ≥ 1, no duplicates
```

Legacy clients get `0x10` (fixed shape; superseded for new implementations — listed for
completeness):

```
u8      negotiationVersion = 1
utf8    serverModVersion
u16     protocolMajor = 4
u16     protocolMinor = 0
u8      patchCodecVersion
u8      regionCodecVersion
utf8    serverPredictorVersion
u8      correctionMode
u8      reasonCode
```

Correction profiles:

| Id | Profile | Patch codec | Region codec | Notes |
| --- | --- | --- | --- | --- |
| 1 | LEGACY_V1 | 3 | 1 | released wire shape |
| 2 | SOURCE_LIGHT_V2 | 4 | 2 | adds source-light metadata |
| 3 | MATERIAL_COLOR_V3 | 5 | 3 | adds block material identity |
| 4 | OVERLAY_V4 | 6 | 4 | adds per-column overlay material |

Residual mode additionally requires `serverPredictorVersion` to equal the client's
predictor id; otherwise use absolute or disabled.

### 4.2 Capability gating

Capabilities are selected in `0x12`; a frame whose capability was not selected is rejected
by the client after the session activates:

| Id | Capability | Version | Gates |
| --- | --- | --- | --- |
| 1 | FLAT_BASELINE | 2 | `0x07`; v2 adds `topMaterialId` per entry |
| 2 | LOAD_STATE | 1 | `0x08`, `0x09` |
| 3 | MAP_INVALIDATION | 1 | `0x0A`, `0x0B` |
| 4 | REGION_CORRECTION | 1 | `0x0C`, `0x0D` |
| 5 | REGION_INVALIDATION | 1 | `0x0E`, `0x0F` |
| 6 | SERVER_VIEW_DISTANCE | 1 | `0x11` |
| 7 | SERVER_INSTANCE | 1 | `0x13` |
| 8 | PLAYER_POSITIONS | 1 | `0x14` |
| 9 | QUADRA_LAYOUT | 2 | `0x15`; v2 adds `topMaterialId` per flat quadrant |
| 10 | DATAPACK_BIOME_LANGS | 1 | `0x16`, `0x17` |
| 11 | FULL_SYNC | 1 | `0x18` |

Important: a server that replies **without any selection frame** leaves the client on the
legacy capability set, which excludes `SERVER_VIEW_DISTANCE`, `SERVER_INSTANCE`,
`PLAYER_POSITIONS`, and `QUADRA_LAYOUT`. To deliver radar positions you **must** send `0x12`
selecting capability 8 (see [§9.2](#92-serving-radar-positions)).

### 4.3 PLAYER_POSITIONS (0x14, S2C)

The entity-radar feed. The companion broadcasts one snapshot **every 5 ticks** while the
radar is enabled, to every session that selected capability 8:

```
u16     entryCount              ≤ 2048
entryCount × {
    uuid    playerId
    utf8    name                non-empty
    utf8    dimensionId         non-empty, e.g. "minecraft:overworld"
    f64     x, y, z             finite
    u8      flags               bit 0x01 spectator; all other bits rejected
}
```

Each snapshot is complete (not a delta). The client ignores the feed when the policy flag
`entityRadarForbidden` is set. Positions in dimensions the receiving player cannot see are
handled client-side.

## 5. `map_sync`: message reference

Index — direction `C2S` = client→server, `S2C` = server→client:

| Id | Name | Dir | Cap. | Purpose |
| --- | --- | --- | --- | --- |
| 0x01 | HELLO | C2S | — | announce versions + offer |
| 0x02 | HELLO_POLICY | S2C | — | policy, budgets, dims; activates the session |
| 0x03 | MAP_VIEW_REQ | C2S | — | request tile corrections |
| 0x04 | MAP_PATCH | S2C | — | one tile's correction data |
| 0x05 | POLICY_UPDATE | S2C | — | mid-session flags/budgets |
| 0x06 | ERROR | S2C | — | structured error |
| 0x07 | FLAT_BASELINE | S2C | 1 | superflat baselines (pre-policy) |
| 0x08 | LOAD_STATE_SUBSCRIBE | C2S | 2 | (un)subscribe chunk load-state viewport |
| 0x09 | LOAD_STATE_DELTA | S2C | 2 | load-state snapshot/delta batch |
| 0x0A | MAP_SYNC_SUBSCRIBE | C2S | 3 | (un)subscribe correction invalidations |
| 0x0B | MAP_INVALIDATE | S2C | 3 | invalidated correction tiles |
| 0x0C | MAP_REGION_VIEW_REQ | C2S | 4 | request cropped region pages |
| 0x0D | MAP_REGION_PATCH | S2C | 4 | one region-page correction snapshot |
| 0x0E | MAP_REGION_SYNC_SUBSCRIBE | C2S | 5 | exact chunk-viewport invalidation sub |
| 0x0F | MAP_REGION_INVALIDATE | S2C | 5 | changed source regions |
| 0x10 | MAP_COMPATIBILITY | S2C | — | legacy selection |
| 0x11 | SERVER_VIEW_DISTANCE | S2C | 6 | effective chunk-send radius |
| 0x12 | MAP_CAPABILITIES | S2C | — | modern selection |
| 0x13 | SERVER_INSTANCE | S2C | 7 | server-process identity (pre-policy) |
| 0x14 | PLAYER_POSITIONS | S2C | 8 | radar position snapshot |
| 0x15 | QUADRA_LAYOUT | S2C | 9 | quadra-gen quadrant layouts (pre-policy) |
| 0x16 | BIOME_LANG_REQ | C2S | 10 | request datapack biome names |
| 0x17 | BIOME_LANG | S2C | 10 | datapack biome display names |
| 0x18 | MAP_FULL_SYNC_MANIFEST | S2C | 11 | full-sync manifest page |
| 0x19 | FEATURE_RESTRICTIONS | S2C | — | client feature restrictions |

### ERROR (0x06, S2C)

```
u8      code        1 rate-limited · 2 malformed request · 3 internal · 4 companion disabled
utf8    detail      human-readable; clients log it and may toast it
```

The client never tears down the channel on ERROR; only channel-level violations (strike
mutes) do.

### MAP_VIEW_REQ (0x03, C2S) and MAP_PATCH (0x04, S2C)

```
0x03:   i32 reqId · u8 dimIndex · u8 lod (≤ 4) · u8 tileCount (≤ 8)
        tileCount × { i32 tileX · i32 tileZ · i64 sinceRevision }   (i64.MIN = cold)

0x04:   i32 reqId · u8 dimIndex · u8 lod · i32 tileX · i32 tileZ · u8 mode · i64 tileRevision
        32 B presence bitmap · u8 structureCount (≤ 255)
        structureCount × { u8 structType · i32 blockX · i32 blockZ · u8 state }
        bytes body (≤ 576 KiB)
```

A tile is 256×256 output pixels; world span is `256 << lod` blocks. `mode`: `0` unchanged
(client revision matched, empty body), `1` residual, `2` absolute, `3` unavailable
(prediction only, empty body), `4` partial progress for a pending revision. The client
echoes `tileRevision` back as `sinceRevision` on later requests. The presence bitmap is one
coarse diagnostic bit per 16×16 output-pixel cell; exact coverage lives in the body.

### LOAD_STATE_SUBSCRIBE (0x08, C2S) / LOAD_STATE_DELTA (0x09, S2C)

```
0x08:   i32 subscriptionId · u8 dimIndex · bool active
        i32 minChunkX · i32 minChunkZ · i32 maxChunkX · i32 maxChunkZ   (span ≤ 4096 when active)

0x09:   i32 subscriptionId · u8 flags (bit 0x01 reset · bit 0x02 complete)
        u16 entryCount (≤ 512)
        entryCount × { i32 chunkX · i32 chunkZ · u8 level · u8 band }
```

`active = 0` cancels the subscription (bounds ignored). `level` is the effective chunk
ticket level; `0xFF` is the unloaded sentinel and must pair with band `0`. Bands: `0`
unloaded, `1` border, `2` block-ticking, `3` entity-ticking; band and level must agree.

### MAP_SYNC_SUBSCRIBE (0x0A, C2S) / MAP_INVALIDATE (0x0B, S2C)

```
0x0A:   u8 dimIndex · u8 lod · bool active
        i32 minTileX · i32 maxTileX · i32 minTileZ · i32 maxTileZ   (≤ 256 tiles total when active)

0x0B:   u8 dimIndex · u8 lod · u16 tileCount (1..256)
        tileCount × { i32 tileX · i32 tileZ }
```

The invalidation stream tells the client which cached correction tiles changed on the server
(only meaningful alongside the correction data plane).

### MAP_REGION_VIEW_REQ (0x0C, C2S) / MAP_REGION_PATCH (0x0D, S2C)

```
0x0C:   i32 reqId · u8 dimIndex · u8 lod · u8 regionCount (1..32)
        regionCount × {
            i32 regionX · i32 regionZ
            u8 minLocalChunkX · u8 minLocalChunkZ · u8 maxLocalChunkX · u8 maxLocalChunkZ   (0..31)
            i64 sinceRevision
        }

0x0D:   i32 reqId · u8 dimIndex · u8 lod · i32 regionX · i32 regionZ
        u8 minLocalChunkX · u8 minLocalChunkZ · u8 maxLocalChunkX · u8 maxLocalChunkZ
        u8 mode (0/3 empty body · 1/2 body) · i64 regionRevision
        bytes body (≤ 576 KiB, ChunkPatchCodec container)
```

A region page is a cropped 32×32-chunk summary region; the four local bounds select the
crop. Modes mirror MAP_PATCH.

### MAP_REGION_SYNC_SUBSCRIBE (0x0E, C2S) / MAP_REGION_INVALIDATE (0x0F, S2C)

```
0x0E:   u8 dimIndex · u8 lod · bool active
        i32 minChunkX · i32 maxChunkX · i32 minChunkZ · i32 maxChunkZ   (span ≤ 4096 when active)

0x0F:   u8 dimIndex · u8 lod · u16 regionCount (1..256)
        regionCount × { i32 regionX · i32 regionZ }
```

### SERVER_VIEW_DISTANCE (0x11, S2C)

```
u16     chunks      effective server chunk-send radius, ≤ 64
```

The client uses it to bound prediction re-seed work. Send pre-policy (slot 4).

### SERVER_INSTANCE (0x13, S2C)

```
utf8    instanceId      UUID string, regenerated per server process
```

Unlike `worldId` (stored in the save, survives copies) the instance id lives outside the
world, so clients use it to distinguish server processes sharing one world. Send pre-policy
(slot 5).

### FLAT_BASELINE (0x07, S2C)

```
u8      entryCount          1..8
entryCount × {
    u8   dimIndex
    u8   biomeId            cubiomes-space biome id
    i16  surfaceY           topmost non-air layer Y (−2048..2047)
    u8   kind               SurfaceKind ordinal (see below)
    u8   mapColorId         vanilla map color; 255 = none
    u8   fluidDepth         consecutive water layers below the surface
    [utf8 topMaterialId]    only when negotiated capability version ≥ 2
}
```

SurfaceKind ordinals: `0` unknown, `1` land, `2` water, `3` lava, `4` foliage, `5` snow,
`6` ice, `7` sand, `8` bedrock ceiling, `9` void. One entry per superflat dimension; sent
pre-policy (slot 2) so prediction is correct the moment the session opens.

### QUADRA_LAYOUT (0x15, S2C)

```
u8      entryCount          1..8
entryCount × {
    u8   dimIndex
    4 × u8 style            quadrant style ordinal: 0 noise · 1 cleared · 2 flat
    then, for each quadrant whose style = 2, in quadrant order:
        u8 biomeId · i16 surfaceY · u8 kind · u8 mapColorId · u8 fluidDepth
        [utf8 topMaterialId]    capability version ≥ 2 only
}
```

Quadrant order is +X+Z, +X−Z, −X+Z, −X−Z (block coordinate 0 on an axis belongs to the
positive side). Only FLAT quadrants carry baseline fields.

### BIOME_LANG_REQ (0x16, C2S) / BIOME_LANG (0x17, S2C)

```
0x16:   utf8 locale         e.g. "en_us"; sent by the client right after HELLO_POLICY

0x17:   u16 entryCount (≤ 1024) × { utf8 biomeId · utf8 displayName }
```

The server answers from the world's datapacks; an empty map when the packs ship none. The
companion throttles replies to one per 5 s per player.

### MAP_FULL_SYNC_MANIFEST (0x18, S2C)

```
u16     syncId · u8 dimIndex · u8 lod · u8 pageIndex · u8 pageCount (1..128) · u8 cancel
u16     regionCount (≤ 4096) × { i32 regionX · i32 regionZ }
```

Pages of a server-initiated bulk sync (the `/confluxmap fullsync` command). A cancel page is
`pageCount = 1, pageIndex = 0`, empty region list. The client then requests the listed
regions through the normal region-page path.

## 6. `waypoints_v1`: session and versioning

The shared-waypoint channel is fully independent of `map_sync` (it works even when map
corrections are off). Protocol version: **major 1, minor 7**.

Lifecycle:

1. The client sends HELLO (0x01) on join, unconditionally, and waits 100 ticks for STATUS.
2. The server replies STATUS (0x02) once per HELLO, and re-pushes STATUS whenever the
   player's operator status changes or the runtime feature switch flips.
3. The client sends SUBSCRIBE (0x03); the server answers SNAPSHOT (0x07). The client retries
   the subscription if it fails.
4. Mutations flow C2S with an `operationId` (client-generated UUID) and land as RESULT (0x0A)
   for the originator plus broadcast deltas (0x08/0x09/0x0C/0x0D) for every other subscribed
   peer. Results are idempotent per `operationId` and deliberately survive reconnects.

Version negotiation: the server negotiates `minor = min(client minor, 7)` and encodes every
later frame in the shape of that minor. The minor ladder:

| Negotiated ≥ | Added on the wire |
| --- | --- |
| 1 | `DUPLICATE_LOCATION` result error code |
| 2 | `ownerManagementAllowed` flag in STATUS |
| 3 | `iconItemId` + `markerLabel` strings (waypoints, CREATE, UPDATE) |
| 4 | groups: snapshot group list, waypoint `groupId`, GROUP_UPSERT/GROUP_REMOVE |
| 5 | group mutations C2S (0x0E–0x11) |
| 6 | MOVE_BATCH (0x12); CREATE/UPDATE carry an explicit group selection |
| 7 | waypoint `description` + `imageId` strings (waypoints, CREATE, UPDATE); S2C fragment envelopes |

Server-side limits: HELLO and SUBSCRIBE share a token bucket (burst 8, 60/min per
connection); mutations are additionally rate-limited by server config (default 30/min) and
per-world / per-player quotas advertised in STATUS. Three malformed strikes mute the
connection until disconnect.

Revision protocol: the catalog has one monotonically increasing `revision` (`i64`). Mutations
carry `expectedRevision` (optimistic concurrency — a mismatch yields `REVISION_CONFLICT`);
deltas and results carry the new revision after applying. MOVE_BATCH entries additionally
carry the per-waypoint revision they were planned against.

## 7. `waypoints_v1`: message reference

Booleans are strict `0`/`1` bytes. Direction index:

| Id | Name | Dir |
| --- | --- | --- |
| 0x01 | HELLO | C2S |
| 0x02 | STATUS | S2C |
| 0x03 | SUBSCRIBE | C2S |
| 0x04 | CREATE | C2S |
| 0x05 | DELETE | C2S |
| 0x06 | LOCK | C2S |
| 0x07 | SNAPSHOT | S2C |
| 0x08 | UPSERT | S2C |
| 0x09 | REMOVE | S2C |
| 0x0A | RESULT | S2C |
| 0x0B | UPDATE | C2S |
| 0x0C | GROUP_UPSERT | S2C |
| 0x0D | GROUP_REMOVE | S2C |
| 0x0E | GROUP_CREATE | C2S |
| 0x0F | GROUP_RENAME | C2S |
| 0x10 | GROUP_DELETE | C2S |
| 0x11 | SET_GROUP | C2S |
| 0x12 | MOVE_BATCH | C2S |

Shared sub-structures (shown once):

```
waypoint (negotiated minor in brackets):
    uuid id · uuid publisherId · utf8 publisherName · utf8 name
    utf8 dimensionId           resource id, e.g. "minecraft:the_nether"
    f64 x · f64 y · f64 z      finite
    i32 colorArgb
    u8  type                   0 normal · 1 death
    bool legacyLocked          always 0; kept for wire compatibility
    i64 createdAtEpochMs
    i64 revision
    [≥3] utf8 iconItemId       item id, e.g. "minecraft:red_banner"; "" = default icon
    [≥3] utf8 markerLabel      custom label override; "" = none
    [≥4] bool hasGroup [+ uuid groupId]
    [≥7] utf8 description      multi-line notes, ≤512 code points / ≤2048 UTF-8 bytes
    [≥7] utf8 imageId          canonical UUID of the preview image, or "" = none

group:
    uuid id · uuid creatorId · utf8 name · i64 createdAtEpochMs · i64 revision
```

### HELLO (0x01, C2S)

```
i32 major = 1 · i32 minor = 7
```

### STATUS (0x02, S2C)

```
i32 major · i32 minor
bool supported          false = server speaks the channel but declines (client goes quiet)
bool enabled            shared waypoints currently on
bool operator           receiving player has management rights
utf8 worldId            stable world identity
i64 revision            current catalog revision
i32 maxWorld · i32 maxPlayer    quotas
[≥2] bool ownerManagementAllowed   may non-operators manage their own entries
```

### SUBSCRIBE (0x03, C2S)

Empty body. Server answers SNAPSHOT; if disabled, answers STATUS instead with
`enabled = false`.

### SNAPSHOT (0x07, S2C)

```
i64 revision · bool operator
u16 waypointCount (≤ 512) × waypoint
[≥4] u16 groupCount (≤ 64) × group
```

A snapshot whose encoded size exceeds 512 KiB is split into transport-level fragment
envelopes (minor ≥ 7 only; below that a full snapshot always fits the S2C cap). Each
envelope is `[0x00] u8 seq · u8 count · bytes[≤ 512 KiB]` on the same channel; `0x00` is
never a valid legacy message type id, so unfragmented frames are unchanged. The receiver
concatenates the fragments in order and decodes the result as one message.

### CREATE (0x04, C2S) / UPDATE (0x0B, C2S)

```
uuid operationId · [UPDATE only: uuid id] · i64 expectedRevision
utf8 name · utf8 dimensionId · f64 x · f64 y · f64 z · i32 colorArgb · u8 type
[≥3] utf8 iconItemId · utf8 markerLabel
[≥6] bool hasGroup [+ uuid groupId]     explicit target group; false = ungroup
[≥7] utf8 description · utf8 imageId
```

### DELETE (0x05, C2S) / LOCK (0x06, C2S)

```
DELETE: uuid operationId · uuid id · i64 expectedRevision
LOCK:   uuid operationId · uuid id · i64 expectedRevision · bool locked
```

### Group mutations (0x0E–0x11, C2S; require minor ≥ 5)

```
GROUP_CREATE: uuid operationId · i64 expectedRevision · utf8 name
GROUP_RENAME: uuid operationId · i64 expectedRevision · uuid groupId · utf8 name
GROUP_DELETE: uuid operationId · i64 expectedRevision · uuid groupId
SET_GROUP:    uuid operationId · uuid waypointId · i64 expectedRevision · bool hasGroup [+ uuid groupId]
```

### MOVE_BATCH (0x12, C2S; minor ≥ 6)

```
uuid operationId · i64 expectedRevision
u16 entryCount (1..128) × {
    uuid waypointId · i64 expectedWaypointRevision
    bool hasGroup [+ uuid groupId]
}
```

Atomic: all entries move or none do.

### Deltas (0x08 / 0x09 / 0x0C / 0x0D, S2C)

```
UPSERT:        i64 revision · waypoint
REMOVE:        i64 revision · uuid id
GROUP_UPSERT:  i64 revision · group          (minor ≥ 4)
GROUP_REMOVE:  i64 revision · uuid groupId   (minor ≥ 4)
```

Broadcast to every subscribed, version-compatible peer. Group deltas have no minor-3
encoding and are skipped for older peers (they resync on the next snapshot).

### RESULT (0x0A, S2C)

```
uuid operationId · i32 statusCode · i32 errorCode
```

`statusCode`: `0` applied, `1` rejected. `errorCode`:

| Code | Meaning |
| --- | --- |
| 0 | none |
| 1 | invalid request |
| 2 | revision conflict |
| 3 | not found |
| 4 | forbidden |
| 5 | world quota exceeded (also group quota) |
| 6 | player quota exceeded |
| 7 | rate limited |
| 8 | persistence failed |
| 9 | id generation failed |
| 10 | disabled |
| 11 | duplicate location (minor ≥ 1) |

## 8. Versioning and compatibility

### 8.1 `map_sync`

Wire version: **4.0** (`PROTO_MAJOR = 4`, `PROTO_MINOR = 0`). Mismatched majors are
incompatible; minors are additive. History: minor 1 added per-dim generator presets, 2 the
flat baseline, 3 chunk load-state, 4 the radar policy and progressive coarse patches, 5
correction invalidation, 6 chunk-range region pages. From negotiation-aware peers onward
(`0x10`, then `0x12`), additions ride the capability/selection mechanism.

Additive S2C type ids (`0x11`–`0x19`) deliberately leave the minor at 0: the legacy
`wire:` token matches `major.minor` exactly, so a minor bump would knock every released
legacy peer out of residual sync. Unknown type ids are dropped by old clients — that is how
`0x19` shipped without a wire change.

### 8.2 `waypoints_v1`

Major 1; minors are the ladder in [§6](#6-waypoints_v1-session-and-versioning). A server
must answer with its own minor and encode to the negotiated shape.

### 8.3 Client release support

- The policy flags (radar, biome map, structure search) and the whole correction/radar
  data plane have been in released clients for many versions.
- `0x19` FEATURE_RESTRICTIONS merged on 2026-11-03 and ships with the first client release
  published after that date (`0.1.15-beta.2` was still unreleased at the time of writing).
  Older clients silently drop the frame; there is no handshake advertisement to detect
  support. Until your player base updates, treat `0x19` as progressive hardening on top of
  the flags-byte restrictions.

## 9. Implementing a third-party server

### 9.1 Restrictions-only server (Paper example)

The minimal integration answers the client's HELLO with an optional `0x19` plus a
`HELLO_POLICY` that enables nothing. No negotiation parsing required.

```java
public final class ConfluxRestrictions implements PluginMessageListener {
    private static final String CHANNEL = "confluxmap:map_sync";
    private final JavaPlugin plugin;

    ConfluxRestrictions(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] payload) {
        if (!CHANNEL.equals(channel) || payload.length == 0 || (payload[0] & 0xFF) != 0x01) {
            return; // only answer HELLO (0x01)
        }
        try {
            // 0x19: forbid minimap + world map with a custom message
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buf);
            out.writeByte(0x19);
            out.writeByte(0x01 | 0x02);                      // minimap | world map
            writeUtf(out, "Maps are disabled on this server");
            player.sendPluginMessage(plugin, CHANNEL, buf.toByteArray());

            // 0x02: minimal policy — corrections off, empty dims, default budgets
            buf = new ByteArrayOutputStream();
            out = new DataOutputStream(buf);
            out.writeByte(0x02);
            out.writeByte(0x10);                             // entityRadarForbidden
            writeUtf(out, player.getWorld().getUID().toString()); // worldId
            writeUtf(out, "");                               // worldgenVersion
            out.writeInt(256 * 1024);                        // maxBytesPerSec
            out.writeShort(8);                               // maxTilesPerReq
            out.writeShort(100);                             // minReqIntervalMs
            out.writeByte(4);                                // maxPatchLod
            out.writeByte(0);                                // dimCount
            player.sendPluginMessage(plugin, CHANNEL, buf.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().warning("conflux frame: " + e.getMessage());
        }
    }

    private static void writeUtf(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeShort(bytes.length);
        out.write(bytes);
    }
}
```

Registration (no `plugin.yml` channel declaration is required — the shipped Paper companion
registers the same channels without one):

```java
getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, listener);
getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
```

Notes:

- Send `0x19` before `0x02`, mirroring the companion. Both frames in one join is normal.
- On a config reload, re-send `0x19` directly — restrictions apply live, no reconnection
  needed. Flags-byte changes need `0x05` POLICY_UPDATE or a reconnect.
- You can also just listen for HELLO as a **Conflux Map client detector** (first frame a
  client ever sends on this channel) without replying at all.

### 9.2 Serving radar positions

Radar delivery requires the capability selection, because a policy-only reply leaves the
legacy capability set (no `PLAYER_POSITIONS`). Reply to HELLO with, in order:

1. `0x12` MAP_CAPABILITIES: `negotiationVersion = 2`, your version strings,
   `correctionMode = 2` (disabled), `reasonCode = 2`, `correctionProfileId = 2`,
   capabilities `{ (8, 1) }`.
2. `0x02` HELLO_POLICY as above (flags bit `0x10` only if you want the radar off).

Then broadcast `0x14` snapshots (every online player, all dimensions) every 5 ticks while
your radar policy allows. If you also select capability 10, clients will send you
`0x16` BIOME_LANG_REQ with their locale — you may answer `0x17` or ignore it.

### 9.3 Platform notes

- **Paper/Bukkit**: register both channels via `Messenger` as above; vanilla clients ignore
  the channel, so no per-player capability check is needed before sending.
- **Fabric server mod**: same channel names and payloads through the Fabric networking API
  (`ServerPlayNetworking`); nothing else changes.
- **Proxies**: plugin messages are transparently forwarded; no proxy-side handling is
  needed for any of this protocol.
- **Full data sync** (corrections, region pages, full sync): speak the messages in [§5]
  with a negotiated correction profile. This is the path of the shipped companion; expect
  to implement the summary/patch pipeline, not just the wire.

Detection of Conflux Map clients in general: a HELLO on either channel is the signal. Parse
`modVersion` from `0x01` if you need the client version.

## 10. `waypoint_images_v1`

An independent companion channel carrying waypoint preview images as opaque
PNG containers. It shares the shared-waypoint session's trust domain and
negotiation: the channel only answers clients whose `waypoints_v1`
negotiated minor reached 7, and it never negotiates on its own. Protocol
version: **major 1, minor 1**.

Availability: when the negotiated `waypoints_v1` minor is below 7 (old
server or old client), clients never send and servers never reply — the
channel is silent by construction.

### Messages

```
0x01 IMAGE_UPLOAD_BEGIN  (C2S): uuid imageId · u8 formatTag · i32 width · i32 height · i32 totalBytes (1..65536)
0x02 IMAGE_UPLOAD_CHUNK  (C2S): uuid imageId · i32 seq · u16 byteLen (1..16384) · bytes
0x03 IMAGE_FETCH         (C2S): uuid imageId
0x04 IMAGE_UPLOAD_RESULT (S2C): uuid imageId · u8 status
0x05 IMAGE_DATA          (S2C): uuid imageId · u8 formatTag · i32 byteLen (1..65536) · bytes
0x06 IMAGE_MISSING       (S2C): uuid imageId
```

`formatTag`: `1` = PNG (the only defined format). `status`: `0` OK,
`1` TOO_LARGE, `2` RATE_LIMITED, `3` QUOTA_EXCEEDED,
`4` DUPLICATE_OWNER_CONFLICT, `5` INVALID_FORMAT, `6` STORAGE_ERROR.
Receivers must treat any non-zero status as a generic failure; STORAGE_ERROR
covers a server-side persist fault and says nothing about the player's
quota.

Upload flow: BEGIN declares the full size and dimensions, CHUNKs follow in
order (each ≤ 16 KiB); every non-final chunk gets no reply, the completing
chunk gets RESULT. BEGIN is idempotent — re-declaring an image that already
exists under the same owner reports OK (safe against reconnect retries); the
same imageId under a different owner is refused with
DUPLICATE_OWNER_CONFLICT, so nobody can poison an image another player's
waypoint references. FETCH answers DATA or MISSING.

### Limits and quotas

- One image: 64 KiB on the wire; the vanilla C2S payload cap bounds a chunk
  frame at 32 KiB.
- Per player: 512 KiB of stored bytes (configurable), at most four concurrent
  pending uploads and 128 KiB of pending bytes.
- Per world: 2 MiB of stored bytes (configurable).
- Rates: uploads 10/min per player (whole images, not chunks; configurable),
  fetches 60/min per player.
- Malformed payloads strike the same three-strike mute as `waypoints_v1`.

Servers never decode image bytes — the container payload is opaque, which is
why BEGIN carries the true width/height for the stored header.

### Storage and GC

Server containers live at `<world>/confluxmap/images/<uuid>.img`, a
`CFXWIMG1` header (version, format, dimensions, owner) followed by the bytes.
The server waypoint file is the single source of truth for references:
periodically (and after edits) every non-empty `imageId` in it is collected
and unreferenced images older than 24 hours are deleted. The grace window
covers crash-after-upload-before-upsert and in-progress editing. Clients keep
fetched shared images memory-only and store only their own uploads under
`<gameDir>/confluxmap/waypoints/<serverId>/images/`.

### Ordering rule

A waypoint payload may reference an imageId before the image bytes arrive
(EDITOR → other clients): clients render a placeholder and fetch on demand.
Publishers upload the image before (or with) the waypoint mutation — the
shipped client uploads at capture/paste time, which satisfies the ordering
for every downstream path.

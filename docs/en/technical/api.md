# Conflux Map client API

Conflux Map ships a client-side API for third-party mods: waypoints, shared waypoints,
map-data queries, custom map markers, screen/teleport actions, and lifecycle events. The
API is **experimental** while the mod is on 0.x — interfaces may change between minor
versions; breaking changes are announced per release until the API is declared stable.

One published artifact covers **every supported Minecraft version** (1.17.1 through the
26.x line). The API is deliberately Minecraft-free: positions are plain doubles/ints,
dimensions are resource-id strings such as `"minecraft:the_nether"`, and icons are item-id
strings such as `"minecraft:red_banner"`. There is nothing to remap, so the same
dependency works on every version line.

## Getting the dependency

The API jar is published through JitPack:

```groovy
repositories {
    maven { url = 'https://jitpack.io' }
}

dependencies {
    compileOnly 'com.github.Conflux-Union.conflux-map:api:<tag>'
}
```

Replace `<tag>` with the Conflux Map release you target (e.g. `v0.1.6`). Use
`compileOnly`: the mod supplies the implementation at runtime. Declare the mod itself as
a `depends` in your `fabric.mod.json` so players install it:

```json
"depends": {
  "confluxmap": "*"
}
```

## Entrypoint

Declare a plugin under the `confluxmap` entrypoint key:

```json
"entrypoints": {
  "confluxmap": [ "com.example.ExamplePlugin" ]
}
```

```java
public final class ExamplePlugin implements ConfluxMapPlugin {
    @Override
    public void onConfluxMapInitialize(ConfluxMapApi api) {
        api.events().sessionStarted().register(event ->
            System.out.println("joined " + event.worldId())
        );
    }
}
```

Plugins run on the client main thread after every Conflux Map service has started. A
throwing plugin is logged and skipped; other plugins still load. If you prefer not to use
the entrypoint, poll `ConfluxMapApi.instance()`, which is empty until client startup and
after client shutdown.

## Threading contract

- **Reads** return immutable snapshots and are safe from any thread.
- **Writes** return `CompletableFuture` and marshal themselves onto the client main
  thread when called from elsewhere; calling one on the main thread completes
  synchronously.
- **Events** fire on the client main thread. Listeners must not throw - one throwing
  listener aborts the remaining notifications of that event.
- Unregister what you register: `EventRegistration` and provider handles are
  `AutoCloseable`; close them when your mod disables.

## Waypoints

```java
WaypointApi waypoints = api.waypoints();

waypoints.list().forEach(w ->
    System.out.println(w.name() + " @ " + w.dimensionId() + " " + w.x() + "," + w.y() + "," + w.z())
);

// Missing position/dimension fields fall back to the player's current viewpoint.
waypoints.add(WaypointApi.WaypointEdit.builder()
        .name("Quest goal")
        .colorArgb(0xFF2ECC71)
        .iconItemId("minecraft:gold_ingot")
        .build())
    .thenAccept(mutation -> {
        if (mutation.result() == WaypointApi.WaypointMutation.Result.APPLIED) {
            UUID id = mutation.waypoint().id();
        }
    });

// Update: null fields keep the stored value.
waypoints.update(id, WaypointApi.WaypointEdit.builder().name("Renamed").build());

api.events().waypointsChanged().register(event ->
    event.waypoints().forEach(w -> redraw(w))
);
```

The default set (group) is the empty string `""`. Mutations persist through the mod's own
waypoint files and respect its read-only views.

## Shared waypoints

Read-only access to the server-published ("shared") waypoint catalog. The list mirrors
what the companion server last synchronized; observe `sharedWaypointsChanged` to redraw.

```java
SharedWaypointApi shared = api.sharedWaypoints();

SharedWaypointApi.SharedWaypointAccess access = shared.availability();
if (access.enabled() && access.synchronizedSnapshot()) {
    shared.list().forEach(w ->
        System.out.println(w.name() + " by " + w.publisherName() + " @ " + w.dimensionId())
    );
}

api.events().sharedWaypointsChanged().register(event ->
    event.waypoints().forEach(w -> redraw(w))
);
```

`availability()` reports the handshake: `supported` (server understands the protocol),
`enabled` (feature turned on), `synchronizedSnapshot` (list fetched at least once).
Creating, editing, and deleting shared waypoints is not part of the public surface yet -
the mod's own waypoint-list screen remains the write path.

## Map data

Queries are scoped to the player's current session; a dimension argument that does not
match the active dimension yields empty/false results, and everything goes empty between
sessions.

```java
MapDataApi mapData = api.mapData();

String dimension = mapData.currentDimension().orElseThrow();
boolean mapped = mapData.hasExploredChunk(dimension, ApiLayer.SURFACE, chunkX, chunkZ);
OptionalInt surface = mapData.surfaceYAt(dimension, ApiLayer.SURFACE, blockX, blockZ);
Optional<String> biome = mapData.biomeAt(dimension, ApiLayer.SURFACE, blockX, blockZ);
```

Prediction queries (`predictedBiomeIdAt`, `predictedSurfaceYAt`) read the
seed-prediction underlay and only return values for tiles the fullscreen map has
composed - open the map over the area first. Predicted biome ids are **cubiomes** biome
ids; their canonical names follow the 1.17-era vanilla biome names
(`CubiomesBiomeIds` in the mod maps ids to name candidates).

`snapshotTile` composes one 256x256 ARGB tile on a worker thread (`lod` 0 renders the
raw 256-block tile; each step halves the block span). This is what the mod's own PNG
export uses.

## Custom markers

Register a provider that returns data-driven markers; they render on the minimap and the
fullscreen map with the mod's waypoint-marker visuals (a colored plate, the item icon of
`iconItemId` when set, else the first character of `label`).

```java
try (EventRegistration ignored = api.markers().registerProvider("examplemod", () ->
    List.of(new MarkerApi.ApiMarker(
        "reactor-1", "minecraft:overworld", 120, 64, -340, "Reactor", 0xFFE74C3C,
        "minecraft:redstone", true, true, 0
    ))
)) {
    // markers refresh roughly every half second while registered
}
```

Providers are polled on the main thread about every half second - answer quickly, and do
not throw (a failing provider is skipped for that round and logged). Markers whose
position leaves the minimap frame are culled rather than clamped to the edge.

## Actions

```java
api.actions().openFullscreenMap();
api.actions().openWaypointEditor(waypointId);
api.actions().requestTeleport(waypointId).thenAccept(result -> {
    // STARTED, NO_WAYPOINT, NO_SESSION, or FORBIDDEN_BY_SERVER
});
api.actions().setLayerOverride(ActionApi.LayerOverride.UNDERGROUND);
```

Screen actions reuse the keybind dispatch, so the same guards apply (no session, another
screen open). Teleports go through the mod's ground-teleport path, honoring the
configured command template and server-side restrictions. `layerOverride` mirrors the
config: `AUTO`, `SURFACE`, `UNDERGROUND`; use `activeLayer()` to read which layer the map
shows right now (best called from the main thread).

## Waypoint-list opening defaults

When the mod's waypoint-list screen is created - including opens through its own hotkey -
it fires `waypointListOpening` with a **mutable request payload**. A listener can change
the tab and dimension filter the screen opens with:

```java
api.events().waypointListOpening().register(opening -> {
    if (opening.openedFromHotkey()) {
        opening.requestTab(ConfluxMapEvents.WaypointListTab.PUBLIC);  // needs sharedWaypointsEnabled()
        opening.requestAllDimensions();
    }
});
```

Later listeners override earlier ones. Requesting `PUBLIC` while shared waypoints are
unavailable keeps `LOCAL` (check `opening.sharedWaypointsEnabled()` first if you care).
The screen applies the mod's own config defaults before the event, so a request also
overrides those.

## Teleport landing resolver

The map's teleport resolves a landing height from captured/sampled ground, or stages
high and corrects once the target chunk loads. When it has **no landing answer of its
own** - a known void column, or an unknown column browsed from a non-live session - it
fires `teleportLandingResolve`, another **mutable query payload**. Supply a Y and the
teleport lands exactly there in one command:

```java
api.events().teleportLandingResolve().register(resolve -> {
    if (resolve.voidColumn() || isBrowsedArea(resolve.dimensionId())) {
        resolve.setLandingY(128);
    }
});
```

The event is never fired when the two-stage policy can still resolve real ground (a live
session with an unknown column), so a resolver cannot override a correct landing. The
same resolver also enables the map's teleport button for browsed unknown-height targets
that would otherwise be gated off.

## Events

| Event | Fires |
|---|---|
| `sessionStarted` / `sessionEnded` | joining / leaving a world; both fire on a world switch |
| `dimensionChanged` | dimension change within the same world |
| `fullscreenMapOpened` / `fullscreenMapClosed` | fullscreen map screen lifecycle |
| `waypointsChanged` | after any waypoint mutation, with the full new list |
| `waypointListOpening` | while the waypoint-list screen is created; **mutable** request payload |
| `sharedWaypointsChanged` | after the shared-waypoint catalog changes, with the full new list |
| `locationActionPerformed` | after a fullscreen-map location-menu action runs |
| `teleportLandingResolve` | when a teleport target has no landing of its own; **mutable** query payload |

`waypointListOpening` and `teleportLandingResolve` are the only payloads that are
mutable by design; everything else is an immutable snapshot.

## Compatibility notes

- The API module has zero dependencies and no Minecraft imports; compile against it with
  plain `compileOnly` (no Loom remapping needed).
- The runtime implementation comes from the Conflux Map jar; never jar-in-jar the API
  artifact into your own mod.
- API version equals mod version. The `fabric.mod.json` of the mod advertises
  `"custom": {"confluxmap:api": {"version": ..., "state": "experimental"}}` if you want
  to check capabilities at runtime.

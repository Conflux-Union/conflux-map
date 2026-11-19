package cn.net.rms.confluxmap.core.waypoint.library;

import static org.junit.jupiter.api.Assertions.*;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import java.nio.file.*;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WaypointListMemoryTest {
    @TempDir Path temp;
    private WaypointListMemory memory() { return new WaypointListMemory(temp.resolve("native.json"), temp.resolve("halfmasa"), Runnable::run, LogManager.getLogger()); }
    @Test void mapPhotoModesMigrateOldSwitchesAndPersistIndependently() throws Exception {
        Files.writeString(temp.resolve("native.json"), """
            {"version":1,"uiRevision":2,"options":{"screenshotsOnMap":true,"mapPhotoPreview":false},"worlds":{}}
            """);
        var memory = memory();
        assertEquals(WaypointListMemory.MapPhotoMode.ICON, memory.options().screenshotMode);
        assertEquals(WaypointListMemory.MapPhotoMode.OFF, memory.options().waypointPhotoMode);
        memory.options().waypointPhotoMode = WaypointListMemory.MapPhotoMode.THUMBNAIL;
        memory.save();
        assertEquals(WaypointListMemory.MapPhotoMode.THUMBNAIL, memory().options().waypointPhotoMode);
        assertEquals(WaypointListMemory.MapPhotoMode.ICON, memory().options().screenshotMode);
    }
    @Test void screenshotShortcutRestoresLastVisibleModeAcrossRestart() {
        var initial = memory();
        initial.options().toggleScreenshots();
        assertEquals(WaypointListMemory.MapPhotoMode.ICON, initial.options().screenshotMode);
        initial.options().setScreenshotMode(WaypointListMemory.MapPhotoMode.THUMBNAIL);
        initial.options().toggleScreenshots();
        initial.save();
        var restored = memory().options();
        assertEquals(WaypointListMemory.MapPhotoMode.OFF, restored.screenshotMode);
        restored.toggleScreenshots();
        assertEquals(WaypointListMemory.MapPhotoMode.THUMBNAIL, restored.screenshotMode);
    }

    @Test void compactSizesDefaultAndPersistIndependently() throws Exception {
        Files.writeString(temp.resolve("native.json"), """
            {"version":1,"uiRevision":2,"options":{"compact":true},"worlds":{}}
            """);
        var memory = memory();
        assertEquals(100, memory.options().compactWidthPercent());
        assertEquals(100, memory.options().compactHeightPercent());
        memory.options().setCompactWidthPercent(12);
        memory.options().setCompactHeightPercent(475);
        memory.save();
        var restored = memory().options();
        assertEquals(30, restored.compactWidthPercent());
        assertEquals(300, restored.compactHeightPercent());
    }

    @Test void importsLegacyOnceAndKeepsNativePreferences() throws Exception {
        Path legacy = temp.resolve("halfmasa"); Files.createDirectories(legacy);
        String old = "{\"version\":1,\"worlds\":{\"local\\ntest\":{\"tab\":\"PUBLIC\",\"dimensionMode\":\"ALL\",\"search\":\"iron\",\"nativeScroll\":4,\"compactScroll\":2}}}";
        Files.writeString(legacy.resolve("conflux-waypoint-list.json"), old);
        Files.writeString(legacy.resolve("halfmasa.json"), "{\"Extensions\":{\"confluxMapWaypointCompact\":true,\"confluxMapWaypointDistance\":true,\"confluxMapWaypointOrder\":\"personal_first\"}}");
        var memory = memory(); var world = new WorldIdentity("local", "test");
        assertEquals("iron", memory.get(world).search()); assertFalse(memory.options().compact); assertTrue(memory.options().distance); assertTrue(memory.options().localFirst);
        memory.options().compact = false; memory.save();
        assertFalse(memory().options().compact); assertEquals(old, Files.readString(legacy.resolve("conflux-waypoint-list.json")));
        assertNull(memory.get(new WorldIdentity("local", "other")));
    }
    @Test void normalizesStateDoesNotPersistSelectionsAndHonorsRemember() throws Exception {
        var memory = memory(); var world = new WorldIdentity("local", "test");
        memory.put(world, new WaypointListMemory.State("invalid", "invalid", null, null, null, -1, -2));
        var state = memory().get(world); assertEquals("LOCAL", state.tab()); assertEquals("CURRENT", state.dimensionMode()); assertEquals(0, state.compactScroll());
        assertFalse(Files.readString(temp.resolve("native.json")).contains("selected"));
        memory.options().remember = false; assertNull(memory.get(world));
    }
    @Test void legacyImportRetriesOnlyPendingPreferencesAndPreservesNewNativeChoices() throws Exception {
        final Path legacy = Files.createDirectories(temp.resolve("halfmasa")).resolve("halfmasa.json");
        Files.writeString(legacy, """
            {"Extensions":{"confluxMapWaypointDistance":true,"confluxMapWaypointDimension":"invalid",
            "confluxMapPublicWaypoints":"invalid"}}
            """);
        final var first = memory();
        assertTrue(first.options().distance);
        assertFalse(first.options().dimension);
        assertFalse(first.options().unknownHeightFallback);
        first.options().defaultPublic = true;
        first.save();
        Files.writeString(legacy, """
            {"Extensions":{"confluxMapWaypointDistance":false,"confluxMapWaypointDimension":true,
            "confluxMapPublicWaypoints":false}}
            """);
        final var reopened = memory();
        assertTrue(reopened.options().distance);
        assertTrue(reopened.options().dimension);
        assertTrue(reopened.options().defaultPublic);
    }
    @Test void unsupportedAndMalformedNativeDocumentsAreNeverOverwritten() throws Exception {
        for (String invalid : new String[]{"broken", "{\"version\":2}"}) {
            Files.writeString(temp.resolve("native.json"), invalid); var memory = memory();
            memory.options().compact = true; memory.save();
            assertEquals(invalid, Files.readString(temp.resolve("native.json")));
        }
    }
    @Test void importsRealMaliLibBooleanHotkeyObjectsAndTemporaryBinding() throws Exception {
        Path legacy = temp.resolve("halfmasa"); Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("halfmasa.json"), "{\"Extensions\":{\"confluxMapWaypointCompact\":{\"enabled\":true,\"hotkey\":{\"keys\":\"\"}},\"confluxMapWaypointDimension\":{\"enabled\":true},\"confluxMapQuickPublish\":true,\"confluxMapTemporaryWaypoint\":{\"keys\":\"KP_PLUS\"}}}");
        var memory = memory(); assertFalse(memory.options().compact); assertTrue(memory.options().dimension);
        assertTrue(memory.options().quickPublish); assertEquals("KP_PLUS", memory.options().temporaryKeys);
        var root = new com.google.gson.JsonObject();
        assertTrue(WaypointListMemory.importTemporaryHotkey(root, memory.options().temporaryKeys));
        assertEquals("KP_PLUS", root.getAsJsonObject("hotkeys").getAsJsonObject("temporaryWaypoint").get("keys").getAsString());
        root.getAsJsonObject("hotkeys").getAsJsonObject("temporaryWaypoint").addProperty("keys", "");
        assertFalse(WaypointListMemory.importTemporaryHotkey(root, "KP_PLUS"));
        assertEquals("", root.getAsJsonObject("hotkeys").getAsJsonObject("temporaryWaypoint").get("keys").getAsString());
    }
    @Test void nativeLayoutMigrationRunsOnceAndRetainsFeaturePreferencesAndBrowsingData() throws Exception {
        Files.writeString(temp.resolve("native.json"), "{\"version\":1,\"options\":{\"compact\":true,\"favorites\":false,\"images\":false},\"worlds\":{\"local\\ntest\":{\"tab\":\"LOCAL\",\"search\":\"keep search\",\"nativeScroll\":3}}}");
        var memory = memory(); assertFalse(memory.options().compact);
        assertFalse(memory.options().favorites); assertFalse(memory.options().images);
        assertEquals("keep search", memory.get(new WorldIdentity("local", "test")).search());
        memory.options().compact = true; memory.save();
        assertTrue(memory().options().compact); // User may opt into compact after the one-time migration.
    }
    @Test void disabledFeatureSwitchesPersistIndependently() {
        var memory = memory(); var o = memory.options();
        o.favorites = false; o.details = false; o.images = false;
        o.listPhotoPreview = false; o.waypointPhotoMode = WaypointListMemory.MapPhotoMode.OFF; o.hoverActions = false;
        o.sourcePriority = false; o.temporaryWaypoint = false; o.unknownHeightFallback = false;
        memory.save(); var restored = memory().options();
        assertFalse(restored.favorites); assertFalse(restored.details); assertFalse(restored.images);
        assertFalse(restored.listPhotoPreview); assertEquals(WaypointListMemory.MapPhotoMode.OFF, restored.waypointPhotoMode); assertFalse(restored.hoverActions);
        assertFalse(restored.sourcePriority); assertFalse(restored.temporaryWaypoint); assertFalse(restored.unknownHeightFallback);
    }
}

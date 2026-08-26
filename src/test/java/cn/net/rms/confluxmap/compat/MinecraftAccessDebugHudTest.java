package cn.net.rms.confluxmap.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;
//#if MC>=260100
//$$ import net.minecraft.client.Minecraft;
//$$ import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
//#elseif MC>=12109
//$$ import net.minecraft.client.MinecraftClient;
//$$ import net.minecraft.client.gui.hud.DebugHud;
//$$ import net.minecraft.client.gui.hud.InGameHud;
//$$ import net.minecraft.client.gui.hud.debug.DebugHudProfile;
//$$ import net.minecraft.client.option.GameOptions;
//#endif
import org.junit.jupiter.api.Test;

final class MinecraftAccessDebugHudTest {
    //#if MC>=260100
    //$$ @Test
    //$$ void individualDebugEntryDoesNotCountAsTheFullDebugOverlay() throws Exception {
    //$$     assertFalse(MinecraftAccess.isFullDebugOverlayVisible(client(false, true)));
    //$$ }
    //$$
    //$$ @Test
    //$$ void fullDebugOverlayStillCountsAsVisible() throws Exception {
    //$$     assertTrue(MinecraftAccess.isFullDebugOverlayVisible(client(true, false)));
    //$$ }
    //$$
    //$$ private static Minecraft client(
    //$$     final boolean fullOverlayVisible,
    //$$     final boolean individualEntryVisible
    //$$ ) throws Exception {
    //$$     final Minecraft client = allocate(Minecraft.class);
    //$$     final DebugScreenEntryList entries = allocate(DebugScreenEntryList.class);
    //$$     set(entries, "currentlyEnabled", individualEntryVisible ? List.of(new Object()) : List.of());
    //$$     set(entries, "isOverlayVisible", fullOverlayVisible);
    //$$     set(client, "debugEntries", entries);
    //$$     return client;
    //$$ }
    //#elseif MC>=12109
    //$$ @Test
    //$$ void individualDebugEntryDoesNotCountAsTheFullDebugOverlay() throws Exception {
    //$$     assertFalse(MinecraftAccess.isFullDebugOverlayVisible(client(false, true)));
    //$$ }
    //$$
    //$$ @Test
    //$$ void fullDebugOverlayStillCountsAsVisible() throws Exception {
    //$$     assertTrue(MinecraftAccess.isFullDebugOverlayVisible(client(true, false)));
    //$$ }
    //$$
    //$$ private static MinecraftClient client(
    //$$     final boolean fullOverlayVisible,
    //$$     final boolean individualEntryVisible
    //$$ ) throws Exception {
    //$$     final MinecraftClient client = allocate(MinecraftClient.class);
    //$$     final DebugHudProfile profile = allocate(DebugHudProfile.class);
    //$$     set(profile, "visibleEntries", individualEntryVisible ? List.of(new Object()) : List.of());
    //$$     set(profile, "f3Enabled", fullOverlayVisible);
    //$$     set(client, "debugHudEntryList", profile);
    //$$
    //$$     final GameOptions options = allocate(GameOptions.class);
    //$$     set(options, "hudHidden", false);
    //$$     set(client, "options", options);
    //$$     final InGameHud inGameHud = allocate(InGameHud.class);
    //$$     final DebugHud debugHud = allocate(DebugHud.class);
    //$$     set(debugHud, "client", client);
    //$$     set(inGameHud, "debugHud", debugHud);
    //$$     set(client, "inGameHud", inGameHud);
    //$$     return client;
    //$$ }
    //#endif

    private static void set(final Object target, final String name, final Object value)
        throws ReflectiveOperationException {
        final Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static <T> T allocate(final Class<T> type) throws ReflectiveOperationException {
        final Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        final Field theUnsafe = unsafeType.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        return type.cast(
            unsafeType.getMethod("allocateInstance", Class.class).invoke(theUnsafe.get(null), type)
        );
    }
}

package cn.net.rms.confluxmap.compat;

import java.io.IOException;
import java.io.InputStream;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
//#if MC>=260200
//$$ import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
//#else
import net.minecraft.client.gui.screen.ingame.HandledScreen;
//#endif
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.resource.ResourceManager;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
//#if MC>=12111
//$$ import net.minecraft.command.permission.Permission;
//$$ import net.minecraft.command.permission.PermissionLevel;
//#endif

/** Small access seams for Minecraft methods whose signatures changed after 1.17.1. */
public final class MinecraftAccess {
    private MinecraftAccess() {
    }

    public static int viewDistance(final MinecraftClient client) {
        //#if MC>=12000
        //$$ return client.options.getViewDistance().getValue();
        //#else
        return client.options.viewDistance;
        //#endif
    }

    /** The client's selected language code (e.g. {@code "zh_cn"}). */
    public static String language(final MinecraftClient client) {
        //#if MC>=260100
        //$$ return client.options.languageCode;
        //#else
        return client.options.language;
        //#endif
    }

    /**
     * Identity of the current integrated server (null without one): lets callers cache
     * per-session values on the client thread and re-resolve when the instance changes.
     */
    public static Object singleplayerServerRef(final MinecraftClient client) {
        //#if MC>=260100
        //$$ return client.getSingleplayerServer();
        //#else
        return client.getServer();
        //#endif
    }

    /**
     * The current singleplayer world's save root, or null on a dedicated connection. Read
     * on the client thread; the MCA backfill scanner resolves region files under it.
     */
    public static java.nio.file.Path singleplayerSaveRoot(final MinecraftClient client) {
        //#if MC>=260100
        //$$ return client.getSingleplayerServer() == null
        //$$     ? null
        //$$     : client.getSingleplayerServer().getWorldPath(
        //$$         net.minecraft.world.level.storage.LevelResource.ROOT);
        //#else
        return client.getServer() == null
            ? null
            : client.getServer().getSavePath(net.minecraft.util.WorldSavePath.ROOT);
        //#endif
    }

    /**
     * The current singleplayer world's {@code datapacks} directory, or null on a dedicated
     * connection. Read on the client thread; the returned path stays valid for the whole
     * session. Datapack translations only ever exist server-side, and the companion stays
     * inactive in an unpublished singleplayer world, so the client reads the local level
     * itself instead of waiting for a network answer that never comes.
     */
    public static java.nio.file.Path singleplayerDatapacksDir(final MinecraftClient client) {
        //#if MC>=260100
        //$$ return client.getSingleplayerServer() == null
        //$$     ? null
        //$$     : client.getSingleplayerServer().getWorldPath(
        //$$         net.minecraft.world.level.storage.LevelResource.DATAPACK_DIR);
        //#else
        return client.getServer() == null
            ? null
            : client.getServer().getSavePath(net.minecraft.util.WorldSavePath.DATAPACKS);
        //#endif
    }

    /**
     * The player's biome-blend radius in blocks: the tint at a position is averaged over the
     * square that many blocks around it, and 0 turns blending off entirely.
     */
    public static int biomeBlendRadius(final MinecraftClient client) {
        //#if MC>=12000
        //$$ return client.options.getBiomeBlendRadius().getValue();
        //#else
        return client.options.biomeBlendRadius;
        //#endif
    }

    /**
     * Live video gamma. Tweakeroo Gamma Override deliberately writes through this vanilla
     * option, including values above the normal slider range, so no optional API is needed.
     */
    public static float gamma(final MinecraftClient client) {
        //#if MC>=12000
        //$$ return client.options.getGamma().getValue().floatValue();
        //#else
        return (float) client.options.gamma;
        //#endif
    }

    /** Whether the configured vanilla player-list key is currently held. */
    public static boolean isPlayerListKeyPressed(final MinecraftClient client) {
        //#if MC>=260100
        //$$ return client.options.keyPlayerList.isDown();
        //#elseif MC>=11800
        //$$ return client.options.playerListKey.isPressed();
        //#else
        return client.options.keyPlayerList.isPressed();
        //#endif
    }

    /** The active screen, whose owner moved from Minecraft to Gui in 26.2. */
    public static Screen screen(final MinecraftClient client) {
        //#if MC>=260200
        //$$ return client.gui.screen();
        //#else
        return client.currentScreen;
        //#endif
    }

    /** Changes the active screen through the version-appropriate owner. */
    public static void setScreen(final MinecraftClient client, final Screen screen) {
        //#if MC>=260200
        //$$ client.gui.setScreen(screen);
        //#else
        client.setScreen(screen);
        //#endif
    }

    /** Inventory-style screens also host JEI/REI overlays, so the minimap HUD must yield to them. */
    public static boolean isContainerScreen(final Screen screen) {
        //#if MC>=260200
        //$$ return screen instanceof AbstractContainerScreen<?>;
        //#else
        return screen instanceof HandledScreen<?>;
        //#endif
    }

    /** Whether the full vanilla debug overlay is visible. */
    public static boolean isFullDebugOverlayVisible(final MinecraftClient client) {
        //#if MC>=260100
        //$$ return client.debugEntries.isOverlayVisible();
        //#elseif MC>=12109
        //$$ return client.debugHudEntryList.isF3Enabled();
        //#elseif MC>=12002
        //$$ return client.getDebugHud().shouldShowDebugHud();
        //#else
        return client.options.debugEnabled;
        //#endif
    }

    /**
     * Whether the player hid the vanilla HUD with F1. The state lived in the options until
     * 26.1 (hideGui) and moved to the Hud itself (26.2 toggles it, isHidden reports it), so
     * HUD-side mods must hide with it — Fabric's HUD callbacks keep firing either way.
     */
    public static boolean isHudHidden(final MinecraftClient client) {
        //#if MC>=260200
        //$$ return client.gui.hud.isHidden();
        //#elseif MC>=260100
        //$$ return client.options.hideGui;
        //#else
        return client.options.hudHidden;
        //#endif
    }

    public static void sendChatMessage(final MinecraftClient client, final String message) {
        //#if MC>=12000
        //$$ if (client.getNetworkHandler() != null) {
        //$$     client.getNetworkHandler().sendChatMessage(message);
        //$$ }
        //#else
        if (client.player != null) {
            client.player.sendChatMessage(message);
        }
        //#endif
    }

    /** Whether the server exposed at least one named command to this player's command tree. */
    public static boolean canSendCommand(final MinecraftClient client, final String... commandNames) {
        if (client.player == null || client.getNetworkHandler() == null) {
            return false;
        }
        for (final String commandName : commandNames) {
            if (client.getNetworkHandler().getCommandDispatcher().getRoot().getChild(commandName) != null) {
                return true;
            }
        }
        return false;
    }

    /** Sends one command without the leading slash through the version-appropriate chat path. */
    public static void sendCommand(final MinecraftClient client, final String command) {
        //#if MC>=12000
        //$$ if (client.getNetworkHandler() != null) {
        //$$     client.getNetworkHandler().sendChatCommand(command);
        //$$ }
        //#else
        if (client.player != null) {
            client.player.sendChatMessage("/" + command);
        }
        //#endif
    }

    public static String playerName(final ServerPlayerEntity player) {
        //#if MC>=12003
        //$$ return player.getName().getString();
        //#else
        return player.getEntityName();
        //#endif
    }

    public static InputStream openResource(final ResourceManager resources, final Identifier id)
        throws IOException {
        //#if MC>=12000
        //$$ return resources.getResource(id)
        //$$     .orElseThrow(() -> new IOException("missing resource: " + id))
        //$$     .getInputStream();
        //#else
        return resources.getResource(id).getInputStream();
        //#endif
    }

    /** True when the default pack or a loaded resource pack provides the resource. */
    public static boolean resourceExists(final ResourceManager resources, final Identifier id) {
        //#if MC>=12000
        //$$ return resources.getResource(id).isPresent();
        //#else
        try {
            return resources.getResource(id) != null;
        } catch (final IOException e) {
            return false;
        }
        //#endif
    }

    public static void sendFeedback(
        final ServerCommandSource source,
        final Text message,
        final boolean broadcastToOps
    ) {
        //#if MC>=12000
        //$$ source.sendFeedback(() -> message, broadcastToOps);
        //#else
        source.sendFeedback(message, broadcastToOps);
        //#endif
    }

    public static boolean hasPermission(final ServerCommandSource source, final int level) {
        //#if MC>=12111
        //$$ return source.getPermissions().hasPermission(
        //$$     new Permission.Level(PermissionLevel.fromLevel(level))
        //$$ );
        //#else
        return source.hasPermissionLevel(level);
        //#endif
    }

    public static boolean hasPermission(final ServerPlayerEntity player, final int level) {
        //#if MC>=12111
        //$$ return player.getPermissions().hasPermission(
        //$$     new Permission.Level(PermissionLevel.fromLevel(level))
        //$$ );
        //#else
        return player.hasPermissionLevel(level);
        //#endif
    }
}

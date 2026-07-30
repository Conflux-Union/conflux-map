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
        //#if MC>=12100
        //$$ return client.options.getViewDistance().getValue();
        //#else
        return client.options.viewDistance;
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

    public static void sendChatMessage(final MinecraftClient client, final String message) {
        //#if MC>=12100
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
        //#if MC>=12100
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
        //#if MC>=12100
        //$$ return player.getName().getString();
        //#else
        return player.getEntityName();
        //#endif
    }

    public static InputStream openResource(final MinecraftClient client, final Identifier id) throws IOException {
        //#if MC>=12100
        //$$ return client.getResourceManager().getResource(id)
        //$$     .orElseThrow(() -> new IOException("missing resource: " + id))
        //$$     .getInputStream();
        //#else
        return client.getResourceManager().getResource(id).getInputStream();
        //#endif
    }

    public static void sendFeedback(
        final ServerCommandSource source,
        final Text message,
        final boolean broadcastToOps
    ) {
        //#if MC>=12100
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

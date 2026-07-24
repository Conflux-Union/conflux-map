package cn.net.rms.confluxmap.compat;

import java.io.IOException;
import java.io.InputStream;
import net.minecraft.client.MinecraftClient;
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

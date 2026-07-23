package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.compat.Texts;
import static net.minecraft.server.command.CommandManager.literal;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.server.shared.SharedWaypointService;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;

/** Operator controls for the shared-waypoint runtime kill switch. */
final class SharedWaypointCommands {
    private static boolean registered;

    private SharedWaypointCommands() {
    }

    static synchronized void register(final ConfluxMapCompanion companion) {
        if (registered) {
            return;
        }
        registered = true;
        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> dispatcher.register(
            literal("confluxmap")
                .then(literal("waypoints")
                    .requires(source -> source.hasPermissionLevel(2))
                    .then(literal("status").executes(context -> status(
                        companion,
                        context.getSource()
                    )))
                    .then(literal("enable")
                        .requires(source -> source.hasPermissionLevel(2))
                        .executes(context -> enable(companion, context.getSource())))
                    .then(literal("disable")
                        .requires(source -> source.hasPermissionLevel(2))
                        .executes(context -> disable(companion, context.getSource()))))
        ));
    }

    private static int status(
        final ConfluxMapCompanion companion,
        final ServerCommandSource source
    ) {
        final ServerConfig config = companion.config();
        final SharedWaypointService service = companion.sharedWaypoints();
        final long revision = service == null ? 0L : service.snapshot().revision();
        source.sendFeedback(Texts.literal(
            "Conflux Map shared waypoints: master=" + config.enabled
                + ", configured=" + config.shareWaypoints
                + ", enabled=" + companion.sharedWaypointsEnabled()
                + ", revision=" + revision
                + ", worldQuota=" + config.maxSharedWaypointsPerWorld
                + ", playerQuota=" + config.maxSharedWaypointsPerPlayer
        ), false);
        return 1;
    }

    private static int enable(
        final ConfluxMapCompanion companion,
        final ServerCommandSource source
    ) {
        final ConfluxMapCompanion.SharedWaypointToggleResult result =
            companion.enableSharedWaypoints(source.getServer());
        audit(source, "enable", result);
        return switch (result) {
            case ENABLED -> feedback(source, "Shared waypoints enabled and saved.");
            case ALREADY_ENABLED -> feedback(source, "Shared waypoints are already enabled.");
            case MASTER_DISABLED -> error(
                source,
                "Cannot enable shared waypoints while companion enabled=false."
            );
            case LOAD_FAILED -> error(
                source,
                "Shared waypoint storage could not be loaded; sharing remains disabled."
            );
            case SAVE_FAILED -> error(
                source,
                "Server config could not be saved; sharing remains disabled."
            );
            default -> error(source, "Unexpected shared waypoint enable result: " + result);
        };
    }

    private static int disable(
        final ConfluxMapCompanion companion,
        final ServerCommandSource source
    ) {
        final ConfluxMapCompanion.SharedWaypointToggleResult result =
            companion.disableSharedWaypoints(source.getServer());
        audit(source, "disable", result);
        return switch (result) {
            case DISABLED -> feedback(source, "Shared waypoints disabled and saved.");
            case ALREADY_DISABLED -> feedback(source, "Shared waypoints are already disabled.");
            case DISABLED_SAVE_FAILED -> error(
                source,
                "Shared waypoints are disabled for this runtime, but server config could not be saved."
            );
            default -> error(source, "Unexpected shared waypoint disable result: " + result);
        };
    }

    private static int feedback(final ServerCommandSource source, final String message) {
        source.sendFeedback(Texts.literal(message), true);
        return 1;
    }

    private static int error(final ServerCommandSource source, final String message) {
        source.sendError(Texts.literal(message));
        return 0;
    }

    private static void audit(
        final ServerCommandSource source,
        final String action,
        final ConfluxMapCompanion.SharedWaypointToggleResult result
    ) {
        ConfluxMapMod.LOGGER.info(
            "shared-waypoint admin actor={} action={} result={}",
            source.getName(),
            action,
            result
        );
    }
}

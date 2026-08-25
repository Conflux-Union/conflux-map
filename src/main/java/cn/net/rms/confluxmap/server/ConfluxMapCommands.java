package cn.net.rms.confluxmap.server;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.server.shared.SharedWaypointCommandService;
import cn.net.rms.confluxmap.server.shared.SharedWaypointService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
//#if MC>=12108
//$$ import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
//#else
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
//#endif
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

/** Server commands for the companion's player diagnostics and operator controls. */
final class ConfluxMapCommands {
    private static boolean registered;

    private ConfluxMapCommands() {
    }

    static synchronized void register(final ConfluxMapCompanion companion) {
        if (registered) {
            return;
        }
        registered = true;
        //#if MC>=12108
        //$$ CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
        //#else
        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> dispatcher.register(
        //#endif
            literal("confluxmap")
                .then(literal("waypoints")
                    .then(literal("list")
                        .executes(context -> list(companion, context.getSource(), 1))
                        .then(argument("page", integer(1)).executes(context -> list(
                            companion,
                            context.getSource(),
                            getInteger(context, "page")
                        ))))
                    .then(literal("add")
                        .requires(source -> source.getEntity() instanceof ServerPlayerEntity)
                        .then(argument("name", greedyString()).executes(context -> createHere(
                            companion,
                            context.getSource(),
                            getString(context, "name")
                        ))))
                    .then(literal("edit")
                        .then(argument("id", word())
                            .then(argument("name", greedyString()).executes(context -> rename(
                                companion,
                                context.getSource(),
                                getString(context, "id"),
                                getString(context, "name")
                            )))))
                    .then(literal("move")
                        .requires(source -> source.getEntity() instanceof ServerPlayerEntity)
                        .then(argument("id", word()).executes(context -> moveHere(
                            companion,
                            context.getSource(),
                            getString(context, "id")
                        ))))
                    .then(literal("delete")
                        .then(argument("id", word()).executes(context -> delete(
                            companion,
                            context.getSource(),
                            getString(context, "id")
                        ))))
                    .then(literal("status").executes(context -> status(
                        companion,
                        context.getSource()
                    )).requires(source -> MinecraftAccess.hasPermission(source, 2)))
                    .then(literal("enable")
                        .requires(source -> MinecraftAccess.hasPermission(source, 2))
                        .executes(context -> enable(companion, context.getSource())))
                    .then(literal("disable")
                        .requires(source -> MinecraftAccess.hasPermission(source, 2))
                        .executes(context -> disable(companion, context.getSource()))))
                .then(literal("performance")
                    .requires(source -> source.getEntity() instanceof ServerPlayerEntity)
                    .executes(context -> performance(companion, context.getSource())))
                .then(literal("webmap")
                    .requires(source -> source.getEntity() instanceof ServerPlayerEntity)
                    .then(literal("hide").executes(context -> webMapPrivacy(
                        companion, context.getSource(), true
                    )))
                    .then(literal("show").executes(context -> webMapPrivacy(
                        companion, context.getSource(), false
                    ))))
        ));
    }

    private static int list(
        final ConfluxMapCompanion companion,
        final ServerCommandSource source,
        final int pageNumber
    ) {
        final SharedWaypointCommandService commands = commands(companion, source);
        if (commands == null) {
            return error(source, "Shared waypoints are disabled on this server.");
        }
        final SharedWaypointCommandService.Page page = commands.list(pageNumber);
        if (!page.valid()) {
            return error(source, "Page must be between 1 and " + page.totalPages() + ".");
        }
        MinecraftAccess.sendFeedback(source, Texts.literal(
            "Shared waypoints (page " + page.page() + "/" + page.totalPages()
                + ", total " + page.totalWaypoints() + ")"
        ), false);
        if (page.entries().isEmpty()) {
            MinecraftAccess.sendFeedback(source, Texts.literal("No shared waypoints."), false);
            return 1;
        }
        for (final SharedWaypointCommandService.Entry entry : page.entries()) {
            MinecraftAccess.sendFeedback(source, Texts.literal(
                "[" + entry.idPrefix() + "] " + entry.waypoint().name()
                    + " by " + entry.waypoint().publisherName()
                    + " @ " + entry.waypoint().dimensionId()
                    + " (" + coordinate(entry.waypoint().x())
                    + ", " + coordinate(entry.waypoint().y())
                    + ", " + coordinate(entry.waypoint().z()) + ")"
            ), false);
            MinecraftAccess.sendFeedback(source, Texts.literal(entry.xaeroMessage()), false);
        }
        return 1;
    }

    private static int createHere(
        final ConfluxMapCompanion companion,
        final ServerCommandSource source,
        final String name
    ) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        final SharedWaypointCommandService commands = commands(companion, source);
        if (commands == null) {
            return error(source, "Shared waypoints are disabled on this server.");
        }
        final ServerPlayerEntity player = source.getPlayer();
        return result(
            source,
            commands.createHere(actor(source), position(player), name),
            "Shared waypoint uploaded."
        );
    }

    private static int rename(
        final ConfluxMapCompanion companion,
        final ServerCommandSource source,
        final String id,
        final String name
    ) {
        final SharedWaypointCommandService commands = commands(companion, source);
        return commands == null
            ? error(source, "Shared waypoints are disabled on this server.")
            : result(source, commands.rename(actor(source), id, name), "Shared waypoint renamed.");
    }

    private static int moveHere(
        final ConfluxMapCompanion companion,
        final ServerCommandSource source,
        final String id
    ) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        final SharedWaypointCommandService commands = commands(companion, source);
        if (commands == null) {
            return error(source, "Shared waypoints are disabled on this server.");
        }
        return result(
            source,
            commands.moveHere(actor(source), id, position(source.getPlayer())),
            "Shared waypoint moved."
        );
    }

    private static int delete(
        final ConfluxMapCompanion companion,
        final ServerCommandSource source,
        final String id
    ) {
        final SharedWaypointCommandService commands = commands(companion, source);
        return commands == null
            ? error(source, "Shared waypoints are disabled on this server.")
            : result(source, commands.delete(actor(source), id), "Shared waypoint deleted.");
    }

    private static SharedWaypointCommandService commands(
        final ConfluxMapCompanion companion,
        final ServerCommandSource source
    ) {
        if (!companion.sharedWaypointsEnabled()) {
            return null;
        }
        return new SharedWaypointCommandService(
            companion.sharedWaypoints(),
            UUID::randomUUID,
            mutation -> companion.onSharedWaypointCommandMutation(source.getServer(), mutation)
        );
    }

    private static SharedWaypointService.Actor actor(final ServerCommandSource source) {
        if (source.getEntity() instanceof final ServerPlayerEntity player) {
            return new SharedWaypointService.Actor(
                player.getUuid(), MinecraftAccess.playerName(player),
                MinecraftAccess.hasPermission(source, 2)
            );
        }
        return new SharedWaypointService.Actor(
            UUID.nameUUIDFromBytes(
                ("confluxmap-command:" + source.getName()).getBytes(StandardCharsets.UTF_8)
            ),
            source.getName(),
            MinecraftAccess.hasPermission(source, 2)
        );
    }

    private static SharedWaypointCommandService.Position position(final ServerPlayerEntity player) {
        return new SharedWaypointCommandService.Position(
            DimensionId.parse(player.getServerWorld().getRegistryKey().getValue().toString()),
            player.getX(), player.getY(), player.getZ()
        );
    }

    private static int result(
        final ServerCommandSource source,
        final SharedWaypointCommandService.Result result,
        final String success
    ) {
        if (result.applied()) {
            return feedback(source, success);
        }
        return error(source, switch (result.status()) {
            case INVALID_ID -> "Invalid waypoint ID.";
            case UNKNOWN_ID -> "Shared waypoint not found.";
            case AMBIGUOUS_ID -> "Waypoint ID prefix is ambiguous; use the longer ID shown by list.";
            case FORBIDDEN -> "You do not have permission to manage this shared waypoint.";
            case REJECTED -> mutationError(result.mutation().error());
            default -> "Shared waypoint command failed.";
        });
    }

    private static String mutationError(final SharedWaypointService.MutationError error) {
        return switch (error) {
            case INVALID_REQUEST -> "The waypoint name or position is invalid.";
            case REVISION_CONFLICT -> "The waypoint changed; run the list command and try again.";
            case NOT_FOUND -> "Shared waypoint not found.";
            case FORBIDDEN -> "You do not have permission to manage this shared waypoint.";
            case WORLD_QUOTA_EXCEEDED -> "The server shared-waypoint limit has been reached.";
            case PLAYER_QUOTA_EXCEEDED -> "Your shared-waypoint limit has been reached.";
            case RATE_LIMITED -> "Too many waypoint changes; try again shortly.";
            case DUPLICATE_LOCATION -> "A shared waypoint already exists at that block.";
            case PERSISTENCE_FAILED -> "The waypoint could not be saved.";
            case ID_GENERATION_FAILED -> "The server could not allocate a waypoint ID.";
            default -> "Shared waypoint command failed: " + error;
        };
    }

    private static String coordinate(final double value) {
        return Double.toString(value);
    }

    private static int performance(
        final ConfluxMapCompanion companion,
        final ServerCommandSource source
    ) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!companion.isEnabled()) {
            return error(source, "Conflux Map companion is disabled.");
        }
        final ServerPlayerEntity player = source.getPlayer();
        final java.util.List<SyncPerformanceMonitor.LodSnapshot> snapshots =
            companion.summaries().performance(player.getUuid());
        for (final String line : SyncPerformanceFormatter.format(
            snapshots,
            cn.net.rms.confluxmap.core.util.TileMath.MAX_LOD
        )) {
            MinecraftAccess.sendFeedback(source, Texts.literal(line), false);
        }
        return 1;
    }

    private static int webMapPrivacy(
        final ConfluxMapCompanion companion,
        final ServerCommandSource source,
        final boolean hidden
    ) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        final ServerPlayerEntity player = source.getPlayer();
        if (!companion.setWebMapHidden(player.getUuid(), hidden)) {
            return error(source, "Could not save the web-map privacy preference.");
        }
        return feedback(
            source,
            hidden ? "You are hidden from the public web map."
                : "You are visible on the public web map when player sharing is enabled."
        );
    }

    private static int status(
        final ConfluxMapCompanion companion,
        final ServerCommandSource source
    ) {
        final ServerConfig config = companion.config();
        final SharedWaypointService service = companion.sharedWaypoints();
        final long revision = service == null ? 0L : service.snapshot().revision();
        MinecraftAccess.sendFeedback(source, Texts.literal(
            "Conflux Map shared waypoints: master=" + config.enabled
                + ", configured=" + config.shareWaypoints
                + ", enabled=" + companion.sharedWaypointsEnabled()
                + ", revision=" + revision
                + ", worldQuota=" + config.maxSharedWaypointsPerWorld
                + ", playerQuota=" + config.maxSharedWaypointsPerPlayer
                + ", nonOperatorManagement=" + config.allowNonOperatorSharedWaypointManagement
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
        MinecraftAccess.sendFeedback(source, Texts.literal(message), true);
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

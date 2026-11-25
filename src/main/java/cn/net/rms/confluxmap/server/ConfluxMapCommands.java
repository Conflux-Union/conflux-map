package cn.net.rms.confluxmap.server;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.compat.Dimensions;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.core.util.CoordinateText;
import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import cn.net.rms.confluxmap.core.shared.SharedWaypointGroup;
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
                    .then(literal("group")
                        .then(literal("list").executes(context -> listGroups(
                            companion,
                            context.getSource()
                        )))
                        .then(literal("create")
                            .then(argument("name", greedyString()).executes(context -> createGroup(
                                companion,
                                context.getSource(),
                                getString(context, "name")
                            ))))
                        .then(literal("rename")
                            .then(argument("id", word())
                                .then(argument("name", greedyString()).executes(context -> renameGroup(
                                    companion,
                                    context.getSource(),
                                    getString(context, "id"),
                                    getString(context, "name")
                                )))))
                        .then(literal("delete")
                            .then(argument("id", word()).executes(context -> deleteGroup(
                                companion,
                                context.getSource(),
                                getString(context, "id")
                            ))))
                        .then(literal("assign")
                            .then(argument("waypoint", word())
                                .then(argument("group", word()).executes(context -> assignGroup(
                                    companion,
                                    context.getSource(),
                                    getString(context, "waypoint"),
                                    getString(context, "group")
                                ))))))
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
                    )))
                    .then(literal("approve").requires(source -> MinecraftAccess.hasPermission(source,2)).then(argument("image",word()).executes(context -> moderateImage(companion,context.getSource(),getString(context,"image"),"image-approve"))))
                    .then(literal("reject").requires(source -> MinecraftAccess.hasPermission(source,2)).then(argument("image",word()).executes(context -> moderateImage(companion,context.getSource(),getString(context,"image"),"image-reject"))))
                    .then(literal("delete-image").requires(source -> MinecraftAccess.hasPermission(source,2)).then(argument("image",word()).executes(context -> moderateImage(companion,context.getSource(),getString(context,"image"),"image-delete"))))
                    .then(literal("pending-images").requires(source -> MinecraftAccess.hasPermission(source,2)).executes(context -> pendingImages(companion,context.getSource()))))
                .then(literal("fullsync")
                    .requires(source -> source.getEntity() instanceof ServerPlayerEntity
                        && MinecraftAccess.hasPermission(source, 2))
                    .executes(context -> fullSync(companion, context.getSource(), false))
                    .then(literal("cancel").executes(context -> fullSync(
                        companion, context.getSource(), true
                    ))))
        ));
    }

    private static int moderateImage(ConfluxMapCompanion companion,ServerCommandSource source,String id,String action){
        try{var f=companion.webFeatures();return f!=null&&f.moderate(UUID.fromString(id),action)?feedback(source,"Image updated"):error(source,"Image unavailable");}catch(IllegalArgumentException e){return error(source,"Invalid image ID");}
    }
    private static int pendingImages(ConfluxMapCompanion companion,ServerCommandSource source){var f=companion.webFeatures();if(f==null)return error(source,"Web map disabled");for(String item:f.pending())MinecraftAccess.sendFeedback(source,Texts.literal(item),false);return 1;}
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
                    + " (" + CoordinateText.plain(entry.waypoint().x())
                    + ", " + CoordinateText.plain(entry.waypoint().y())
                    + ", " + CoordinateText.plain(entry.waypoint().z()) + ")"
                    + groupSuffix(companion, entry.waypoint())
            ), false);
            MinecraftAccess.sendFeedback(source, Texts.literal(entry.xaeroMessage()), false);
        }
        return 1;
    }

    /** Resolves the waypoint's group name for the list output, or an empty suffix when ungrouped. */
    private static String groupSuffix(final ConfluxMapCompanion companion, final SharedWaypoint waypoint) {
        if (waypoint.groupId() == null) {
            return "";
        }
        final SharedWaypointService service = companion.sharedWaypoints();
        if (service == null) {
            return "";
        }
        for (final SharedWaypointGroup group : service.groups()) {
            if (group.id().equals(waypoint.groupId())) {
                return " in [" + group.name() + "]";
            }
        }
        return "";
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

    private static int listGroups(
        final ConfluxMapCompanion companion,
        final ServerCommandSource source
    ) {
        final SharedWaypointCommandService commands = commands(companion, source);
        if (commands == null) {
            return error(source, "Shared waypoints are disabled on this server.");
        }
        final java.util.List<SharedWaypointCommandService.GroupEntry> groups = commands.listGroups();
        if (groups.isEmpty()) {
            return feedback(source, "No shared waypoint groups.");
        }
        MinecraftAccess.sendFeedback(source, Texts.literal(
            "Shared waypoint groups (total " + groups.size() + ")"
        ), false);
        for (final SharedWaypointCommandService.GroupEntry entry : groups) {
            MinecraftAccess.sendFeedback(source, Texts.literal(
                "[" + entry.idPrefix() + "] " + entry.group().name()
            ), false);
        }
        return 1;
    }

    private static int createGroup(
        final ConfluxMapCompanion companion,
        final ServerCommandSource source,
        final String name
    ) {
        final SharedWaypointCommandService commands = commands(companion, source);
        if (commands == null) {
            return error(source, "Shared waypoints are disabled on this server.");
        }
        final SharedWaypointCommandService.Result result =
            commands.createGroup(actor(source), name);
        if (result.applied()) {
            return feedback(source, "Shared waypoint group created.");
        }
        return groupError(source, result);
    }

    private static int renameGroup(
        final ConfluxMapCompanion companion,
        final ServerCommandSource source,
        final String id,
        final String name
    ) {
        final SharedWaypointCommandService commands = commands(companion, source);
        if (commands == null) {
            return error(source, "Shared waypoints are disabled on this server.");
        }
        final SharedWaypointCommandService.Result result =
            commands.renameGroup(actor(source), id, name);
        if (result.applied()) {
            return feedback(source, "Shared waypoint group renamed.");
        }
        return groupError(source, result);
    }

    private static int deleteGroup(
        final ConfluxMapCompanion companion,
        final ServerCommandSource source,
        final String id
    ) {
        final SharedWaypointCommandService commands = commands(companion, source);
        if (commands == null) {
            return error(source, "Shared waypoints are disabled on this server.");
        }
        final SharedWaypointCommandService.Result result =
            commands.deleteGroup(actor(source), id);
        if (result.applied()) {
            return feedback(source, "Shared waypoint group deleted; its waypoints are now ungrouped.");
        }
        return groupError(source, result);
    }

    private static int assignGroup(
        final ConfluxMapCompanion companion,
        final ServerCommandSource source,
        final String waypointId,
        final String groupId
    ) {
        final SharedWaypointCommandService commands = commands(companion, source);
        if (commands == null) {
            return error(source, "Shared waypoints are disabled on this server.");
        }
        final SharedWaypointCommandService.Result result =
            commands.setWaypointGroup(actor(source), waypointId, groupId);
        if (result.applied()) {
            return feedback(source, "Shared waypoint group membership updated.");
        }
        return groupError(source, result);
    }

    private static int groupError(
        final ServerCommandSource source,
        final SharedWaypointCommandService.Result result
    ) {
        return error(source, switch (result.status()) {
            case INVALID_ID -> "Invalid group or waypoint ID.";
            case UNKNOWN_ID -> "Shared waypoint or group not found.";
            case AMBIGUOUS_ID -> "ID prefix is ambiguous; use the longer ID shown by list.";
            case FORBIDDEN -> "You do not have permission to manage this shared waypoint group.";
            case REJECTED -> groupMutationError(result.mutation().error());
            default -> "Shared waypoint group command failed.";
        });
    }

    private static String groupMutationError(final SharedWaypointService.MutationError error) {
        return switch (error) {
            case INVALID_REQUEST -> "The group name is invalid.";
            case REVISION_CONFLICT -> "The group or waypoint changed; run the list command and try again.";
            case NOT_FOUND -> "Shared waypoint or group not found.";
            case FORBIDDEN -> "You do not have permission to manage this shared waypoint group.";
            case RATE_LIMITED -> "Too many waypoint changes; try again shortly.";
            case GROUP_QUOTA_EXCEEDED -> "The server shared-waypoint group limit has been reached.";
            case PERSISTENCE_FAILED -> "The group could not be saved.";
            case ID_GENERATION_FAILED -> "The server could not allocate a group ID.";
            default -> "Shared waypoint group command failed: " + error;
        };
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
            Dimensions.of(player.getServerWorld()),
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

    /**
     * Operator-only, deliberate opt-in ({@code fullSyncEnabled}): pushes the dimension's whole
     * generated-region manifest to the executing player's Conflux Map client, which then
     * bulk-pulls every region. Cancel asks the client to stop a running sync.
     */
    private static int fullSync(
        final ConfluxMapCompanion companion,
        final ServerCommandSource source,
        final boolean cancel
    ) {
        if (!(source.getEntity() instanceof final ServerPlayerEntity player)) {
            return error(source, "[ConfluxMap] Full sync must be run by a player.");
        }
        if (!companion.isEnabled() || !companion.config().shareCorrections) {
            return error(source, "[ConfluxMap] Map sync is disabled on this server.");
        }
        if (!companion.config().fullSyncEnabled) {
            return error(source, "[ConfluxMap] Full sync is disabled on this server "
                + "(config/confluxmap/server.json: fullSyncEnabled).");
        }
        final ServerNetworking networking = companion.networking();
        if (!networking.canFullSync(player)) {
            return error(source, "[ConfluxMap] Full sync needs the Conflux Map client mod "
                + "with map corrections active.");
        }
        if (cancel) {
            return networking.cancelFullSync(player)
                ? feedback(source, "[ConfluxMap] Full sync cancel sent.")
                : error(source, "[ConfluxMap] No companion session for that player.");
        }
        networking.startFullSync(source.getServer(), player, message -> feedback(source, message));
        return feedback(source, "[ConfluxMap] Full sync requested; scanning region files...");
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
            "[ConfluxMap] shared-waypoint admin actor={} action={} result={}",
            source.getName(),
            action,
            result
        );
    }
}

package cn.net.rms.confluxmap.paper;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.util.CoordinateText;
import cn.net.rms.confluxmap.core.util.TileMath;
import cn.net.rms.confluxmap.server.ServerConfig;
import cn.net.rms.confluxmap.server.SyncPerformanceFormatter;
import cn.net.rms.confluxmap.server.shared.SharedWaypointCommandService;
import cn.net.rms.confluxmap.server.shared.SharedWaypointService;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.slf4j.Logger;

/** Paper command adapter for companion diagnostics and shared-waypoint controls. */
final class PaperCommands implements CommandExecutor, TabCompleter {
    private final PaperCompanion companion;
    private final Logger logger;

    PaperCommands(final PaperCompanion companion, final Logger logger) {
        this.companion = companion;
        this.logger = logger;
    }

    @Override
    public boolean onCommand(
        final CommandSender sender,
        final Command command,
        final String label,
        final String[] args
    ) {
        if (args.length == 1 && "performance".equalsIgnoreCase(args[0])) {
            return performance(sender);
        }
        if (args.length >= 2 && "waypoints".equalsIgnoreCase(args[0])) {
            return waypoints(sender, args);
        }
        if (args.length == 2 && "webmap".equalsIgnoreCase(args[0])
            && ("hide".equalsIgnoreCase(args[1]) || "show".equalsIgnoreCase(args[1]))) {
            return webMapPrivacy(sender, "hide".equalsIgnoreCase(args[1]));
        }
        if(args.length>=2&&"webmap".equalsIgnoreCase(args[0])&&sender.hasPermission("confluxmap.admin")){
            var f=companion.webFeatures();if(f==null){sender.sendMessage("Web map disabled");return true;}
            if(args[1].equals("pending-images")){for(String item:f.pending())sender.sendMessage(item);return true;}
            if(args.length==3){String action=switch(args[1]){case "approve"->"image-approve";case "reject"->"image-reject";case "delete-image"->"image-delete";default->"";};try{sender.sendMessage(f.moderate(UUID.fromString(args[2]),action)?"Image updated":"Image unavailable");}catch(IllegalArgumentException e){sender.sendMessage("Invalid image ID");}return true;}
        }
        sender.sendMessage("Usage: /confluxmap <performance|webmap hide|show|waypoints list|add|edit|move|delete|group ...>");
        return true;
    }

    @Override
    public List<String> onTabComplete(
        final CommandSender sender,
        final Command command,
        final String alias,
        final String[] args
    ) {
        if (args.length == 1) {
            return prefix(args[0], List.of("performance", "webmap", "waypoints"));
        }
        if (args.length == 2 && "waypoints".equalsIgnoreCase(args[0])
        ) {
            final List<String> values = sender.hasPermission("confluxmap.admin")
                ? List.of("list", "add", "edit", "move", "delete", "group", "status", "enable", "disable")
                : List.of("list", "add", "edit", "move", "delete", "group");
            return prefix(args[1], values);
        }
        if (args.length == 3 && "waypoints".equalsIgnoreCase(args[0])
            && "group".equalsIgnoreCase(args[1])) {
            return prefix(args[2], List.of("list", "create", "rename", "delete", "assign"));
        }
        if (args.length == 2 && "webmap".equalsIgnoreCase(args[0])) {
            return prefix(args[1], List.of("hide", "show"));
        }
        return List.of();
    }

    private boolean waypoints(final CommandSender sender, final String[] args) {
        final String action = args[1].toLowerCase(Locale.ROOT);
        if ("list".equals(action) && (args.length == 2 || args.length == 3)) {
            final int page;
            try {
                page = args.length == 2 ? 1 : Integer.parseInt(args[2]);
            } catch (final NumberFormatException ignored) {
                sender.sendMessage("Page must be a positive number.");
                return true;
            }
            return list(sender, page);
        }
        if ("add".equals(action) && args.length >= 3) {
            return createHere(sender, join(args, 2));
        }
        if ("edit".equals(action) && args.length >= 4) {
            return rename(sender, args[2], join(args, 3));
        }
        if ("move".equals(action) && args.length == 3) {
            return moveHere(sender, args[2]);
        }
        if ("delete".equals(action) && args.length == 3) {
            return delete(sender, args[2]);
        }
        if ("group".equals(action) && args.length >= 3) {
            return group(sender, args);
        }
        if (!sender.hasPermission("confluxmap.admin")) {
            sender.sendMessage("Only administrators may perform this action.");
            return true;
        }
        if (args.length == 2) {
            return switch (action) {
                case "status" -> status(sender);
                case "enable" -> enable(sender);
                case "disable" -> disable(sender);
                default -> usage(sender);
            };
        }
        return usage(sender);
    }

    private boolean list(final CommandSender sender, final int pageNumber) {
        final SharedWaypointCommandService commands = commands();
        if (commands == null) {
            sender.sendMessage("Shared waypoints are disabled on this server.");
            return true;
        }
        final SharedWaypointCommandService.Page page = commands.list(pageNumber);
        final Map<UUID, String> groupNames = new HashMap<>();
        for (final SharedWaypointCommandService.GroupEntry group : commands.listGroups()) {
            groupNames.put(group.group().id(), group.group().name());
        }
        if (!page.valid()) {
            sender.sendMessage("Page must be between 1 and " + page.totalPages() + ".");
            return true;
        }
        sender.sendMessage(
            "Shared waypoints (page " + page.page() + "/" + page.totalPages()
                + ", total " + page.totalWaypoints() + ")"
        );
        if (page.entries().isEmpty()) {
            sender.sendMessage("No shared waypoints.");
            return true;
        }
        for (final SharedWaypointCommandService.Entry entry : page.entries()) {
            final String groupName = groupNames.get(entry.waypoint().groupId());
            sender.sendMessage(
                "[" + entry.idPrefix() + "] " + entry.waypoint().name()
                    + " by " + entry.waypoint().publisherName()
                    + " @ " + entry.waypoint().dimensionId()
                    + " (" + CoordinateText.plain(entry.waypoint().x())
                    + ", " + CoordinateText.plain(entry.waypoint().y())
                    + ", " + CoordinateText.plain(entry.waypoint().z()) + ")"
                    + (groupName == null ? "" : " in [" + groupName + "]")
            );
            sender.sendMessage(entry.xaeroMessage());
        }
        return true;
    }

    private boolean createHere(final CommandSender sender, final String name) {
        if (!(sender instanceof final Player player)) {
            sender.sendMessage("This command must be run by a player.");
            return true;
        }
        final SharedWaypointCommandService commands = commands();
        if (commands == null) {
            sender.sendMessage("Shared waypoints are disabled on this server.");
            return true;
        }
        return result(
            sender,
            commands.createHere(actor(sender), position(player), name),
            "Shared waypoint uploaded."
        );
    }

    private boolean rename(final CommandSender sender, final String id, final String name) {
        final SharedWaypointCommandService commands = commands();
        return commands == null
            ? disabled(sender)
            : result(sender, commands.rename(actor(sender), id, name), "Shared waypoint renamed.");
    }

    private boolean moveHere(final CommandSender sender, final String id) {
        if (!(sender instanceof final Player player)) {
            sender.sendMessage("This command must be run by a player.");
            return true;
        }
        final SharedWaypointCommandService commands = commands();
        return commands == null
            ? disabled(sender)
            : result(sender, commands.moveHere(actor(sender), id, position(player)), "Shared waypoint moved.");
    }

    private boolean delete(final CommandSender sender, final String id) {
        final SharedWaypointCommandService commands = commands();
        return commands == null
            ? disabled(sender)
            : result(sender, commands.delete(actor(sender), id), "Shared waypoint deleted.");
    }

    /** Handles {@code /confluxmap waypoints group <list|create|rename|delete|assign> ...}. */
    private boolean group(final CommandSender sender, final String[] args) {
        final SharedWaypointCommandService commands = commands();
        if (commands == null) {
            return disabled(sender);
        }
        final String action = args[2].toLowerCase(Locale.ROOT);
        if ("list".equals(action) && args.length == 3) {
            return listGroups(sender, commands);
        }
        if ("create".equals(action) && args.length >= 4) {
            return groupResult(
                sender,
                commands.createGroup(actor(sender), join(args, 3)),
                "Shared waypoint group created."
            );
        }
        if ("rename".equals(action) && args.length >= 5) {
            return groupResult(
                sender,
                commands.renameGroup(actor(sender), args[3], join(args, 4)),
                "Shared waypoint group renamed."
            );
        }
        if ("delete".equals(action) && args.length == 4) {
            return groupResult(
                sender,
                commands.deleteGroup(actor(sender), args[3]),
                "Shared waypoint group deleted; its waypoints are now ungrouped."
            );
        }
        if ("assign".equals(action) && args.length == 5) {
            return groupResult(
                sender,
                commands.setWaypointGroup(actor(sender), args[3], args[4]),
                "Shared waypoint group membership updated."
            );
        }
        sender.sendMessage(
            "Usage: /confluxmap waypoints group <list|create <name>|rename <id> <name>"
                + "|delete <id>|assign <waypoint> <group|none>>"
        );
        return true;
    }

    private boolean listGroups(
        final CommandSender sender,
        final SharedWaypointCommandService commands
    ) {
        final List<SharedWaypointCommandService.GroupEntry> groups = commands.listGroups();
        if (groups.isEmpty()) {
            sender.sendMessage("No shared waypoint groups.");
            return true;
        }
        sender.sendMessage("Shared waypoint groups (total " + groups.size() + ")");
        for (final SharedWaypointCommandService.GroupEntry entry : groups) {
            sender.sendMessage("[" + entry.idPrefix() + "] " + entry.group().name());
        }
        return true;
    }

    private boolean groupResult(
        final CommandSender sender,
        final SharedWaypointCommandService.Result result,
        final String success
    ) {
        if (result.applied()) {
            sender.sendMessage(success);
            return true;
        }
        sender.sendMessage(switch (result.status()) {
            case INVALID_ID -> "Invalid group or waypoint ID.";
            case UNKNOWN_ID -> "Shared waypoint or group not found.";
            case AMBIGUOUS_ID -> "ID prefix is ambiguous; use the longer ID shown by list.";
            case FORBIDDEN -> "You do not have permission to manage this shared waypoint group.";
            case REJECTED -> groupMutationError(result.mutation().error());
            default -> "Shared waypoint group command failed.";
        });
        return true;
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

    private SharedWaypointCommandService commands() {
        if (!companion.sharedWaypointsEnabled()) {
            return null;
        }
        return new SharedWaypointCommandService(
            companion.sharedWaypoints(), UUID::randomUUID, companion::onSharedWaypointCommandMutation
        );
    }

    private static SharedWaypointService.Actor actor(final CommandSender sender) {
        final UUID id = sender instanceof final Player player
            ? player.getUniqueId()
            : UUID.nameUUIDFromBytes(
                ("confluxmap-command:" + sender.getName()).getBytes(StandardCharsets.UTF_8)
            );
        return new SharedWaypointService.Actor(
            id, sender.getName(), sender.hasPermission("confluxmap.admin")
        );
    }

    private static SharedWaypointCommandService.Position position(final Player player) {
        return new SharedWaypointCommandService.Position(
            DimensionId.parse(player.getWorld().getKey().toString()),
            player.getX(), player.getY(), player.getZ()
        );
    }

    private static boolean result(
        final CommandSender sender,
        final SharedWaypointCommandService.Result result,
        final String success
    ) {
        if (result.applied()) {
            sender.sendMessage(success);
            return true;
        }
        sender.sendMessage(switch (result.status()) {
            case INVALID_ID -> "Invalid waypoint ID.";
            case UNKNOWN_ID -> "Shared waypoint not found.";
            case AMBIGUOUS_ID -> "Waypoint ID prefix is ambiguous; use the longer ID shown by list.";
            case FORBIDDEN -> "You do not have permission to manage this shared waypoint.";
            case REJECTED -> mutationError(result.mutation().error());
            default -> "Shared waypoint command failed.";
        });
        return true;
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

    private static boolean disabled(final CommandSender sender) {
        sender.sendMessage("Shared waypoints are disabled on this server.");
        return true;
    }

    private static boolean usage(final CommandSender sender) {
        sender.sendMessage(
            "Usage: /confluxmap waypoints <list [page]|add <name>|edit <id> <name>|move <id>|delete <id>>"
        );
        return true;
    }

    private static String join(final String[] args, final int from) {
        return String.join(" ", Arrays.copyOfRange(args, from, args.length));
    }

    private boolean performance(final CommandSender sender) {
        if (!(sender instanceof final Player player)) {
            sender.sendMessage("This command must be run by a player.");
            return true;
        }
        if (!companion.isEnabled()) {
            sender.sendMessage("Conflux Map companion is disabled.");
            return true;
        }
        for (final String line : SyncPerformanceFormatter.format(
            companion.corrections().performance(player.getUniqueId()), TileMath.MAX_LOD
        )) {
            sender.sendMessage(line);
        }
        return true;
    }

    private boolean webMapPrivacy(final CommandSender sender, final boolean hidden) {
        if (!(sender instanceof final Player player)) {
            sender.sendMessage("This command must be run by a player.");
            return true;
        }
        if (!companion.setWebMapHidden(player.getUniqueId(), hidden)) {
            sender.sendMessage("Could not save the web-map privacy preference.");
            return true;
        }
        sender.sendMessage(hidden
            ? "You are hidden from the public web map."
            : "You are visible on the public web map when player sharing is enabled.");
        return true;
    }

    private boolean status(final CommandSender sender) {
        final ServerConfig config = companion.config();
        final SharedWaypointService service = companion.sharedWaypoints();
        final long revision = service == null ? 0L : service.snapshot().revision();
        sender.sendMessage(
            "Conflux Map shared waypoints: master=" + config.enabled
                + ", configured=" + config.shareWaypoints
                + ", enabled=" + companion.sharedWaypointsEnabled()
                + ", revision=" + revision
                + ", worldQuota=" + config.maxSharedWaypointsPerWorld
                + ", playerQuota=" + config.maxSharedWaypointsPerPlayer
                + ", nonOperatorManagement=" + config.allowNonOperatorSharedWaypointManagement
        );
        return true;
    }

    private boolean enable(final CommandSender sender) {
        final PaperCompanion.WaypointToggleResult result = companion.enableSharedWaypoints();
        logger.info("Shared waypoint admin actor={} action=enable result={}", sender.getName(), result);
        sender.sendMessage(switch (result) {
            case ENABLED -> "Shared waypoints enabled and saved.";
            case ALREADY_ENABLED -> "Shared waypoints are already enabled.";
            case MASTER_DISABLED -> "Cannot enable shared waypoints while companion enabled=false.";
            case LOAD_FAILED -> "Shared waypoint storage could not be loaded; sharing remains disabled.";
            case SAVE_FAILED -> "Server config could not be saved; sharing remains disabled.";
            default -> "Unexpected shared waypoint enable result: " + result;
        });
        return true;
    }

    private boolean disable(final CommandSender sender) {
        final PaperCompanion.WaypointToggleResult result = companion.disableSharedWaypoints();
        logger.info("Shared waypoint admin actor={} action=disable result={}", sender.getName(), result);
        sender.sendMessage(switch (result) {
            case DISABLED -> "Shared waypoints disabled and saved.";
            case ALREADY_DISABLED -> "Shared waypoints are already disabled.";
            case DISABLED_SAVE_FAILED ->
                "Shared waypoints are disabled for this runtime, but server config could not be saved.";
            default -> "Unexpected shared waypoint disable result: " + result;
        });
        return true;
    }

    private static List<String> prefix(final String input, final List<String> values) {
        final String normalized = input.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.startsWith(normalized)).toList();
    }
}

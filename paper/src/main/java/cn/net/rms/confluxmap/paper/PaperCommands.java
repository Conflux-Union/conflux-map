package cn.net.rms.confluxmap.paper;

import cn.net.rms.confluxmap.core.util.TileMath;
import cn.net.rms.confluxmap.server.ServerConfig;
import cn.net.rms.confluxmap.server.SyncPerformanceFormatter;
import cn.net.rms.confluxmap.server.shared.SharedWaypointService;
import java.util.List;
import java.util.Locale;
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
        if (args.length == 2 && "waypoints".equalsIgnoreCase(args[0])) {
            if (!sender.hasPermission("confluxmap.admin")) {
                sender.sendMessage("You do not have permission to manage Conflux Map.");
                return true;
            }
            return switch (args[1].toLowerCase(Locale.ROOT)) {
                case "status" -> status(sender);
                case "enable" -> enable(sender);
                case "disable" -> disable(sender);
                default -> false;
            };
        }
        sender.sendMessage("Usage: /confluxmap <performance|waypoints status|enable|disable>");
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
            return prefix(args[0], List.of("performance", "waypoints"));
        }
        if (args.length == 2 && "waypoints".equalsIgnoreCase(args[0])
            && sender.hasPermission("confluxmap.admin")) {
            return prefix(args[1], List.of("status", "enable", "disable"));
        }
        return List.of();
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

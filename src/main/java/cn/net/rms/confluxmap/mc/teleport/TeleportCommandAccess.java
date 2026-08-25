package cn.net.rms.confluxmap.mc.teleport;

import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.core.config.TeleportCommandTemplate;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.MinecraftClient;

/** Evaluates whether the configured teleport command can reach one map target. */
public final class TeleportCommandAccess {
    private static final String NO_PLAYER = "confluxmap.teleport.unavailable.player";
    private static final String UNKNOWN_POSITION = "confluxmap.teleport.unavailable.position";
    private static final String DIMENSION_SWITCH = "confluxmap.teleport.unavailable.dimension";
    private static final String WORLD_SWITCH = "confluxmap.teleport.unavailable.world";
    private static final String COMMAND = "confluxmap.teleport.unavailable.command";

    private TeleportCommandAccess() {
    }

    public static Result evaluate(
        final MinecraftClient client,
        final String template,
        final SessionGuard.Session live,
        final WorldIdentity targetWorld,
        final DimensionId targetDimension,
        final boolean targetPositionKnown
    ) {
        return evaluate(
            template,
            client.player != null,
            targetPositionKnown,
            live.dimension().equals(targetDimension),
            live.world().equals(targetWorld),
            names -> MinecraftAccess.canSendCommand(client, names.toArray(String[]::new))
        );
    }

    static Result evaluate(
        final String template,
        final boolean playerPresent,
        final boolean targetPositionKnown,
        final boolean sameDimension,
        final boolean sameWorld,
        final Predicate<List<String>> commandsAvailable
    ) {
        if (!playerPresent) {
            return Result.unavailable(NO_PLAYER);
        }
        if (!targetPositionKnown) {
            return Result.unavailable(UNKNOWN_POSITION);
        }
        if (!sameDimension && !TeleportCommandTemplate.supportsDimensionSwitch(template)) {
            return Result.unavailable(DIMENSION_SWITCH);
        }
        if (!sameWorld && !TeleportCommandTemplate.supportsWorldSwitch(template)) {
            return Result.unavailable(WORLD_SWITCH);
        }
        return TeleportCommandTemplate.commandName(template)
            .map(name -> "tp".equals(name) || "teleport".equals(name)
                ? List.of("teleport", "tp")
                : List.of(name))
            .filter(commandsAvailable)
            .map(ignored -> Result.availableNow())
            .orElseGet(() -> Result.unavailable(COMMAND));
    }

    public record Result(boolean available, String reasonKey) {
        private static Result availableNow() {
            return new Result(true, null);
        }

        private static Result unavailable(final String reasonKey) {
            return new Result(false, reasonKey);
        }
    }
}

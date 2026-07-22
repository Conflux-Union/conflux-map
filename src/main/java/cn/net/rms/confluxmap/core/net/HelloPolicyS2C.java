package cn.net.rms.confluxmap.core.net;

import cn.net.rms.confluxmap.core.predict.WorldPreset;
import java.util.List;

/**
 * {@code 0x02 S2C HELLO_POLICY}: server's handshake reply. Carries everything the client needs
 * to decide (a) whether it can show the predicted underlay in multiplayer ({@link #seedGranted}
 * + per-dim seed), (b) which namespace to use for its caches ({@link #worldId}), and (c) the
 * rate/batch limits it must respect when issuing {@link MapViewReqC2S}.
 *
 * <p>{@link PolicyUpdateS2C} reuses the {@link Flags} and {@link Budgets} records; the only fields
 * a mid-session update can change are those two (worldId and dim list are stable for the connection).
 */
public record HelloPolicyS2C(
    Flags flags,
    String worldId,
    String worldgenVersion,
    Budgets budgets,
    List<DimDescriptor> dims
) implements Message {

    /**
     * Top-level booleans the server advertises. {@code seedGranted} means a per-dim {@code seed}
     * is included in {@link DimDescriptor} - off by default (server config {@code shareSeed=false}).
     */
    public record Flags(boolean seedGranted, boolean correctionsEnabled, boolean structureInfoEnabled) {
    }

    /** Rate/batch limits. The client treats these as upper bounds, not guarantees. */
    public record Budgets(
        int maxBytesPerSec,
        int maxTilesPerReq,
        int minReqIntervalMs,
        int maxPatchLod
    ) {
    }

    /**
     * One entry per dimension the server is willing to serve. Index in the list is the
     * {@code dimIndex} used in {@link MapViewReqC2S} / {@link MapPatchS2C} so the per-message
     * wire form is small. {@code seed} is meaningful only when {@link Flags#seedGranted} is true.
     *
     * @param dimId       stringified dimension identifier, e.g. {@code "minecraft:overworld"}
     * @param dimType     {@code "overworld"} / {@code "the_nether"} / {@code "the_end"} (vanilla) or a mod id
     * @param predictable whether the server can ever produce corrections for this dim (cubiomes coverage
     *                    and a {@link WorldPreset#predictable()} generator)
     * @param hasSeed     whether the server actually has the world seed (always true when seedGranted; future
     *                    hook for per-dim-seed configs)
     * @param seed        the world seed; valid only when {@link Flags#seedGranted} && {@code hasSeed}
     * @param preset      the generator preset the server recognized for this dim; carried in spare bits of
     *                    the per-dim flag byte, so a pre-preset peer reads/writes {@link WorldPreset#DEFAULT}
     */
    public record DimDescriptor(String dimId, String dimType, boolean predictable, boolean hasSeed, long seed, WorldPreset preset) {
    }

    @Override
    public int typeId() {
        return Proto.MSG_HELLO_POLICY_S2C;
    }
}

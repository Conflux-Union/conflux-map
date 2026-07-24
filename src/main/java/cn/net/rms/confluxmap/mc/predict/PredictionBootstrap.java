package cn.net.rms.confluxmap.mc.predict;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.net.HelloPolicyS2C;
import cn.net.rms.confluxmap.core.predict.FlatBaseline;
import cn.net.rms.confluxmap.core.predict.PredictionDimensions;
import cn.net.rms.confluxmap.core.predict.PredictionState;
import cn.net.rms.confluxmap.core.predict.WorldPreset;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.mc.net.CompanionSession;
import cn.net.rms.confluxmap.nativepredict.McVersions;
import cn.net.rms.confluxmap.compat.MinecraftVersion;
import cn.net.rms.confluxmap.server.FlatWorldBaseline;
import cn.net.rms.confluxmap.server.WorldPresetDetector;
import java.util.Optional;
import java.util.OptionalLong;
import net.minecraft.client.MinecraftClient;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.World;

/**
 * Session listener that publishes everything prediction knows about the new session into {@link
 * PredictionState}: the per-dimension generator preset, the world seed when one is known, and -
 * for a superflat overworld - the uniform {@link FlatBaseline} that predicts without a seed.
 *
 * <p>Sources per mode:
 * <ul>
 *   <li>Singleplayer: presets/flat surface from the integrated server's live generators
 *       ({@link WorldPresetDetector}/{@link FlatWorldBaseline}), seed from the save properties.</li>
 *   <li>Multiplayer with an ACTIVE companion: presets/flat surface from the handshake
 *       (HELLO_POLICY dim entries + FLAT_BASELINE), seed from the overworld dim entry when
 *       granted. A pre-preset companion advertises defaults, matching its old behavior.</li>
 *   <li>Multiplayer without a companion: state stays cleared - we never fabricate a seed.</li>
 * </ul>
 *
 * <p>The worldgen-version string ("1.17.1" for this subproject) is mapped to the cubiomes
 * {@code MCVersion} int via {@link McVersions}; an unmappable version drops the seed (flat
 * prediction is version-independent and survives).
 */
public final class PredictionBootstrap {
    /** This subproject compiles for exactly one Minecraft version; see {@code McVersions} for the worldgen-version string table. */
    private static final String MC_VERSION_STRING = MinecraftVersion.current();

    private final MinecraftClient client;
    private final PredictionState state;
    private final CompanionSession companion;

    public PredictionBootstrap(final MinecraftClient client, final PredictionState state, final CompanionSession companion) {
        this.client = client;
        this.state = state;
        this.companion = companion;
    }

    /** Main thread, from the session tracker. */
    public void onSessionChanged(final SessionGuard.Session session) {
        state.clear();
        if (!session.active()) {
            return;
        }
        final boolean singleplayer = client.isInSingleplayer() && client.getServer() != null;
        final OptionalLong seedOpt;
        final String worldgenVersion;
        final WorldPreset overworldPreset;
        final WorldPreset endPreset;
        final Optional<FlatBaseline> flatBaseline;
        if (singleplayer) {
            seedOpt = OptionalLong.of(client.getServer().getSaveProperties().getGeneratorOptions().getSeed());
            // The client jar's own worldgen is the only worldgen an integrated server can run.
            worldgenVersion = MC_VERSION_STRING;
            overworldPreset = detectLocal(World.OVERWORLD);
            endPreset = detectLocal(World.END);
            flatBaseline = overworldPreset == WorldPreset.FLAT ? localFlatBaseline() : Optional.empty();
        } else if (companion.isActive()) {
            // The companion publishes the same vanilla seed for every dim (research R6 confirms
            // vanilla threads one long through every dimension); read it from the overworld entry.
            seedOpt = companion.seedFor(PredictionDimensions.OVERWORLD);
            // Plan: worldgen version comes from the handshake. A 1.17.1 client predicting against
            // a different-version server's seed uses cubiomes' params for the server's version.
            worldgenVersion = companion.policy() != null ? companion.policy().worldgenVersion() : MC_VERSION_STRING;
            overworldPreset = advertisedPreset(DimensionId.OVERWORLD);
            endPreset = advertisedPreset(DimensionId.END);
            flatBaseline = overworldPreset == WorldPreset.FLAT
                ? companion.flatBaselineFor(PredictionDimensions.OVERWORLD)
                : Optional.empty();
        } else {
            return;
        }
        state.setPresets(overworldPreset, endPreset);
        flatBaseline.ifPresent(state::setFlatBaseline);
        if (seedOpt.isPresent()) {
            final java.util.OptionalInt mcVersion = McVersions.toCubiomes(worldgenVersion);
            if (mcVersion.isPresent()) {
                state.setSeed(seedOpt.getAsLong(), mcVersion.getAsInt());
            } else {
                ConfluxMapMod.LOGGER.warn(
                    "prediction: server advertised unmappable worldgen version '{}', seeded prediction disabled",
                    worldgenVersion
                );
            }
        }
        ConfluxMapMod.LOGGER.debug(
            "prediction: session bootstrapped (source={} worldgen={} overworld={} end={} seed={} flat={})",
            singleplayer ? "singleplayer" : "companion", worldgenVersion, overworldPreset, endPreset,
            state.seedKnown() ? "known" : "none", flatBaseline.isPresent()
        );
        if (overworldPreset == WorldPreset.FLAT) {
            ConfluxMapMod.LOGGER.info(
                "prediction: superflat overworld, underlay {} (uniform surface {})",
                flatBaseline.isPresent() ? "uses the flat baseline" : "disabled (no surface info)",
                flatBaseline.map(Object::toString).orElse("-")
            );
        } else if (!overworldPreset.predictable()) {
            ConfluxMapMod.LOGGER.info(
                "prediction: overworld generator recognized as {}, predicted underlay disabled there", overworldPreset
            );
        }
    }

    /** Integrated server only: classify one dimension's live generator; a missing world is CUSTOM. */
    private WorldPreset detectLocal(final RegistryKey<World> key) {
        final ServerWorld world = client.getServer().getWorld(key);
        return world == null ? WorldPreset.CUSTOM : WorldPresetDetector.detect(world);
    }

    /** Integrated server only: the superflat overworld's uniform surface. */
    private Optional<FlatBaseline> localFlatBaseline() {
        final ServerWorld world = client.getServer().getWorld(World.OVERWORLD);
        return world == null ? Optional.empty() : FlatWorldBaseline.of(world);
    }

    /** The companion-advertised preset for {@code dimension}; DEFAULT when absent (pre-preset server). */
    private WorldPreset advertisedPreset(final DimensionId dimension) {
        final HelloPolicyS2C policy = companion.policy();
        if (policy == null) {
            return WorldPreset.DEFAULT;
        }
        final String dimId = dimension.toString();
        for (final HelloPolicyS2C.DimDescriptor dim : policy.dims()) {
            if (dimId.equals(dim.dimId())) {
                return dim.preset() == null ? WorldPreset.DEFAULT : dim.preset();
            }
        }
        return WorldPreset.DEFAULT;
    }
}

package cn.net.rms.confluxmap.mc.predict;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.core.predict.PredictionDimensions;
import cn.net.rms.confluxmap.core.predict.PredictionState;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.mc.net.CompanionSession;
import cn.net.rms.confluxmap.nativepredict.McVersions;
import java.util.OptionalLong;
import net.minecraft.client.MinecraftClient;

/**
 * Session listener that publishes a world seed into {@link PredictionState} when one is known.
 * Two seed sources in S3:
 *
 * <ul>
 *   <li>Singleplayer: {@code client.getServer().getSaveProperties().getGeneratorOptions().getSeed()}
 *       (verified against the decompiled 1.17.1 source - see the plan's R6).</li>
 *   <li>Multiplayer: when a companion {@link CompanionSession} is ACTIVE and has advertised
 *       {@code seedGranted}, use the overworld dim entry's seed. No companion or no granted
 *       seed: PredictionState is cleared - we never fabricate one.</li>
 * </ul>
 *
 * <p>The worldgen-version string ("1.17.1" for this subproject) is mapped to the cubiomes
 * {@code MCVersion} int via {@link McVersions}; an unmappable version also clears state.
 */
public final class PredictionBootstrap {
    /** This subproject compiles for exactly one Minecraft version; see {@code McVersions} for the worldgen-version string table. */
    private static final String MC_VERSION_STRING = "1.17.1";

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
        if (!session.active()) {
            state.clear();
            return;
        }
        final boolean singleplayer = client.isInSingleplayer() && client.getServer() != null;
        final OptionalLong seedOpt;
        final String worldgenVersion;
        if (singleplayer) {
            seedOpt = OptionalLong.of(client.getServer().getSaveProperties().getGeneratorOptions().getSeed());
            // The client jar's own worldgen is the only worldgen an integrated server can run.
            worldgenVersion = MC_VERSION_STRING;
        } else if (companion.isActive()) {
            // The companion publishes the same vanilla seed for every dim (research R6 confirms
            // vanilla threads one long through every dimension); read it from the overworld entry.
            seedOpt = companion.seedFor(PredictionDimensions.OVERWORLD);
            // Plan: worldgen version comes from the handshake. A 1.17.1 client predicting against
            // a different-version server's seed uses cubiomes' params for the server's version.
            worldgenVersion = companion.policy() != null ? companion.policy().worldgenVersion() : MC_VERSION_STRING;
        } else {
            seedOpt = OptionalLong.empty();
            worldgenVersion = MC_VERSION_STRING;
        }
        if (seedOpt.isEmpty()) {
            state.clear();
            return;
        }
        final java.util.OptionalInt mcVersion = McVersions.toCubiomes(worldgenVersion);
        if (mcVersion.isEmpty()) {
            state.clear();
            ConfluxMapMod.LOGGER.warn(
                "prediction: server advertised unmappable worldgen version '{}', prediction disabled",
                worldgenVersion
            );
            return;
        }
        state.set(seedOpt.getAsLong(), mcVersion.getAsInt());
        ConfluxMapMod.LOGGER.debug(
            "prediction: seed known for this session (source={} worldgen={})",
            singleplayer ? "singleplayer" : "companion", worldgenVersion
        );
    }
}

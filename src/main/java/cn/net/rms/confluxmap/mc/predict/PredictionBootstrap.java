package cn.net.rms.confluxmap.mc.predict;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.core.predict.PredictionState;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.nativepredict.McVersions;
import java.util.OptionalInt;
import net.minecraft.client.MinecraftClient;

/**
 * Session listener that publishes a world seed into {@link PredictionState} when one is known:
 * singleplayer only this slice, via {@code
 * client.getServer().getSaveProperties().getGeneratorOptions().getSeed()} (verified against the
 * decompiled 1.17.1 source - see the plan's R6). Multiplayer always clears the state instead -
 * a companion handshake to opt into a server-shared seed is S3's job, not this one's.
 */
public final class PredictionBootstrap {
    /** This subproject compiles for exactly one Minecraft version; see {@code McVersions} for the worldgen-version string table. */
    private static final String MC_VERSION_STRING = "1.17.1";

    private final MinecraftClient client;
    private final PredictionState state;

    public PredictionBootstrap(final MinecraftClient client, final PredictionState state) {
        this.client = client;
        this.state = state;
    }

    /** Main thread, from the session tracker. */
    public void onSessionChanged(final SessionGuard.Session session) {
        if (!session.active() || !client.isInSingleplayer() || client.getServer() == null) {
            state.clear();
            return;
        }
        final long seed = client.getServer().getSaveProperties().getGeneratorOptions().getSeed();
        final OptionalInt mcVersion = McVersions.toCubiomes(MC_VERSION_STRING);
        if (mcVersion.isEmpty()) {
            state.clear();
            return;
        }
        state.set(seed, mcVersion.getAsInt());
        ConfluxMapMod.LOGGER.debug("prediction: seed known for this singleplayer session");
    }
}

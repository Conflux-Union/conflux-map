package cn.net.rms.confluxmap.mc.ui;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.server.DatapackLangService;
import java.nio.file.Path;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Feeds {@link BiomeLabels} the biome names a level's data packs carry in their {@code assets/}
 * half, which vanilla never loads on the client.
 *
 * <p>Two sources, one per connection shape: a singleplayer world is read straight from the
 * integrated server's {@code datapacks} directory — the companion stays inactive there until
 * the world is published to LAN, so no network answer would ever come — while a dedicated
 * server can only be read by its own companion, which the client asks through the map-sync
 * channel (the capability-gated BIOME_LANG request wired in {@code ClientNetworking}).
 *
 * <p>Reloads re-run both paths because a language switch also fires a client resource reload;
 * the local read picks the new locale file, the companion request carries the new code.
 *
 * <p>Synced names live for the whole connection: {@code ClientNetworking} clears them on
 * disconnect, and this listener deliberately does not. Session changes also fire on every
 * dimension hop, and the companion's answer for a join can land either side of the session
 * swap — clearing here would race that answer and strand the map on registry-id fallbacks
 * (datapacks are level-scoped anyway, so dimension hops see the same names).
 */
public final class DatapackBiomeNameService implements SimpleSynchronousResourceReloadListener {
    private static final Identifier RELOAD_ID =
        cn.net.rms.confluxmap.compat.Ids.of(ConfluxMapMod.ID, "datapack_biome_names");

    private final MinecraftClient client;
    private final Runnable requestFromCompanion;
    private final Logger logger = ConfluxMapMod.LOGGER;
    /** One worker for the whole client lifetime; the directory it reads swaps per session. */
    private final DatapackLangService localReader;
    private volatile Path singleplayerDatapacks;

    public DatapackBiomeNameService(final MinecraftClient client, final Runnable requestFromCompanion) {
        this.client = client;
        this.requestFromCompanion = requestFromCompanion;
        this.localReader = new DatapackLangService(() -> singleplayerDatapacks, logger);
    }

    /** Session listener: re-binds the local reader to the new world; clearing is disconnect-owned. */
    public void onSessionChanged(final SessionGuard.Session session) {
        if (!session.active()) {
            singleplayerDatapacks = null;
            return;
        }
        final Path dir = MinecraftAccess.singleplayerDatapacksDir(client);
        singleplayerDatapacks = dir;
        if (dir != null) {
            localReader.requestBiomeNames(
                MinecraftAccess.language(client),
                BiomeLabels::acceptDatapackNames
            );
        }
    }

    @Override
    public Identifier getFabricId() {
        return RELOAD_ID;
    }

    @Override
    public void reload(final ResourceManager manager) {
        if (singleplayerDatapacks != null) {
            localReader.requestBiomeNames(
                MinecraftAccess.language(client),
                BiomeLabels::acceptDatapackNames
            );
        } else {
            requestFromCompanion.run();
        }
    }

    /** The local reader's worker; the composition root stops it on client shutdown. */
    public void shutdown() {
        localReader.shutdown();
    }

    /** Directory seam visible to tests. */
    @Nullable
    Path singleplayerDatapacks() {
        return singleplayerDatapacks;
    }
}

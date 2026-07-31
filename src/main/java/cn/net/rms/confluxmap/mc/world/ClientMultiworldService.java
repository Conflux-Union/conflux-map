package cn.net.rms.confluxmap.mc.world;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.compat.Regs;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.core.cache.RegionFileCodec;
import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldObservation;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldProfile;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldProfileResolver;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldResolution;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldSignalHasher;
import cn.net.rms.confluxmap.core.multiworld.TerrainFingerprintMatcher;
import cn.net.rms.confluxmap.mc.net.CompanionSession;
import cn.net.rms.confluxmap.mc.snapshot.ChunkCaptureService;
import com.mojang.brigadier.tree.CommandNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import net.minecraft.util.Formatting;

/**
 * Client-only upstream world resolver for proxy addresses. The server seed hash is strong evidence;
 * hashed registry/command/brand metadata provides conservative fallback evidence, and existing
 * terrain cache is consulted last. Ambiguous observations intentionally return no identity so the
 * normal session lifecycle suspends every map writer until the user makes a choice.
 */
public final class ClientMultiworldService {
    private static final int SIGNAL_REFRESH_TICKS = 20;

    private final MinecraftClient client;
    private final CompanionSession companion;
    private final ClientWorldProfileResolver resolver;
    private final Path cacheRoot;
    private final Executor io;

    private OptionalLong seedHash = OptionalLong.empty();
    private Map<String, String> signals = Map.of();
    private ClientWorldResolution resolution = ClientWorldResolution.collecting();
    private String address;
    private String serverId;
    private int signalTicks;
    private long observationGeneration;
    private boolean terrainAttempted;
    private boolean ambiguityNotified;
    private ChunkCaptureService chunkCapture;
    private Supplier<String> openMapKeyDisplayName;

    public ClientMultiworldService(
        final MinecraftClient client,
        final CompanionSession companion,
        final ClientWorldProfileResolver resolver,
        final Path cacheRoot,
        final Executor io
    ) {
        this.client = client;
        this.companion = companion;
        this.resolver = resolver;
        this.cacheRoot = cacheRoot;
        this.io = io;
    }

    public void register() {
        ClientWorldIdentityHandler.bind(this);
        ClientTickEvents.END_CLIENT_TICK.register(ignored -> tickSignals());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, ignored) -> resetObservation());
    }

    public void bindChunkCapture(final ChunkCaptureService service) {
        chunkCapture = service;
    }

    public void bindOpenMapKeyDisplayName(final Supplier<String> supplier) {
        openMapKeyDisplayName = Objects.requireNonNull(supplier, "supplier");
    }

    public void onGameJoin(final long observedSeedHash) {
        resetObservation();
        seedHash = OptionalLong.of(observedSeedHash);
    }

    public void onRespawn(final long observedSeedHash) {
        seedHash = OptionalLong.of(observedSeedHash);
        signals = Map.of();
        resolution = ClientWorldResolution.collecting();
        signalTicks = 0;
        terrainAttempted = false;
        ambiguityNotified = false;
        observationGeneration++;
    }

    /** Resolves only the client-owned fallback. Companion world UUIDs remain authoritative. */
    public Optional<WorldIdentity> resolve(final String currentAddress) {
        if (!currentAddress.equals(address)) {
            address = currentAddress;
            serverId = WorldIdentity.multiplayer(currentAddress).serverId();
            resolution = ClientWorldResolution.collecting();
            terrainAttempted = false;
            ambiguityNotified = false;
            observationGeneration++;
        }
        if (resolution.state() != ClientWorldResolution.State.RESOLVED) {
            resolution = resolver.resolve(serverId, observation());
        }
        if (resolution.state() != ClientWorldResolution.State.RESOLVED) {
            notifyAmbiguity();
            tryTerrainMatch();
            return Optional.empty();
        }
        return Optional.of(WorldIdentity.multiplayer(currentAddress, resolution.profile().storageId()));
    }

    public boolean canManageProfiles() {
        return client.world != null && !client.isInSingleplayer() && companion.state() != CompanionSession.State.ACTIVE;
    }

    public boolean needsSelection() {
        return canManageProfiles() && resolution.state() == ClientWorldResolution.State.AMBIGUOUS;
    }

    public List<ClientWorldProfile> profiles() {
        return serverId == null ? List.of() : resolver.profiles(serverId);
    }

    public Optional<ClientWorldProfile> currentProfile() {
        return resolution.state() == ClientWorldResolution.State.RESOLVED
            ? Optional.of(resolution.profile())
            : Optional.empty();
    }

    public void select(final String profileId) {
        requireConnection();
        resolution = resolver.select(serverId, profileId, observation());
        observationGeneration++;
    }

    public void createAndSelect(final String displayName) {
        requireConnection();
        resolution = resolver.createAndSelect(serverId, displayName, observation());
        observationGeneration++;
    }

    public void rename(final String profileId, final String displayName) {
        requireConnection();
        resolver.rename(serverId, profileId, displayName);
    }

    public void clearBindings(final String profileId) {
        requireConnection();
        resolver.clearBindings(serverId, profileId);
        if (currentProfile().map(ClientWorldProfile::id).filter(profileId::equals).isPresent()) {
            resolution = ClientWorldResolution.ambiguous();
            terrainAttempted = false;
            ambiguityNotified = false;
            observationGeneration++;
            notifyAmbiguity();
        }
    }

    private void tickSignals() {
        if (client.world == null || client.player == null || client.getNetworkHandler() == null || client.isInSingleplayer()) {
            return;
        }
        if (++signalTicks < SIGNAL_REFRESH_TICKS) {
            return;
        }
        signalTicks = 0;
        final Map<String, String> observed = collectSignals();
        if (observed.equals(signals)) {
            return;
        }
        signals = observed;
        terrainAttempted = false;
        observationGeneration++;
        if (serverId != null) {
            resolution = resolver.resolve(serverId, observation());
            notifyAmbiguity();
            tryTerrainMatch();
        }
    }

    private Map<String, String> collectSignals() {
        final Map<String, String> observed = new LinkedHashMap<>();
        //#if MC>=260100
        //$$ final String brand = client.getConnection().serverBrand();
        //#elseif MC>=12100
        //$$ final String brand = client.getNetworkHandler().getBrand();
        //#else
        final String brand = client.player.getServerBrand();
        //#endif
        if (brand != null && !brand.isBlank()) {
            observed.put("brand", ClientWorldSignalHasher.hash(brand));
        }

        final List<String> commands = client.getNetworkHandler().getCommandDispatcher().getRoot().getChildren().stream()
            .map(CommandNode::getName)
            .toList();
        if (!commands.isEmpty()) {
            observed.put("commands", ClientWorldSignalHasher.hashSorted(commands));
        }

        final List<String> biomes = Regs.biomes(client.world).getIds().stream()
            .map(Identifier::toString)
            .toList();
        if (!biomes.isEmpty()) {
            observed.put("biomes", ClientWorldSignalHasher.hashSorted(biomes));
        }

        final Identifier dimension = client.world.getRegistryKey().getValue();
        observed.put("dimension", ClientWorldSignalHasher.hash(dimension.toString()));
        observed.put("dimension_type", ClientWorldSignalHasher.hash(
            client.world.getDimension().hasCeiling() + ":"
                + client.world.getDimension().hasSkyLight()
        ));
        return Map.copyOf(observed);
    }

    private void tryTerrainMatch() {
        final ChunkCaptureService capture = chunkCapture;
        if (terrainAttempted || capture == null || serverId == null
            || resolution.state() != ClientWorldResolution.State.AMBIGUOUS) {
            return;
        }
        final List<ClientWorldProfile> profiles = resolver.profiles(serverId);
        if (profiles.size() < 2 || client.world == null) {
            return;
        }
        final MapLayer layer = terrainProbeLayer();
        final List<ChunkSnapshot> probes = capture.probeNearest(layer, TerrainFingerprintMatcher.MIN_CHUNKS);
        if (probes.size() < TerrainFingerprintMatcher.MIN_CHUNKS) {
            return;
        }
        terrainAttempted = true;
        final long generation = observationGeneration;
        final String expectedServer = serverId;
        final String dimension = DimensionId.of(
            client.world.getRegistryKey().getValue().getNamespace(),
            client.world.getRegistryKey().getValue().getPath()
        ).fileName();
        io.execute(() -> {
            final List<TerrainFingerprintMatcher.Candidate> candidates = loadTerrainCandidates(
                expectedServer, dimension, layer, probes, profiles
            );
            final TerrainFingerprintMatcher.Result result = TerrainFingerprintMatcher.match(probes, candidates);
            result.profileId().ifPresent(profileId -> client.execute(() -> {
                if (generation != observationGeneration || !expectedServer.equals(serverId)
                    || resolution.state() != ClientWorldResolution.State.AMBIGUOUS) {
                    return;
                }
                resolution = resolver.select(serverId, profileId, observation());
                observationGeneration++;
                ConfluxMapMod.LOGGER.info(
                    "Matched client world profile {} from cached terrain (score={}, gap={})",
                    profileId, result.bestScore(), result.bestScore() - result.runnerUpScore()
                );
            }));
        });
    }

    private MapLayer terrainProbeLayer() {
        switch (LayerSelector.classify(client.world.getDimension())) {
            case HAS_CEILING:
                return MapLayer.NETHER_CEILING;
            case NO_SKY_NO_CEILING:
                return MapLayer.END_SURFACE;
            default:
                return MapLayer.SURFACE;
        }
    }

    private List<TerrainFingerprintMatcher.Candidate> loadTerrainCandidates(
        final String expectedServer,
        final String dimension,
        final MapLayer layer,
        final List<ChunkSnapshot> probes,
        final List<ClientWorldProfile> profiles
    ) {
        final List<TerrainFingerprintMatcher.Candidate> candidates = new ArrayList<>();
        for (final ClientWorldProfile profile : profiles) {
            final Map<TerrainFingerprintMatcher.RegionPos, RegionFileCodec.RegionData> regions = new LinkedHashMap<>();
            for (final ChunkSnapshot probe : probes) {
                final TerrainFingerprintMatcher.RegionPos region = new TerrainFingerprintMatcher.RegionPos(
                    probe.chunkX >> 4, probe.chunkZ >> 4
                );
                if (regions.containsKey(region)) {
                    continue;
                }
                final Path file = cacheRoot.resolve(expectedServer)
                    .resolve(profile.storageId())
                    .resolve(dimension)
                    .resolve(layer.cacheId())
                    .resolve("r." + region.x() + "." + region.z() + ".cfr");
                try (InputStream input = Files.newInputStream(file)) {
                    regions.put(region, RegionFileCodec.decode(
                        input, region.x(), region.z(), layer.type().ordinal()
                    ));
                } catch (final IOException | RegionFileCodec.RegionFileException ignored) {
                    // Recognition is read-only and best-effort; cache ownership remains with RegionDiskCache.
                }
            }
            candidates.add(new TerrainFingerprintMatcher.Candidate(profile.id(), regions));
        }
        return candidates;
    }

    private ClientWorldObservation observation() {
        return new ClientWorldObservation(seedHash, signals);
    }

    private void notifyAmbiguity() {
        if (ambiguityNotified || resolution.state() != ClientWorldResolution.State.AMBIGUOUS
            || client.player == null) {
            return;
        }
        ambiguityNotified = true;
        //#if MC>=260100
        //$$ client.player.sendSystemMessage(
        //$$     Texts.translatable(
        //$$         "confluxmap.client_world.ambiguous_chat", openMapKeyDisplayName.get()
        //$$     ).withStyle(ChatFormatting.YELLOW)
        //$$ );
        //#else
        client.player.sendMessage(
            Texts.translatable(
                "confluxmap.client_world.ambiguous_chat", openMapKeyDisplayName.get()
            ).formatted(Formatting.YELLOW),
            false
        );
        //#endif
    }

    private void requireConnection() {
        if (serverId == null) {
            throw new IllegalStateException("not connected to a multiplayer server");
        }
    }

    private void resetObservation() {
        seedHash = OptionalLong.empty();
        signals = Map.of();
        resolution = ClientWorldResolution.collecting();
        address = null;
        serverId = null;
        signalTicks = 0;
        terrainAttempted = false;
        ambiguityNotified = false;
        observationGeneration++;
    }
}

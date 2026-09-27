package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.compat.Ids;
import cn.net.rms.confluxmap.compat.Regs;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.predict.CubiomesBiomeIds;
import cn.net.rms.confluxmap.core.predict.FlatBaseline;
import cn.net.rms.confluxmap.core.predict.QuadrantLayout;
import cn.net.rms.confluxmap.core.predict.WorldPreset;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;

/**
 * Reads quadra-gen's {@code config/quadragen/config.json} and turns it into per-dimension {@link
 * QuadrantLayout}s, mirroring the activation rules quadra-gen itself applies ({@code
 * LevelBootstrap.install}): the mod loaded, the master and singleplayer switches, the dimension's
 * own switch, and a vanilla noise generator (approximated here by {@link
 * WorldPreset#predictable()}, which is exactly the subset whose seeded prediction runs at all).
 *
 * <p>The config is the documented public contract of quadra-gen, validated by that mod at its own
 * startup and never hot-reloaded, so a successfully parsed file matches the running server. Any
 * surprise (missing mod, unreadable config, new schema version, unresolvable block/biome id)
 * degrades to "no layout": prediction keeps its unmasked behavior rather than guessing.
 *
 * <p>Runs on the companion (dedicated servers, {@code ServerNetworking}) and the integrated
 * server (singleplayer, {@code mc.predict.PredictionBootstrap}) - the same JVM quadra-gen runs
 * in when it is active there.
 */
public final class QuadraGenLayouts {
    private static final Gson GSON = new Gson();
    private static final String MOD_ID = "quadragen";

    /**
     * The MC-free shape of {@code config/quadragen/config.json}, schema version 1. Plain classes
     * with mutable fields, like quadra-gen's own config objects: the Gson bundled with 1.17-era
     * Minecraft cannot deserialize records (it cannot write their final fields reflectively).
     */
    public static final class Config {
        @SerializedName("schema_version")
        Integer schemaVersion;
        @SerializedName("enabled")
        Boolean enabled;
        @SerializedName("enabled_in_singleplayer")
        Boolean enabledInSingleplayer;
        @SerializedName("overworld")
        DimensionSpec overworld;
        @SerializedName("nether")
        DimensionSpec nether;
    }

    public static final class DimensionSpec {
        @SerializedName("enabled")
        Boolean enabled;
        @SerializedName("quadrants")
        Map<String, QuadrantSpec> quadrants;
    }

    /** One quadrant: {@code generator} is {@code noise} or {@code flat}. */
    public static final class QuadrantSpec {
        @SerializedName("generator")
        String generator;
        @SerializedName("clear_generated_content")
        Boolean clearGeneratedContent;
        @SerializedName("flat")
        FlatSpec flat;
    }

    public static final class FlatSpec {
        @SerializedName("biome")
        String biome;
        @SerializedName("layers")
        List<FlatLayerSpec> layers;
    }

    public static final class FlatLayerSpec {
        @SerializedName("block")
        String block;
        @SerializedName("count")
        Integer count;
    }

    private QuadraGenLayouts() {
    }

    /**
     * The layouts quadra-gen manages on this server, keyed by dimension. Empty when quadra-gen is
     * absent, disabled, or its config cannot be trusted - never an error.
     *
     * @param ownerIsSingleplayer whether the owning server is an integrated singleplayer server
     */
    public static Map<DimensionId, QuadrantLayout> detect(
        final MinecraftServer server, final boolean ownerIsSingleplayer
    ) {
        if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) {
            return Map.of();
        }
        final Path configPath = FabricLoader.getInstance().getConfigDir()
            .resolve(MOD_ID).resolve("config.json");
        final Optional<Config> parsed = parse(configPath);
        if (parsed.isEmpty()) {
            return Map.of();
        }
        final Config config = parsed.get();
        if (!Boolean.TRUE.equals(config.enabled)
            || (ownerIsSingleplayer && !Boolean.TRUE.equals(config.enabledInSingleplayer))) {
            return Map.of();
        }
        final Map<DimensionId, QuadrantLayout> layouts = new LinkedHashMap<>();
        for (final ServerWorld world : server.getWorlds()) {
            final String path = world.getRegistryKey().getValue().getPath();
            final DimensionSpec spec;
            if (World.OVERWORLD.getValue().getPath().equals(path)) {
                spec = config.overworld;
            } else if (World.NETHER.getValue().getPath().equals(path)) {
                spec = config.nether;
            } else {
                continue;
            }
            // quadra-gen only routes vanilla noise generators; on any other preset our seeded
            // prediction is disabled for the dimension anyway, so there is nothing to mask.
            if (spec == null || !Boolean.TRUE.equals(spec.enabled)
                || !WorldPresetDetector.detect(world).predictable()) {
                continue;
            }
            layout(world, spec).ifPresent(layout -> layouts.put(dimensionId(world), layout));
        }
        return Collections.unmodifiableMap(layouts);
    }

    /** Gson parse with the schema gate; any failure logs once and returns empty. */
    public static Optional<Config> parse(final Path configPath) {
        if (!Files.isRegularFile(configPath)) {
            ConfluxMapMod.LOGGER.warn(
                "[ConfluxMap] quadra-gen is loaded but {} is missing; predicted quadrants stay unmasked",
                configPath
            );
            return Optional.empty();
        }
        final Config config;
        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            config = GSON.fromJson(reader, Config.class);
        } catch (final Exception e) {
            ConfluxMapMod.LOGGER.warn(
                "[ConfluxMap] quadra-gen config {} is unreadable ({}); predicted quadrants stay unmasked",
                configPath, e.getMessage()
            );
            return Optional.empty();
        }
        if (config == null || config.schemaVersion == null || config.schemaVersion != 1) {
            ConfluxMapMod.LOGGER.warn(
                "[ConfluxMap] quadra-gen config {} has an unsupported schema_version ({}); predicted quadrants stay unmasked",
                configPath, config == null || config.schemaVersion == null ? "-" : config.schemaVersion
            );
            return Optional.empty();
        }
        return Optional.of(config);
    }

    private static Optional<QuadrantLayout> layout(final ServerWorld world, final DimensionSpec spec) {
        try {
            return Optional.of(new QuadrantLayout(
                style(spec, QuadrantLayout.X_POSITIVE_Z_POSITIVE).orElseThrow(),
                flat(spec, world, QuadrantLayout.X_POSITIVE_Z_POSITIVE).orElse(null),
                style(spec, QuadrantLayout.X_POSITIVE_Z_NEGATIVE).orElseThrow(),
                flat(spec, world, QuadrantLayout.X_POSITIVE_Z_NEGATIVE).orElse(null),
                style(spec, QuadrantLayout.X_NEGATIVE_Z_POSITIVE).orElseThrow(),
                flat(spec, world, QuadrantLayout.X_NEGATIVE_Z_POSITIVE).orElse(null),
                style(spec, QuadrantLayout.X_NEGATIVE_Z_NEGATIVE).orElseThrow(),
                flat(spec, world, QuadrantLayout.X_NEGATIVE_Z_NEGATIVE).orElse(null)
            ));
        } catch (final Exception e) {
            ConfluxMapMod.LOGGER.warn(
                "[ConfluxMap] quadra-gen config for {} is invalid ({}); predicted quadrants stay unmasked",
                world.getRegistryKey().getValue(), e.getMessage()
            );
            return Optional.empty();
        }
    }

    private static Optional<QuadrantLayout.Style> style(final DimensionSpec spec, final int quadrant) {
        final QuadrantSpec raw = quadrantSpec(spec, quadrant);
        if (raw == null || raw.generator == null) {
            return Optional.empty();
        }
        return switch (raw.generator.toLowerCase(Locale.ROOT)) {
            case "noise" -> Optional.of(Boolean.TRUE.equals(raw.clearGeneratedContent)
                ? QuadrantLayout.Style.CLEARED : QuadrantLayout.Style.NOISE);
            case "flat" -> Optional.of(QuadrantLayout.Style.FLAT);
            default -> Optional.empty();
        };
    }

    private static Optional<FlatBaseline> flat(
        final DimensionSpec spec, final ServerWorld world, final int quadrant
    ) {
        final QuadrantSpec raw = quadrantSpec(spec, quadrant);
        if (raw == null || !"flat".equalsIgnoreCase(raw.generator)) {
            return Optional.empty();
        }
        final FlatSpec flat = raw.flat;
        if (flat == null || flat.layers == null) {
            return Optional.empty();
        }
        final int biomeId = biomeId(world, flat.biome);
        final List<BlockState> layers = new ArrayList<>();
        for (final FlatLayerSpec layer : flat.layers) {
            if (layer == null || layer.block == null || layer.count == null || layer.count <= 0) {
                return Optional.empty();
            }
            final Block block = Regs.block(Ids.of(layer.block)).orElse(null);
            if (block == null) {
                return Optional.empty();
            }
            final BlockState state = block.getDefaultState();
            for (int i = 0; i < layer.count; i++) {
                layers.add(state);
            }
        }
        return Optional.of(FlatWorldBaseline.fromLayers(layers, world.getBottomY(), biomeId));
    }

    /** cubiomes id for a vanilla biome, else the raw registry id like {@code FlatWorldBaseline}. */
    private static int biomeId(final ServerWorld world, final String name) {
        if (name == null) {
            return 1;
        }
        final int separator = name.indexOf(':');
        final String path = separator >= 0 ? name.substring(separator + 1) : name;
        final OptionalInt mapped = CubiomesBiomeIds.idForName(path);
        if (mapped.isPresent()) {
            return mapped.getAsInt();
        }
        final Registry<Biome> registry = Regs.biomes(world);
        final Biome biome = Regs.biome(
            registry, Ids.of(name.contains(":") ? name : "minecraft:" + name)
        );
        return biome == null ? 1 : registry.getRawId(biome) & 255;
    }

    private static QuadrantSpec quadrantSpec(final DimensionSpec spec, final int quadrant) {
        if (spec.quadrants == null) {
            return null;
        }
        return switch (quadrant) {
            case QuadrantLayout.X_POSITIVE_Z_POSITIVE -> spec.quadrants.get("x_positive_z_positive");
            case QuadrantLayout.X_POSITIVE_Z_NEGATIVE -> spec.quadrants.get("x_positive_z_negative");
            case QuadrantLayout.X_NEGATIVE_Z_POSITIVE -> spec.quadrants.get("x_negative_z_positive");
            case QuadrantLayout.X_NEGATIVE_Z_NEGATIVE -> spec.quadrants.get("x_negative_z_negative");
            default -> null;
        };
    }

    private static DimensionId dimensionId(final ServerWorld world) {
        return DimensionId.of(
            world.getRegistryKey().getValue().getNamespace(),
            world.getRegistryKey().getValue().getPath()
        );
    }
}

package cn.net.rms.confluxmap.paper;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.predict.CubiomesBiomeIds;
import cn.net.rms.confluxmap.core.predict.FlatBaseline;
import cn.net.rms.confluxmap.core.predict.WorldPreset;
import cn.net.rms.confluxmap.server.ChunkColumnSummarizer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import net.querz.nbt.io.NBTUtil;
import net.querz.nbt.io.NamedTag;
import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.ListTag;
import org.bukkit.World;
import org.bukkit.WorldType;

/** Reads the public world metadata needed to mirror Fabric's generator classification. */
final class PaperWorldMetadata {
    private PaperWorldMetadata() {
    }

    static WorldPreset detectPreset(final World world) {
        if (world.getGenerator() != null) {
            return WorldPreset.CUSTOM;
        }
        final CompoundTag generator = generator(world).orElse(null);
        if (generator != null) {
            final String type = string(generator, "type");
            if ("minecraft:flat".equals(type)) {
                return WorldPreset.FLAT;
            }
            if ("minecraft:debug".equals(type)) {
                return WorldPreset.DEBUG;
            }
            if (!"minecraft:noise".equals(type)) {
                return WorldPreset.CUSTOM;
            }
            final String settings = string(generator, "settings");
            if ("minecraft:amplified".equals(settings)) {
                return WorldPreset.AMPLIFIED;
            }
            if ("minecraft:large_biomes".equals(settings)) {
                return WorldPreset.LARGE_BIOMES;
            }
            if ("minecraft:overworld".equals(settings) || "minecraft:end".equals(settings)) {
                return WorldPreset.DEFAULT;
            }
            return WorldPreset.CUSTOM;
        }
        return fallbackPreset(world.getWorldType());
    }

    static Optional<FlatBaseline> flatBaseline(
        final World world,
        final ChunkColumnSummarizer.MapColorResolver mapColors
    ) {
        return flatBaseline(
            world.getWorldFolder().toPath().resolve("level.dat"),
            world.getKey().toString(),
            canonicalDimension(world),
            world.getMinHeight(),
            mapColors
        );
    }

    static Optional<FlatBaseline> flatBaseline(
        final Path levelDat,
        final String dimensionId,
        final String canonicalDimension,
        final int bottomY,
        final ChunkColumnSummarizer.MapColorResolver mapColors
    ) {
        final CompoundTag generator = generator(
            levelDat, dimensionId, canonicalDimension
        ).orElse(null);
        if (generator == null || !"minecraft:flat".equals(string(generator, "type"))) {
            return Optional.empty();
        }
        final CompoundTag settings = generator.getCompoundTag("settings");
        if (settings == null) {
            return Optional.empty();
        }
        final ListTag<CompoundTag> layers = compounds(settings, "layers");
        final int biomeId = biomeId(string(settings, "biome"));
        long nextY = bottomY;
        int surfaceY = 0;
        int waterDepth = 0;
        String surfaceBlock = null;
        for (final CompoundTag layer : layers) {
            final int height = Math.max(0, integer(layer, "height"));
            final String block = string(layer, "block");
            final long followingY = nextY + height;
            if (followingY > (long) Integer.MAX_VALUE + 1L
                || followingY < Integer.MIN_VALUE) {
                return Optional.empty();
            }
            if (height > 0 && !isAir(block)) {
                surfaceY = (int) (followingY - 1L);
                if (isWater(block)) {
                    waterDepth = isWater(surfaceBlock)
                        ? (int) Math.min(255L, waterDepth + (long) height)
                        : Math.min(255, height);
                } else {
                    waterDepth = 0;
                }
                surfaceBlock = block;
            }
            nextY = followingY;
        }
        if (surfaceBlock == null) {
            return Optional.of(new FlatBaseline(
                biomeId, 0, SurfaceKind.VOID.ordinal(), Proto.MAP_COLOR_NONE, 0
            ));
        }
        final ChunkColumnSummarizer.BlockInfo info = ChunkColumnSummarizer.classify(
            surfaceBlock, mapColors
        );
        if (info.kind() == SurfaceKind.UNKNOWN) {
            return Optional.of(new FlatBaseline(
                biomeId, 0, SurfaceKind.VOID.ordinal(), Proto.MAP_COLOR_NONE, 0
            ));
        }
        return Optional.of(new FlatBaseline(
            biomeId,
            surfaceY,
            info.kind().ordinal(),
            info.mapColorId(),
            info.kind() == SurfaceKind.WATER ? Math.min(255, waterDepth) : 0
        ));
    }

    private static Optional<CompoundTag> generator(final World world) {
        return generator(
            world.getWorldFolder().toPath().resolve("level.dat"),
            world.getKey().toString(),
            canonicalDimension(world)
        );
    }

    private static Optional<CompoundTag> generator(
        final Path levelDat,
        final String dimensionId,
        final String canonicalDimension
    ) {
        if (!Files.isRegularFile(levelDat)) {
            return Optional.empty();
        }
        try {
            final NamedTag named = NBTUtil.read(levelDat.toFile());
            if (!(named.getTag() instanceof final CompoundTag root)) {
                return Optional.empty();
            }
            final CompoundTag data = root.getCompoundTag("Data");
            final CompoundTag settings = data == null
                ? null : data.getCompoundTag("WorldGenSettings");
            final CompoundTag dimensions = settings == null
                ? null : settings.getCompoundTag("dimensions");
            if (dimensions == null) {
                return Optional.empty();
            }
            CompoundTag dimension = dimensions.getCompoundTag(dimensionId);
            if (dimension == null) {
                dimension = dimensions.getCompoundTag(canonicalDimension);
            }
            return Optional.ofNullable(
                dimension == null ? null : dimension.getCompoundTag("generator")
            );
        } catch (final IOException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    @SuppressWarnings("deprecation")
    private static WorldPreset fallbackPreset(final WorldType type) {
        if (type == WorldType.FLAT) {
            return WorldPreset.FLAT;
        }
        if (type == WorldType.AMPLIFIED) {
            return WorldPreset.AMPLIFIED;
        }
        if (type == WorldType.LARGE_BIOMES) {
            return WorldPreset.LARGE_BIOMES;
        }
        return type == WorldType.NORMAL ? WorldPreset.DEFAULT : WorldPreset.CUSTOM;
    }

    private static String canonicalDimension(final World world) {
        return switch (world.getEnvironment()) {
            case NETHER -> "minecraft:the_nether";
            case THE_END -> "minecraft:the_end";
            default -> "minecraft:overworld";
        };
    }

    private static boolean isAir(final String block) {
        return block == null || block.isEmpty() || block.endsWith("air");
    }

    private static boolean isWater(final String block) {
        return "minecraft:water".equals(block);
    }

    private static int biomeId(final String name) {
        final int separator = name.indexOf(':');
        final String path = separator >= 0 ? name.substring(separator + 1) : name;
        return CubiomesBiomeIds.idForName(path).orElse(1);
    }

    private static String string(final CompoundTag tag, final String key) {
        return tag == null ? "" : tag.getString(key).orElse("");
    }

    private static int integer(final CompoundTag tag, final String key) {
        return tag == null ? 0 : tag.getInt(key).orElse(0);
    }

    private static ListTag<CompoundTag> compounds(final CompoundTag tag, final String key) {
        final ListTag<?> list = tag == null ? null : tag.getListTag(key);
        if (list == null || !CompoundTag.class.equals(list.getTypeClass())) {
            return new ListTag<>(CompoundTag.class);
        }
        return list.asCompoundTagList();
    }
}

package cn.net.rms.confluxmap.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.predict.FlatBaseline;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import net.querz.nbt.io.NBTUtil;
import net.querz.nbt.io.NamedTag;
import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.ListTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaperWorldMetadataTest {
    @TempDir
    Path temporary;

    @Test
    void derivesTheSameAbsoluteFlatSurfaceAsTheFabricCompanion() throws IOException {
        final Path levelDat = flatLevelDat(List.of(
            layer(1, "minecraft:bedrock"),
            layer(2, "minecraft:dirt"),
            layer(1, "minecraft:grass_block")
        ));

        final FlatBaseline baseline = PaperWorldMetadata.flatBaseline(
            levelDat,
            "minecraft:overworld",
            "minecraft:overworld",
            -64,
            ignored -> 19
        ).orElseThrow();

        assertEquals(-61, baseline.surfaceY());
        assertEquals(SurfaceKind.LAND.ordinal(), baseline.kind());
        assertEquals(19, baseline.mapColorId());
        assertEquals(0, baseline.fluidDepth());
    }

    @Test
    void preservesTopWaterDepthAndIgnoresTrailingAir() throws IOException {
        final Path levelDat = flatLevelDat(List.of(
            layer(1, "minecraft:bedrock"),
            layer(1, "minecraft:sand"),
            layer(2, "minecraft:water"),
            layer(4, "minecraft:air")
        ));

        final FlatBaseline baseline = PaperWorldMetadata.flatBaseline(
            levelDat,
            "example:missing",
            "minecraft:overworld",
            -64,
            ignored -> -1
        ).orElseThrow();

        assertEquals(-61, baseline.surfaceY());
        assertEquals(SurfaceKind.WATER.ordinal(), baseline.kind());
        assertEquals(12, baseline.mapColorId());
        assertEquals(2, baseline.fluidDepth());
    }

    @Test
    void rejectsFlatLayersOutsideTheProtocolHeightRange() throws IOException {
        final Path levelDat = flatLevelDat(List.of(
            layer(Integer.MAX_VALUE, "minecraft:stone"),
            layer(Integer.MAX_VALUE, "minecraft:stone")
        ));

        assertTrue(PaperWorldMetadata.flatBaseline(
            levelDat,
            "minecraft:overworld",
            "minecraft:overworld",
            -64,
            ignored -> 11
        ).isEmpty());
    }

    private Path flatLevelDat(final List<CompoundTag> layerValues) throws IOException {
        final CompoundTag root = new CompoundTag();
        final CompoundTag data = new CompoundTag();
        final CompoundTag worldGenSettings = new CompoundTag();
        final CompoundTag dimensions = new CompoundTag();
        final CompoundTag dimension = new CompoundTag();
        final CompoundTag generator = new CompoundTag();
        final CompoundTag settings = new CompoundTag();
        final ListTag<CompoundTag> layers = new ListTag<>(CompoundTag.class);
        layerValues.forEach(layers::add);
        settings.putString("biome", "minecraft:plains");
        settings.put("layers", layers);
        generator.putString("type", "minecraft:flat");
        generator.put("settings", settings);
        dimension.put("generator", generator);
        dimensions.put("minecraft:overworld", dimension);
        worldGenSettings.put("dimensions", dimensions);
        data.put("WorldGenSettings", worldGenSettings);
        root.put("Data", data);
        final Path file = temporary.resolve("level.dat");
        NBTUtil.write(new NamedTag("", root), file.toFile(), true);
        return file;
    }

    private static CompoundTag layer(final int height, final String block) {
        final CompoundTag layer = new CompoundTag();
        layer.putInt("height", height);
        layer.putString("block", block);
        return layer;
    }
}

package cn.net.rms.confluxmap.mc.snapshot;

import cn.net.rms.confluxmap.compat.Regs;
import cn.net.rms.confluxmap.core.color.MaterialDetailProfile;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.predict.BiomeTable;
import cn.net.rms.confluxmap.core.predict.CubiomesBiomeIds;
import cn.net.rms.confluxmap.core.predict.SyncedMaterialPalette;
import cn.net.rms.confluxmap.core.util.Argb;
import cn.net.rms.confluxmap.core.xaeroimport.XaeroBlockState;
import cn.net.rms.confluxmap.core.xaeroimport.XaeroColumn;
import cn.net.rms.confluxmap.core.xaeroimport.XaeroColumnBaker;
import cn.net.rms.confluxmap.mc.color.BiomeTintResolver;
import cn.net.rms.confluxmap.mc.color.SpriteColorSampler;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * Bakes imported world-map columns with the same color machinery live capture
 * uses: sprite-average base colors from the stitched atlas, biome tints from
 * the prediction biome table (imported columns carry a biome id, not a live
 * position to blend), and the shared surface-kind classifier. Water semantics
 * mirror {@link McChunkSnapshotFactory}: the surface color is the water itself
 * (tint pre-multiplied, tint channel white), the seafloor color rides in the
 * overlay plane, and the recovered depth separates them.
 *
 * <p>Texture/model lookups are main-thread only, so uncached states are
 * resolved through a main-thread hop from the import worker; the cache makes
 * that a one-time cost per distinct block state.
 */
public final class XaeroSnapshotBaker implements XaeroColumnBaker {
    private static final int UNKNOWN_BASE = 0xFF8C8C8C;
    private static final int NEUTRAL_TINT = 0xFFFFFFFF;
    private static final XaeroBlockState GRASS = new XaeroBlockState("minecraft:grass_block", Map.of());
    private static final XaeroBlockState WATER = new XaeroBlockState("minecraft:water", Map.of());

    private final MinecraftClient client;
    private final SpriteColorSampler sampler;
    private final BiomeTintResolver tints;
    private final Map<XaeroBlockState, StateMaterial> materials = new ConcurrentHashMap<>();
    private final Map<String, BiomeTable.Entry> biomeEntries = new ConcurrentHashMap<>();

    private record StateMaterial(
        int baseArgb,
        int xaeroBaseArgb,
        MaterialDetailProfile detail,
        int patternSalt,
        SyncedMaterialPalette.Tint tint,
        int fixedTintArgb,
        SurfaceKind kind
    ) {}

    public XaeroSnapshotBaker(final MinecraftClient client) {
        this.client = client;
        this.sampler = new SpriteColorSampler(client);
        this.tints = new BiomeTintResolver(client);
    }

    @Override
    public Baked bake(
        final XaeroColumn column,
        final int worldX,
        final int worldZ,
        final int waterDepth,
        final String biomeId,
        final boolean ceilingLayer
    ) {
        final XaeroBlockState surface = column.isGrassSurface() ? GRASS : column.surface();
        if (surface == null || isAir(stateId(surface))) {
            return Baked.voidColumn();
        }
        final XaeroBlockState topOverlay = column.overlays().isEmpty()
            ? null : column.overlays().get(0).state();
        final boolean iceSkin = topOverlay != null && isIce(stateId(topOverlay));
        if (column.hasWater()) {
            final StateMaterial skin = iceSkin ? material(topOverlay) : material(WATER);
            final int skinTint = iceSkin ? NEUTRAL_TINT : waterTint(biomeId);
            final StateMaterial floor = material(surface);
            return new Baked(
                tinted(skin, skinTint, worldX, worldZ),
                Argb.multiply(skin.xaeroBaseArgb(), skinTint),
                NEUTRAL_TINT,
                tinted(floor, tintFor(floor, biomeId), worldX, worldZ),
                floor.xaeroBaseArgb(),
                iceSkin ? SurfaceKind.ICE : SurfaceKind.WATER,
                surfaceAt(column, waterDepth)
            );
        }
        final StateMaterial material = material(surface);
        SurfaceKind kind = material.kind();
        if (ceilingLayer && isBedrock(stateId(surface))) {
            kind = SurfaceKind.BEDROCK_CEILING;
        }
        return new Baked(
            material.detail().apply(material.baseArgb(), worldX, worldZ, material.patternSalt()),
            material.xaeroBaseArgb(),
            tintFor(material, biomeId),
            0,
            0,
            kind,
            surfaceAt(column, 0)
        );
    }

    private static short surfaceAt(final XaeroColumn column, final int waterDepth) {
        return (short) Math.min((long) column.height() + waterDepth, Short.MAX_VALUE);
    }

    /** Water and ice pre-multiply their tint into the surface color; land keeps it separate. */
    private int tintFor(final StateMaterial material, final String biomeId) {
        return switch (material.tint()) {
            case GRASS -> grassTint(biomeId);
            case FOLIAGE -> foliageTint(biomeId);
            case WATER -> waterTint(biomeId);
            case FIXED -> material.fixedTintArgb();
            case NONE -> NEUTRAL_TINT;
        };
    }

    private int tinted(final StateMaterial material, final int tint, final int worldX, final int worldZ) {
        return material.detail().apply(
            Argb.multiply(material.baseArgb(), tint), worldX, worldZ, material.patternSalt()
        );
    }

    private StateMaterial material(final XaeroBlockState state) {
        final StateMaterial cached = materials.get(state);
        if (cached != null) {
            return cached;
        }
        final StateMaterial resolved = resolveOnMainThread(state);
        materials.put(state, resolved);
        return resolved;
    }

    /** Sprite sampling touches baked models; hop to the render thread for each uncached state. */
    private StateMaterial resolveOnMainThread(final XaeroBlockState state) {
        if (client.isOnThread()) {
            return resolve(state);
        }
        final CountDownLatch done = new CountDownLatch(1);
        final StateMaterial[] out = new StateMaterial[1];
        client.execute(() -> {
            try {
                out[0] = resolve(state);
            } finally {
                done.countDown();
            }
        });
        try {
            done.await();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out[0] != null ? out[0] : unknown();
    }

    private StateMaterial resolve(final XaeroBlockState state) {
        final BlockState resolved = resolveState(state);
        final ClientWorld world = client.world;
        if (resolved == null || world == null) {
            return unknown();
        }
        final BlockPos reference = client.player == null ? BlockPos.ORIGIN : client.player.getBlockPos();
        return new StateMaterial(
            sampler.baseColorFor(resolved, world, reference),
            sampler.xaeroColorFor(resolved, world, reference),
            sampler.detailProfileFor(resolved, world, reference),
            resolved.toString().hashCode(),
            tints.syncedTint(resolved),
            tints.fixedSyncedTint(resolved),
            McChunkSnapshotFactory.classifySurfaceKind(resolved.getBlock())
        );
    }

    private StateMaterial unknown() {
        return new StateMaterial(
            UNKNOWN_BASE, UNKNOWN_BASE, MaterialDetailProfile.flat(), 0,
            SyncedMaterialPalette.Tint.NONE, NEUTRAL_TINT, SurfaceKind.LAND
        );
    }

    private int grassTint(final String biomeId) {
        return biomeEntry(biomeId).grassTint();
    }

    private int foliageTint(final String biomeId) {
        return biomeEntry(biomeId).foliageTint();
    }

    private int waterTint(final String biomeId) {
        return biomeEntry(biomeId).waterTint();
    }

    private BiomeTable.Entry biomeEntry(final String biomeId) {
        final String key = biomeId == null ? "" : biomeId;
        return biomeEntries.computeIfAbsent(key, ignored -> BiomeTable.get(cubiomesId(biomeId)));
    }

    private static int cubiomesId(final String biomeId) {
        if (biomeId == null) {
            return -1;
        }
        final String name = biomeId.startsWith("minecraft:") ? biomeId.substring("minecraft:".length()) : biomeId;
        return CubiomesBiomeIds.idForName(name).orElse(-1);
    }

    private static boolean isAir(final String id) {
        return id.equals("minecraft:air") || id.equals("minecraft:void_air") || id.equals("minecraft:cave_air");
    }

    private static boolean isIce(final String id) {
        return id.equals("minecraft:ice");
    }

    private static boolean isBedrock(final String id) {
        return id.equals("minecraft:bedrock");
    }

    private static String stateId(final XaeroBlockState state) {
        return state.id() == null ? "" : state.id();
    }

    /**
     * Resolves a decoded state reference against the live registry. Unknown
     * blocks yield null (the caller substitutes a neutral material); unknown
     * properties fall back to the block default so a version drift on one
     * property cannot lose the whole column.
     */
    private static BlockState resolveState(final XaeroBlockState state) {
        if (stateId(state).isEmpty()) {
            return null;
        }
        final Identifier id = Identifier.tryParse(state.id());
        final Block block = id == null ? null : Regs.block(id).orElse(null);
        if (block == null) {
            return null;
        }
        BlockState resolved = block.getDefaultState();
        for (final Map.Entry<String, String> property : state.properties().entrySet()) {
            resolved = withProperty(resolved, property.getKey(), property.getValue());
        }
        return resolved;
    }

    private static BlockState withProperty(final BlockState state, final String name, final String value) {
        for (final Property<?> property : state.getProperties()) {
            if (!property.getName().equals(name)) {
                continue;
            }
            final java.util.Optional<?> parsed = property.parse(value);
            if (parsed.isPresent()) {
                return withParsed(state, property, parsed.get());
            }
        }
        return state;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState withParsed(final BlockState state, final Property property, final Object value) {
        return state.with(property, (Comparable) value);
    }
}

package cn.net.rms.confluxmap.mc.snapshot;

import cn.net.rms.confluxmap.compat.Regs;
import cn.net.rms.confluxmap.core.backfill.McaColumnBaker;
import cn.net.rms.confluxmap.core.color.MaterialDetailProfile;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.core.predict.BiomeTable;
import cn.net.rms.confluxmap.core.predict.SyncedMaterialPalette;
import cn.net.rms.confluxmap.core.util.Argb;
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
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * Bakes scanned Anvil summary columns with the same color machinery the
 * synchronized map uses: material ids resolved as registry default states
 * through the live texture atlas ({@link SpriteColorSampler}), biome tints
 * from the prediction biome table (summary columns carry numeric biome ids),
 * and flat detail for bedrock like {@code SyncedMaterialResolver} — the MCA
 * summary carries no bedrock thickness either. Water semantics mirror live
 * capture: the surface color is the fluid itself (tint pre-multiplied, tint
 * channel white), the seafloor color rides in the overlay plane.
 *
 * <p>Texture/model lookups are main-thread only, so uncached states are
 * resolved through a main-thread hop from the scan worker; the cache makes
 * that a one-time cost per distinct material id.
 */
public final class McaSnapshotBaker implements McaColumnBaker {
    private static final int UNKNOWN_BASE = 0xFF8C8C8C;
    private static final int NEUTRAL_TINT = 0xFFFFFFFF;

    private final MinecraftClient client;
    private final SpriteColorSampler sampler;
    private final BiomeTintResolver tints;
    private final Map<String, StateMaterial> materials = new ConcurrentHashMap<>();
    private final Map<Integer, BiomeTable.Entry> biomeEntries = new ConcurrentHashMap<>();

    private record StateMaterial(
        int baseArgb,
        int xaeroBaseArgb,
        MaterialDetailProfile detail,
        int patternSalt,
        SyncedMaterialPalette.Tint tint,
        int fixedTintArgb
    ) {}

    public McaSnapshotBaker(final MinecraftClient client) {
        this.client = client;
        this.sampler = new SpriteColorSampler(client);
        this.tints = new BiomeTintResolver(client);
    }

    @Override
    public Baked bake(
        final SummaryCodec.Column column,
        final int worldX,
        final int worldZ,
        final int cubiomesBiomeId
    ) {
        final SurfaceKind kind = SurfaceKind.byOrdinal(column.kind());
        if (kind == SurfaceKind.VOID || kind == SurfaceKind.UNKNOWN) {
            return Baked.empty();
        }
        final StateMaterial skin = material(column.materialId());
        if (column.fluidDepth() > 0 && (kind == SurfaceKind.WATER || kind == SurfaceKind.LAVA)) {
            final String floorId = column.floorMaterialId();
            final StateMaterial floor = floorId.isEmpty() ? null : material(floorId);
            final int skinTint = kind == SurfaceKind.WATER
                ? waterTint(cubiomesBiomeId)
                : tintFor(skin, cubiomesBiomeId);
            return new Baked(
                tinted(skin, skinTint, worldX, worldZ),
                Argb.multiply(skin.xaeroBaseArgb(), skinTint),
                NEUTRAL_TINT,
                floor == null ? 0 : tinted(floor, tintFor(floor, cubiomesBiomeId), worldX, worldZ),
                floor == null ? 0 : floor.xaeroBaseArgb()
            );
        }
        return new Baked(
            skin.detail().apply(skin.baseArgb(), worldX, worldZ, skin.patternSalt()),
            skin.xaeroBaseArgb(),
            tintFor(skin, cubiomesBiomeId),
            0,
            0
        );
    }

    /** Water pre-multiplies its tint into the surface color; land keeps the tint separate. */
    private int tintFor(final StateMaterial material, final int cubiomesBiomeId) {
        return switch (material.tint()) {
            case GRASS -> grassTint(cubiomesBiomeId);
            case FOLIAGE -> foliageTint(cubiomesBiomeId);
            case WATER -> waterTint(cubiomesBiomeId);
            case FIXED -> material.fixedTintArgb();
            case NONE -> NEUTRAL_TINT;
        };
    }

    private int tinted(
        final StateMaterial material, final int tint, final int worldX, final int worldZ
    ) {
        return material.detail().apply(
            Argb.multiply(material.baseArgb(), tint), worldX, worldZ, material.patternSalt()
        );
    }

    private StateMaterial material(final String materialId) {
        final StateMaterial cached = materials.get(materialId);
        if (cached != null) {
            return cached;
        }
        final StateMaterial resolved = resolveOnMainThread(materialId);
        materials.put(materialId, resolved);
        return resolved;
    }

    /** Sprite sampling touches baked models; hop to the render thread for each uncached id. */
    private StateMaterial resolveOnMainThread(final String materialId) {
        if (client.isOnThread()) {
            return resolve(materialId);
        }
        final CountDownLatch done = new CountDownLatch(1);
        final StateMaterial[] out = new StateMaterial[1];
        client.execute(() -> {
            try {
                out[0] = resolve(materialId);
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

    private StateMaterial resolve(final String materialId) {
        final BlockState state = resolveState(materialId);
        final ClientWorld world = client.world;
        if (state == null || world == null) {
            return unknown();
        }
        final BlockPos reference = client.player == null ? BlockPos.ORIGIN : client.player.getBlockPos();
        return new StateMaterial(
            sampler.baseColorFor(state, world, reference),
            sampler.xaeroColorFor(state, world, reference),
            // Summary columns carry no per-column bedrock count, so scanned bedrock stays
            // flat exactly like the synchronized map until the summary grows a thickness.
            state.getBlock() == Blocks.BEDROCK
                ? MaterialDetailProfile.flat()
                : sampler.detailProfileFor(state, world, reference),
            state.toString().hashCode(),
            tints.syncedTint(state),
            tints.fixedSyncedTint(state)
        );
    }

    private static StateMaterial unknown() {
        return new StateMaterial(
            UNKNOWN_BASE, UNKNOWN_BASE, MaterialDetailProfile.flat(), 0,
            SyncedMaterialPalette.Tint.NONE, NEUTRAL_TINT
        );
    }

    /**
     * Resolves a bare material id against the live registry default state; summary
     * columns carry plain block ids, so any id the registry does not know degrades
     * to the neutral material instead of losing the column.
     */
    private static BlockState resolveState(final String materialId) {
        if (materialId == null || materialId.isEmpty()) {
            return null;
        }
        final Identifier id = Identifier.tryParse(materialId);
        if (id == null) {
            return null;
        }
        final Block block = Regs.block(id).orElse(null);
        return block == null ? null : block.getDefaultState();
    }

    private int grassTint(final int cubiomesBiomeId) {
        return biomeEntry(cubiomesBiomeId).grassTint();
    }

    private int foliageTint(final int cubiomesBiomeId) {
        return biomeEntry(cubiomesBiomeId).foliageTint();
    }

    private int waterTint(final int cubiomesBiomeId) {
        return biomeEntry(cubiomesBiomeId).waterTint();
    }

    private BiomeTable.Entry biomeEntry(final int cubiomesBiomeId) {
        return biomeEntries.computeIfAbsent(cubiomesBiomeId, BiomeTable::get);
    }
}

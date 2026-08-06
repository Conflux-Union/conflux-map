package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.net.ChunkPatchCodec;
import cn.net.rms.confluxmap.core.net.CorrectionProfile;
import cn.net.rms.confluxmap.core.net.MapSyncCompatibility;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.ProtoException;
import cn.net.rms.confluxmap.core.store.WorldStorageMigration;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.util.ChunkRegionSlice;
import cn.net.rms.confluxmap.core.util.TileMath;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Session-scoped correction store with optional persistent tile files. */
public final class CorrectionStore {
    private static final Logger LOGGER = LogManager.getLogger("ConfluxMap/CorrectionStore");
    private static final int INVALIDATION_FORMAT_VERSION = 1;
    private static final int MAX_INVALIDATIONS = 65_536;

    public record Key(String dimension, int lod, int tileX, int tileZ) {
    }

    private final Path root;
    private WorldIdentity world = new WorldIdentity("local", "world");
    private Path worldRoot;
    private final Map<Key, CorrectionTile> tiles = new HashMap<>();
    private final Map<Key, Boolean> dirty = new HashMap<>();
    private final Map<Key, Long> invalidations = new HashMap<>();
    private long lastFlushMillis;

    public CorrectionStore(final Path root) {
        this.root = root;
        this.worldRoot = root.resolve(world.serverId()).resolve(world.worldId());
        loadInvalidations();
    }

    public synchronized CorrectionTile get(final DimensionId dimension, final int lod, final int tileX, final int tileZ) {
        return get(new Key(dimension.toString(), lod, tileX, tileZ));
    }

    public synchronized CorrectionTile get(final Key key) {
        CorrectionTile tile = tiles.get(key);
        if (tile != null) {
            return tile;
        }
        tile = new CorrectionTile(key.lod());
        final Path path = pathFor(key);
        if (Files.isRegularFile(path)) {
            try {
                final PredictionTileCodec.FileData data = PredictionTileCodec.read(path);
                tile.applyPatch(
                    data.revision(), data.presence(), data.patch(),
                    data.patchMode(), data.baselineProfile(),
                    data.hasChunkMetadata() ? data.validatedAtMillis() : 0L,
                    data.correctionProfile()
                );
                if (data.hasChunkMetadata()) {
                    tile.restoreChunkMetadata(
                        data.generatedChunks(), data.chunkRevisions(), data.chunkValidatedAtMillis()
                    );
                }
                if (invalidatedAfter(key, tile.newestValidatedAtMillis())) {
                    tile.invalidateValidation();
                }
            } catch (final IOException | ProtoException e) {
                quarantine(path);
            }
        }
        tiles.put(key, tile);
        return tile;
    }

    public synchronized boolean apply(
        final Key key, final long revision, final byte[] presence, final PatchCodec.Patch patch
    ) {
        return apply(key, revision, presence, patch, 0L);
    }

    public synchronized boolean apply(
        final Key key,
        final long revision,
        final byte[] presence,
        final PatchCodec.Patch patch,
        final long validatedAtMillis
    ) {
        return apply(
            key, revision, presence, patch,
            Proto.PATCH_MODE_RESIDUAL,
            MapSyncCompatibility.STABLE_PREDICTOR,
            validatedAtMillis
        );
    }

    public synchronized boolean apply(
        final Key key,
        final long revision,
        final byte[] presence,
        final PatchCodec.Patch patch,
        final int patchMode,
        final String baselineProfile,
        final long validatedAtMillis
    ) {
        return apply(
            key, revision, presence, patch, patchMode, baselineProfile, validatedAtMillis,
            CorrectionProfile.SOURCE_LIGHT_V2
        );
    }

    public synchronized boolean apply(
        final Key key,
        final long revision,
        final byte[] presence,
        final PatchCodec.Patch patch,
        final int patchMode,
        final String baselineProfile,
        final long validatedAtMillis,
        final CorrectionProfile correctionProfile
    ) {
        final CorrectionTile tile = get(key);
        final boolean changed = tile.applyPatch(
            revision, presence, patch, patchMode, baselineProfile, validatedAtMillis,
            correctionProfile
        );
        if (changed) {
            dirty.put(key, Boolean.TRUE);
        }
        return changed;
    }

    public synchronized boolean applyRegionSlice(
        final String dimension,
        final int lod,
        final ChunkRegionSlice slice,
        final ChunkPatchCodec.Patch patch,
        final long validatedAtMillis
    ) {
        return applyRegionSlice(
            dimension, lod, slice, patch,
            Proto.PATCH_MODE_RESIDUAL,
            MapSyncCompatibility.STABLE_PREDICTOR,
            validatedAtMillis
        );
    }

    public synchronized boolean applyRegionSlice(
        final String dimension,
        final int lod,
        final ChunkRegionSlice slice,
        final ChunkPatchCodec.Patch patch,
        final int patchMode,
        final String baselineProfile,
        final long validatedAtMillis
    ) {
        return applyRegionSlice(
            dimension, lod, slice, patch, patchMode, baselineProfile, validatedAtMillis,
            CorrectionProfile.SOURCE_LIGHT_V2
        );
    }

    public synchronized boolean applyRegionSlice(
        final String dimension,
        final int lod,
        final ChunkRegionSlice slice,
        final ChunkPatchCodec.Patch patch,
        final int patchMode,
        final String baselineProfile,
        final long validatedAtMillis,
        final CorrectionProfile correctionProfile
    ) {
        final RegionTarget target = regionTarget(dimension, lod, slice);
        final CorrectionTile tile = get(target.key());
        final boolean changed = tile.applyRegionSlice(
            target.minTileChunkX(), target.minTileChunkZ(), patch,
            patchMode, baselineProfile, validatedAtMillis, correctionProfile
        );
        if (changed) {
            dirty.put(target.key(), Boolean.TRUE);
        }
        return changed;
    }

    public synchronized boolean validateRegionSlice(
        final String dimension,
        final int lod,
        final ChunkRegionSlice slice,
        final long revision,
        final long validatedAtMillis
    ) {
        return validateRegionSlice(
            dimension, lod, slice, revision, validatedAtMillis,
            CorrectionProfile.SOURCE_LIGHT_V2
        );
    }

    public synchronized boolean validateRegionSlice(
        final String dimension,
        final int lod,
        final ChunkRegionSlice slice,
        final long revision,
        final long validatedAtMillis,
        final CorrectionProfile correctionProfile
    ) {
        final RegionTarget target = regionTarget(dimension, lod, slice);
        final CorrectionTile tile = get(target.key());
        if (tile.correctionProfile() != correctionProfile) {
            return false;
        }
        final boolean changed = tile.validateRegionSlice(
            target.minTileChunkX(), target.minTileChunkZ(),
            slice.width(), slice.height(), revision, validatedAtMillis,
            slice.regionX(), slice.regionZ(), slice.minLocalChunkX(), slice.minLocalChunkZ()
        );
        if (changed) {
            dirty.put(target.key(), Boolean.TRUE);
        }
        return changed;
    }

    public synchronized long regionSliceRevision(
        final String dimension, final int lod, final ChunkRegionSlice slice
    ) {
        final RegionTarget target = regionTarget(dimension, lod, slice);
        return get(target.key()).regionSliceRevision(
            target.minTileChunkX(), target.minTileChunkZ(), slice
        );
    }

    public synchronized long regionSliceRevision(
        final String dimension,
        final int lod,
        final ChunkRegionSlice slice,
        final int expectedPatchMode,
        final String expectedBaselineProfile
    ) {
        return regionSliceRevision(
            dimension, lod, slice, expectedPatchMode, expectedBaselineProfile,
            CorrectionProfile.SOURCE_LIGHT_V2
        );
    }

    public synchronized long regionSliceRevision(
        final String dimension,
        final int lod,
        final ChunkRegionSlice slice,
        final int expectedPatchMode,
        final String expectedBaselineProfile,
        final CorrectionProfile correctionProfile
    ) {
        final RegionTarget target = regionTarget(dimension, lod, slice);
        final CorrectionTile tile = get(target.key());
        if (!tile.matchesSource(
            expectedPatchMode, expectedBaselineProfile, correctionProfile
        )) {
            return Long.MIN_VALUE;
        }
        return tile.regionSliceRevision(
            target.minTileChunkX(), target.minTileChunkZ(), slice
        );
    }

    public synchronized boolean regionSliceFreshAt(
        final String dimension,
        final int lod,
        final ChunkRegionSlice slice,
        final long nowMillis,
        final long ttlMillis,
        final int expectedPatchMode,
        final String expectedBaselineProfile
    ) {
        return regionSliceFreshAt(
            dimension, lod, slice, nowMillis, ttlMillis,
            expectedPatchMode, expectedBaselineProfile,
            CorrectionProfile.SOURCE_LIGHT_V2
        );
    }

    public synchronized boolean regionSliceFreshAt(
        final String dimension,
        final int lod,
        final ChunkRegionSlice slice,
        final long nowMillis,
        final long ttlMillis
    ) {
        final RegionTarget target = regionTarget(dimension, lod, slice);
        return get(target.key()).regionSliceFreshAt(
            target.minTileChunkX(), target.minTileChunkZ(),
            slice.width(), slice.height(), nowMillis, ttlMillis
        );
    }

    public synchronized boolean regionSliceFreshAt(
        final String dimension,
        final int lod,
        final ChunkRegionSlice slice,
        final long nowMillis,
        final long ttlMillis,
        final int expectedPatchMode,
        final String expectedBaselineProfile,
        final CorrectionProfile correctionProfile
    ) {
        final RegionTarget target = regionTarget(dimension, lod, slice);
        final CorrectionTile tile = get(target.key());
        return tile.matchesSource(expectedPatchMode, expectedBaselineProfile, correctionProfile)
            && tile.regionSliceFreshAt(
                target.minTileChunkX(), target.minTileChunkZ(),
                slice.width(), slice.height(), nowMillis, ttlMillis
            );
    }

    public synchronized boolean invalidateRegionSlice(
        final String dimension, final int lod, final ChunkRegionSlice slice
    ) {
        final RegionTarget target = regionTarget(dimension, lod, slice);
        final CorrectionTile tile = get(target.key());
        final boolean changed = tile.invalidateRegionSlice(
            target.minTileChunkX(), target.minTileChunkZ(), slice.width(), slice.height()
        );
        if (changed) {
            dirty.put(target.key(), Boolean.TRUE);
        }
        return changed;
    }

    /** Refreshes an unchanged committed snapshot without treating an empty body as replacement. */
    public synchronized boolean validate(
        final Key key,
        final long revision,
        final byte[] presence,
        final long validatedAtMillis
    ) {
        return validate(
            key, revision, presence, validatedAtMillis,
            CorrectionProfile.SOURCE_LIGHT_V2
        );
    }

    public synchronized boolean validate(
        final Key key,
        final long revision,
        final byte[] presence,
        final long validatedAtMillis,
        final CorrectionProfile correctionProfile
    ) {
        final CorrectionTile tile = get(key);
        if (tile.correctionProfile() != correctionProfile) {
            return false;
        }
        final boolean changed = tile.validate(revision, presence, validatedAtMillis);
        if (changed) {
            dirty.put(key, Boolean.TRUE);
        }
        return changed;
    }

    /** Applies an ephemeral progressive scan overlay; it is intentionally not persisted. */
    public synchronized boolean applyPartial(
        final Key key, final byte[] presence, final PatchCodec.Patch patch
    ) {
        return get(key).applyPartial(presence, patch);
    }

    /** Invalidates every loaded correction overlapping an invalidated tile at any LOD. */
    public synchronized boolean invalidateCoverage(final Key area) {
        return invalidateCoverages(List.of(area));
    }

    /** Applies one bounded server invalidation batch and persists its journal once. */
    public synchronized boolean invalidateCoverages(final Collection<Key> areas) {
        if (areas == null || areas.isEmpty()) {
            return false;
        }
        for (final Map.Entry<Key, CorrectionTile> entry : tiles.entrySet()) {
            if (overlapsAny(areas, entry.getKey()) && entry.getValue().invalidateValidation()) {
                dirty.put(entry.getKey(), Boolean.TRUE);
            }
        }
        final long now = System.currentTimeMillis();
        for (final Key area : areas) {
            if (area == null) {
                continue;
            }
            invalidations.entrySet().removeIf(
                entry -> covers(area, entry.getKey()) && entry.getValue() <= now
            );
            invalidations.put(area, now);
        }
        pruneInvalidations(now);
        persistInvalidations();
        return true;
    }

    public synchronized void flush() {
        for (final Key key : dirty.keySet().toArray(new Key[0])) {
            final CorrectionTile tile = tiles.get(key);
            try {
                PredictionTileCodec.writeAtomic(pathFor(key), new PredictionTileCodec.FileData(
                    key.lod(), key.tileX(), key.tileZ(), tile.storedRevision(), tile.validatedAtMillis(),
                    tile.presence(), tile.copyPatch(), tile.copyGeneratedChunkMask(),
                    tile.copyChunkRevisions(), tile.copyChunkValidatedAtMillis(),
                    tile.patchMode(), tile.baselineProfile(), tile.correctionProfile()
                ));
                dirty.remove(key);
            } catch (final IOException e) {
                // Keep it dirty; a later session-end flush can retry.
            }
        }
        lastFlushMillis = System.currentTimeMillis();
    }

    public synchronized void flushIfDue(final long nowMillis) {
        if (!dirty.isEmpty() && (lastFlushMillis == 0L || nowMillis - lastFlushMillis >= 30_000L)) {
            flush();
        }
    }

    public synchronized void clear() {
        tiles.clear();
        dirty.clear();
    }

    /** Main thread, from the session tracker: binds corrections to the same identity as all other world storage. */
    public synchronized void onSessionChanged(final SessionGuard.Session session) {
        if (!session.active()) {
            flush();
            clear();
            return;
        }
        setNamespace(session.world());
    }

    public synchronized void setNamespace(final WorldIdentity world) {
        if (this.world.equals(world)) {
            return;
        }
        flush();
        clear();
        this.world = world;
        this.worldRoot = WorldStorageMigration.directory(root, world, LOGGER);
        loadInvalidations();
    }

    private Path pathFor(final Key key) {
        return worldRoot.resolve(sanitize(key.dimension())).resolve("pred").resolve(Integer.toString(key.lod()))
            .resolve("t." + key.tileX() + "." + key.tileZ() + ".cfp");
    }

    private static boolean overlaps(final Key first, final Key second) {
        return first.dimension().equals(second.dimension()) && TileMath.overlaps(
            first.lod(), first.tileX(), first.tileZ(),
            second.lod(), second.tileX(), second.tileZ()
        );
    }

    private static boolean overlapsAny(final Collection<Key> areas, final Key candidate) {
        for (final Key area : areas) {
            if (area != null && overlaps(area, candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean invalidatedAfter(final Key key, final long validatedAtMillis) {
        if (validatedAtMillis <= 0L) {
            return false;
        }
        for (final Map.Entry<Key, Long> invalidation : invalidations.entrySet()) {
            if (invalidation.getValue() >= validatedAtMillis && overlaps(invalidation.getKey(), key)) {
                return true;
            }
        }
        return false;
    }

    private static boolean covers(final Key area, final Key candidate) {
        return area.dimension().equals(candidate.dimension())
            && area.lod() >= candidate.lod()
            && overlaps(area, candidate);
    }

    private void pruneInvalidations(final long nowMillis) {
        final long oldestUseful = nowMillis - PredictionTileService.CORRECTION_REUSE_TTL_MS;
        invalidations.entrySet().removeIf(entry -> entry.getValue() < oldestUseful);
        while (invalidations.size() > MAX_INVALIDATIONS) {
            final Key oldest = invalidations.entrySet().stream()
                .min(Comparator.comparingLong(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .orElse(null);
            if (oldest == null) {
                break;
            }
            invalidations.remove(oldest);
        }
    }

    private Path invalidationPath() {
        return worldRoot.resolve("correction-invalidations.cfi");
    }

    private void loadInvalidations() {
        invalidations.clear();
        final Path path = invalidationPath();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if (in.readInt() != 0x43464956 || in.readUnsignedByte() != INVALIDATION_FORMAT_VERSION) {
                throw new IOException("invalid correction invalidation header");
            }
            final int count = in.readInt();
            if (count < 0 || count > MAX_INVALIDATIONS) {
                throw new IOException("invalid correction invalidation count " + count);
            }
            for (int i = 0; i < count; i++) {
                final Key key = new Key(in.readUTF(), in.readUnsignedByte(), in.readInt(), in.readInt());
                if (key.lod() < 0 || key.lod() > TileMath.MAX_LOD) {
                    throw new IOException("invalid correction invalidation LOD " + key.lod());
                }
                invalidations.put(key, in.readLong());
            }
            pruneInvalidations(System.currentTimeMillis());
        } catch (final IOException e) {
            quarantine(path);
            invalidations.clear();
        }
    }

    private void persistInvalidations() {
        final Path path = invalidationPath();
        final Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
                out.writeInt(0x43464956);
                out.writeByte(INVALIDATION_FORMAT_VERSION);
                out.writeInt(invalidations.size());
                for (final Map.Entry<Key, Long> entry : invalidations.entrySet()) {
                    out.writeUTF(entry.getKey().dimension());
                    out.writeByte(entry.getKey().lod());
                    out.writeInt(entry.getKey().tileX());
                    out.writeInt(entry.getKey().tileZ());
                    out.writeLong(entry.getValue());
                }
            }
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (final java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (final IOException ignored) {
                // Best effort cleanup; a stale temp file is never read as authoritative state.
            }
        }
    }

    private static String sanitize(final String value) {
        final String cleaned = value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.startsWith(".") ? "_" + cleaned.replaceFirst("^\\.+", "") : cleaned;
    }

    private record RegionTarget(Key key, int minTileChunkX, int minTileChunkZ) {
    }

    private static RegionTarget regionTarget(
        final String dimension, final int lod, final ChunkRegionSlice slice
    ) {
        if (dimension == null || slice == null || lod < 0 || lod > TileMath.MAX_LOD) {
            throw new IllegalArgumentException("invalid correction region target");
        }
        final int chunksPerTile = 16 << lod;
        final int minChunkX = slice.minChunkX();
        final int minChunkZ = slice.minChunkZ();
        final int maxChunkX = Math.addExact(minChunkX, slice.width() - 1);
        final int maxChunkZ = Math.addExact(minChunkZ, slice.height() - 1);
        final int tileX = Math.floorDiv(minChunkX, chunksPerTile);
        final int tileZ = Math.floorDiv(minChunkZ, chunksPerTile);
        if (Math.floorDiv(maxChunkX, chunksPerTile) != tileX
            || Math.floorDiv(maxChunkZ, chunksPerTile) != tileZ) {
            throw new IllegalArgumentException("summary region crosses a correction tile");
        }
        return new RegionTarget(
            new Key(dimension, lod, tileX, tileZ),
            Math.floorMod(minChunkX, chunksPerTile),
            Math.floorMod(minChunkZ, chunksPerTile)
        );
    }

    private static void quarantine(final Path path) {
        try {
            Files.move(path, path.resolveSibling(path.getFileName() + ".bad"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // Corrupt cache data is non-authoritative; the next patch can recreate it.
        }
    }
}

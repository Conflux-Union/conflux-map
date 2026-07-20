package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Session-scoped correction store with optional persistent tile files. */
public final class CorrectionStore {
    public record Key(String dimension, int lod, int tileX, int tileZ) {
    }

    private final Path root;
    private WorldIdentity world = new WorldIdentity("local", "world");
    private final Map<Key, CorrectionTile> tiles = new HashMap<>();
    private final Map<Key, Boolean> dirty = new HashMap<>();
    private long lastFlushMillis;

    public CorrectionStore(final Path root) {
        this.root = root;
    }

    public synchronized CorrectionTile get(final DimensionId dimension, final int lod, final int tileX, final int tileZ) {
        return get(new Key(dimension.toString(), lod, tileX, tileZ));
    }

    public synchronized CorrectionTile get(final Key key) {
        CorrectionTile tile = tiles.get(key);
        if (tile != null) {
            return tile;
        }
        tile = new CorrectionTile();
        final Path path = pathFor(key);
        if (Files.isRegularFile(path)) {
            try {
                final PredictionTileCodec.FileData data = PredictionTileCodec.read(path);
                tile.applyPatch(data.revision(), data.presence(), data.patch());
            } catch (final IOException | cn.net.rms.confluxmap.core.net.ProtoException e) {
                quarantine(path);
            }
        }
        tiles.put(key, tile);
        return tile;
    }

    public synchronized boolean apply(
        final Key key, final long revision, final byte[] presence, final PatchCodec.Patch patch
    ) {
        final CorrectionTile tile = get(key);
        final boolean changed = tile.applyPatch(revision, presence, patch);
        if (changed) {
            dirty.put(key, Boolean.TRUE);
        }
        return changed;
    }

    public synchronized void flush() {
        for (final Key key : dirty.keySet().toArray(new Key[0])) {
            final CorrectionTile tile = tiles.get(key);
            try {
                PredictionTileCodec.writeAtomic(pathFor(key), new PredictionTileCodec.FileData(
                    key.lod(), key.tileX(), key.tileZ(), tile.revision(), tile.presence(), tile.copyPatch()
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
    }

    private Path pathFor(final Key key) {
        return root.resolve(sanitize(world.serverId())).resolve(sanitize(world.worldId()))
            .resolve(sanitize(key.dimension())).resolve("pred").resolve(Integer.toString(key.lod()))
            .resolve("t." + key.tileX() + "." + key.tileZ() + ".cfp");
    }

    private static String sanitize(final String value) {
        final String cleaned = value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.startsWith(".") ? "_" + cleaned.replaceFirst("^\\.+", "") : cleaned;
    }

    private static void quarantine(final Path path) {
        try {
            Files.move(path, path.resolveSibling(path.getFileName() + ".bad"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // Corrupt cache data is non-authoritative; the next patch can recreate it.
        }
    }
}

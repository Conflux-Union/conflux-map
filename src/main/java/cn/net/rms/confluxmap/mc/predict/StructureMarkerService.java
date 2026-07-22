package cn.net.rms.confluxmap.mc.predict;

import cn.net.rms.confluxmap.core.predict.PredictionDimensions;
import cn.net.rms.confluxmap.core.predict.PredictionState;
import cn.net.rms.confluxmap.core.predict.StructureIndex;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.nativepredict.CubiomesContext;
import cn.net.rms.confluxmap.nativepredict.CubiomesContexts;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Owns structure-marker session state, native candidate lookup, and persistence. UI code only
 * supplies a visible block rectangle and renders the returned markers.
 */
public final class StructureMarkerService {
    private final Path cacheRoot;
    private final PredictionState prediction;
    private StructureIndex current;

    public StructureMarkerService(final Path cacheRoot, final PredictionState prediction) {
        this.cacheRoot = cacheRoot;
        this.prediction = prediction;
    }

    public synchronized void onSessionChanged(final SessionGuard.Session session) {
        flush();
        current = session.active()
            ? new StructureIndex(
                cacheRoot,
                session.world(),
                session.dimension(),
                (type, regionX, regionZ) -> candidates(session, type, regionX, regionZ)
            )
            : null;
    }

    public synchronized List<StructureIndex.Marker> query(
        final int minBlockX,
        final int maxBlockX,
        final int minBlockZ,
        final int maxBlockZ
    ) {
        return current == null ? List.of() : current.query(minBlockX, maxBlockX, minBlockZ, maxBlockZ);
    }

    public synchronized void flush() {
        if (current != null) {
            current.save();
        }
    }

    private long[] candidates(
        final SessionGuard.Session session,
        final StructureIndex.StructureType type,
        final int regionX,
        final int regionZ
    ) {
        // Structure lookup is always cubiomes-backed: a flat underlay has no structure model.
        if (!type.supports(session.dimension()) || !prediction.cubiomesBacked(session.dimension())) {
            return new long[0];
        }
        final int nativeDim = PredictionDimensions.nativeDim(session.dimension());
        if (nativeDim < 0) {
            return new long[0];
        }
        try {
            final CubiomesContext context = CubiomesContexts.get(
                prediction.mcVersion(),
                prediction.seed(),
                nativeDim,
                prediction.cubiomesFlags(session.dimension())
            );
            if (context == null) {
                return new long[0];
            }
            final long[] positions = new long[1];
            final int count = context.structures(type.nativeId(), regionX, regionZ, regionX, regionZ, positions);
            if (count <= 0) {
                return new long[0];
            }
            return Arrays.copyOf(positions, Math.min(count, positions.length));
        } catch (final Throwable fault) {
            NativeLib.disableForSession(fault);
            return new long[0];
        }
    }
}

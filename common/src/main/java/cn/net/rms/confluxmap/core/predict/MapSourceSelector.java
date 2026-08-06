package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.net.CorrectionProfile;

/** Chooses the visible source for one chunk without confusing source time with cache validation. */
public final class MapSourceSelector {
    public static final long UNKNOWN_REVISION = Long.MIN_VALUE;

    private MapSourceSelector() {
    }

    /**
     * Whether evaluated synchronized terrain should be drawn over a local real chunk.
     * Legacy profiles retain the historical local-first rule. Equal revisions also prefer local
     * because its captured pixels have greater detail than a sampled correction.
     */
    public static boolean syncWins(
        final boolean localPresent,
        final long localRevision,
        final boolean syncEvaluated,
        final long syncRevision,
        final CorrectionProfile profile
    ) {
        if (!syncEvaluated) {
            return false;
        }
        if (!localPresent) {
            return true;
        }
        if (!profile.carriesSourceMetadata()) {
            return false;
        }
        final boolean localKnown = localRevision != UNKNOWN_REVISION;
        final boolean syncKnown = syncRevision != UNKNOWN_REVISION;
        if (localKnown != syncKnown) {
            return syncKnown;
        }
        return syncKnown && syncRevision > localRevision;
    }
}

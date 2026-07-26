package cn.net.rms.confluxmap.core.net;

/** Stable presentation bands for server-authoritative effective chunk ticket levels. */
public enum ChunkLoadBand {
    UNLOADED(0),
    BORDER(1),
    BLOCK_TICKING(2),
    ENTITY_TICKING(3);

    private final int wireId;

    ChunkLoadBand(final int wireId) {
        this.wireId = wireId;
    }

    public int wireId() {
        return wireId;
    }

    /** Vanilla's effective full-chunk thresholds are stable across the supported versions. */
    public static ChunkLoadBand fromTicketLevel(final int level) {
        if (level <= 31) {
            return ENTITY_TICKING;
        }
        if (level == 32) {
            return BLOCK_TICKING;
        }
        if (level == 33) {
            return BORDER;
        }
        return UNLOADED;
    }

    public static ChunkLoadBand fromWireId(final int wireId) throws ProtoException {
        for (final ChunkLoadBand band : values()) {
            if (band.wireId == wireId) {
                return band;
            }
        }
        throw new ProtoException("unknown chunk load band: " + wireId);
    }
}

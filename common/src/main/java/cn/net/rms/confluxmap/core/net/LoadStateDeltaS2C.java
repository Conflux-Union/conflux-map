package cn.net.rms.confluxmap.core.net;

import java.util.List;

/** S2C bounded load-state snapshot/delta batch for one client subscription generation. */
public record LoadStateDeltaS2C(
    int subscriptionId,
    boolean reset,
    boolean complete,
    List<Entry> entries
) implements Message {
    public LoadStateDeltaS2C {
        entries = List.copyOf(entries);
    }

    public record Entry(int chunkX, int chunkZ, int level, ChunkLoadBand band) {
    }

    @Override
    public int typeId() {
        return Proto.MSG_LOAD_STATE_DELTA_S2C;
    }
}

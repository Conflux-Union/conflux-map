package cn.net.rms.confluxmap.core.terrain;

import java.util.Set;

public final class MissingMaterialsException extends Exception {
    private final Set<Integer> stateIds;

    MissingMaterialsException(final Set<Integer> stateIds) {
        super("missing " + stateIds.size() + " material descriptors");
        this.stateIds = Set.copyOf(stateIds);
    }

    public Set<Integer> stateIds() {
        return stateIds;
    }
}

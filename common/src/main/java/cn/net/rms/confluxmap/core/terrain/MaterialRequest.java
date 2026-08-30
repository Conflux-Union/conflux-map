package cn.net.rms.confluxmap.core.terrain;

import java.util.Set;

public record MaterialRequest(Set<Integer> stateIds) {
    public MaterialRequest {
        stateIds = Set.copyOf(stateIds);
    }
}

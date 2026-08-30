package cn.net.rms.confluxmap.terrain.protocol;

import java.util.Set;

public record MaterialRequest(Set<Integer> stateIds) {
    public MaterialRequest {
        stateIds = Set.copyOf(stateIds);
    }
}

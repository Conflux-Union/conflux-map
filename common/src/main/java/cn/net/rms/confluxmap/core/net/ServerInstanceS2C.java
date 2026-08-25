package cn.net.rms.confluxmap.core.net;

import java.util.Objects;

/**
 * Identity of the server instance itself, advertised only to capability-aware clients.
 *
 * <p>Distinct from {@link HelloPolicyS2C#worldId()}, which is stored inside the world save and so
 * survives a copy: two servers sharing a synced world advertise the same world id. This value
 * lives beside the companion configuration, so each server keeps its own even when the worlds are
 * identical, and the client uses it to decide which map data belongs to whom.
 */
public record ServerInstanceS2C(String instanceId) implements Message {
    public ServerInstanceS2C {
        Objects.requireNonNull(instanceId, "instanceId");
    }

    @Override
    public int typeId() {
        return Proto.MSG_SERVER_INSTANCE_S2C;
    }
}

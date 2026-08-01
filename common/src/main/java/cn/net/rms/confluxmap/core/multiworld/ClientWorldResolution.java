package cn.net.rms.confluxmap.core.multiworld;

/** Result of matching one upstream observation to client-owned logical worlds. */
public record ClientWorldResolution(State state, ClientWorldProfile profile) {
    public enum State { COLLECTING, RESOLVED, AMBIGUOUS }

    public static ClientWorldResolution resolved(final ClientWorldProfile profile) {
        return new ClientWorldResolution(State.RESOLVED, profile);
    }

    public static ClientWorldResolution collecting() {
        return new ClientWorldResolution(State.COLLECTING, null);
    }

    public static ClientWorldResolution ambiguous() {
        return new ClientWorldResolution(State.AMBIGUOUS, null);
    }
}

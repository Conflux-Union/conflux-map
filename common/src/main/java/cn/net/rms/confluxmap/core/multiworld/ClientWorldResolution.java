package cn.net.rms.confluxmap.core.multiworld;

import java.util.List;
import java.util.Objects;

/** Result of matching one upstream observation to client-owned logical worlds. */
public record ClientWorldResolution(State state, ClientWorldProfile profile, List<Candidate> candidates, String error) {
    public ClientWorldResolution(final State state, final ClientWorldProfile profile, final List<Candidate> candidates) {
        this(state, profile, candidates, null);
    }

    public ClientWorldResolution {
        state = Objects.requireNonNull(state, "state");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        error = error == null || error.isBlank() ? null : error;
    }

    public enum State { COLLECTING, RESOLVED, AMBIGUOUS, PERSISTENCE_FAILED }

    public static ClientWorldResolution resolved(final ClientWorldProfile profile) {
        return new ClientWorldResolution(State.RESOLVED, profile, List.of());
    }

    public static ClientWorldResolution collecting() {
        return new ClientWorldResolution(State.COLLECTING, null, List.of());
    }

    public static ClientWorldResolution ambiguous() {
        return new ClientWorldResolution(State.AMBIGUOUS, null, List.of());
    }

    public static ClientWorldResolution ambiguous(final List<Candidate> candidates) {
        return new ClientWorldResolution(State.AMBIGUOUS, null, candidates);
    }

    public static ClientWorldResolution persistenceFailed(final String error) {
        return new ClientWorldResolution(State.PERSISTENCE_FAILED, null, List.of(), error);
    }

    /** A display-safe candidate explanation for manual world selection. */
    public record Candidate(String profileId, int confidencePercent, List<String> reasons, boolean conflicted) {
        public Candidate {
            profileId = Objects.requireNonNull(profileId, "profileId");
            confidencePercent = Math.max(0, Math.min(100, confidencePercent));
            reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
        }
    }
}

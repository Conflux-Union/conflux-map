package cn.net.rms.confluxmap.core.multiworld;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Result of matching one upstream observation to client-owned logical worlds. */
public record ClientWorldResolution(
    State state,
    ClientWorldProfile profile,
    List<Candidate> candidates,
    String error,
    boolean provisional,
    ConfirmationSource confirmationSource
) {
    public ClientWorldResolution(final State state, final ClientWorldProfile profile, final List<Candidate> candidates) {
        this(state, profile, candidates, null, false, ConfirmationSource.NONE);
    }

    public ClientWorldResolution(
        final State state,
        final ClientWorldProfile profile,
        final List<Candidate> candidates,
        final String error
    ) {
        this(state, profile, candidates, error, false, ConfirmationSource.NONE);
    }

    public ClientWorldResolution(
        final State state,
        final ClientWorldProfile profile,
        final List<Candidate> candidates,
        final String error,
        final boolean provisional
    ) {
        this(state, profile, candidates, error, provisional, ConfirmationSource.NONE);
    }

    public ClientWorldResolution {
        state = Objects.requireNonNull(state, "state");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        error = error == null || error.isBlank() ? null : error;
        confirmationSource = Objects.requireNonNull(confirmationSource, "confirmationSource");
    }

    public enum State { COLLECTING, RESOLVED, AMBIGUOUS, PERSISTENCE_FAILED }

    public enum ConfirmationSource {
        NONE, AUTOMATIC, TERRAIN, STABLE_SIGNALS, CONTINUITY_PROVISIONAL, MANUAL, COMMAND, CREATED
    }

    public static ClientWorldResolution resolved(final ClientWorldProfile profile) {
        return resolved(profile, List.of(), ConfirmationSource.AUTOMATIC);
    }

    public static ClientWorldResolution resolved(
        final ClientWorldProfile profile,
        final List<Candidate> candidates,
        final ConfirmationSource source
    ) {
        return new ClientWorldResolution(State.RESOLVED, profile, candidates, null, false, source);
    }

    public static ClientWorldResolution provisional(final ClientWorldProfile profile) {
        return provisional(profile, List.of());
    }

    public static ClientWorldResolution provisional(final ClientWorldProfile profile, final List<Candidate> candidates) {
        return new ClientWorldResolution(
            State.RESOLVED, profile, candidates, null, true, ConfirmationSource.CONTINUITY_PROVISIONAL
        );
    }

    public static ClientWorldResolution collecting() {
        return new ClientWorldResolution(State.COLLECTING, null, List.of(), null, false, ConfirmationSource.NONE);
    }

    public static ClientWorldResolution ambiguous() {
        return ambiguous(List.of());
    }

    public static ClientWorldResolution ambiguous(final List<Candidate> candidates) {
        return new ClientWorldResolution(
            State.AMBIGUOUS, null, candidates, null, false, ConfirmationSource.NONE
        );
    }

    public static ClientWorldResolution persistenceFailed(final String error) {
        return persistenceFailed(error, List.of());
    }

    public static ClientWorldResolution persistenceFailed(final String error, final List<Candidate> candidates) {
        return new ClientWorldResolution(
            State.PERSISTENCE_FAILED, null, candidates, error, false, ConfirmationSource.NONE
        );
    }

    public enum CandidateOutcome {
        AUTO_RESOLVED, PROVISIONAL, MANUAL_REQUIRED, BLOCKED, CONFLICTED, UNSCORED
    }

    public enum FactorAvailability { AVAILABLE, UNAVAILABLE, NOT_APPLICABLE }

    /** One factor as actually consumed by the resolver; UI must display, not recompute, these values. */
    public record Factor(
        String key,
        FactorAvailability availability,
        double rawScore,
        double configuredWeight,
        double effectiveWeight,
        double contribution,
        boolean veto,
        Map<String, String> metrics
    ) {
        public Factor {
            key = Objects.requireNonNull(key, "key");
            availability = Objects.requireNonNull(availability, "availability");
            rawScore = clamp(rawScore);
            configuredWeight = clamp(configuredWeight);
            effectiveWeight = clamp(effectiveWeight);
            contribution = clamp(contribution);
            metrics = Map.copyOf(Objects.requireNonNull(metrics, "metrics"));
        }

        public static Factor unavailable(final String key, final double configuredWeight) {
            return new Factor(
                key, FactorAvailability.UNAVAILABLE, 0.0D, configuredWeight, 0.0D, 0.0D, false, Map.of()
            );
        }
    }

    /** A complete, display-safe explanation for one candidate. */
    public record Candidate(
        String profileId,
        boolean scored,
        int confidencePercent,
        int queue,
        int requiredConfidencePercent,
        int runnerUpConfidencePercent,
        int requiredMarginPercent,
        int actualMarginPercent,
        int independentFactors,
        boolean seedCompatible,
        boolean sameSeedCandidates,
        CandidateOutcome outcome,
        List<Factor> factors,
        List<String> reasons,
        List<String> blockers,
        boolean conflicted
    ) {
        /** Compatibility constructor for older tests and non-scoring callers. */
        public Candidate(
            final String profileId,
            final int confidencePercent,
            final List<String> reasons,
            final boolean conflicted
        ) {
            this(
                profileId, true, confidencePercent, 3, 0, 0, 0, 0, 0,
                true, false, conflicted ? CandidateOutcome.CONFLICTED : CandidateOutcome.MANUAL_REQUIRED,
                List.of(), reasons, conflicted ? List.of("conflict") : List.of(), conflicted
            );
        }

        public Candidate {
            profileId = Objects.requireNonNull(profileId, "profileId");
            confidencePercent = percent(confidencePercent);
            queue = Math.max(1, Math.min(3, queue));
            requiredConfidencePercent = percent(requiredConfidencePercent);
            runnerUpConfidencePercent = percent(runnerUpConfidencePercent);
            requiredMarginPercent = percent(requiredMarginPercent);
            actualMarginPercent = Math.max(-100, Math.min(100, actualMarginPercent));
            independentFactors = Math.max(0, independentFactors);
            outcome = Objects.requireNonNull(outcome, "outcome");
            factors = List.copyOf(Objects.requireNonNull(factors, "factors"));
            reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
            blockers = List.copyOf(Objects.requireNonNull(blockers, "blockers"));
        }
    }

    private static int percent(final int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static double clamp(final double value) {
        return Double.isFinite(value) ? Math.max(0.0D, Math.min(1.0D, value)) : 0.0D;
    }
}

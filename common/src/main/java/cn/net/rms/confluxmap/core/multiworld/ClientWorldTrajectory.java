package cn.net.rms.confluxmap.core.multiworld;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/** Bounded local movement history used when the final server movement packet was not received. */
public final class ClientWorldTrajectory {
    public static final int DEFAULT_CAPACITY = 240;
    public static final long DEFAULT_MAX_PREDICTION_MS = 5_000L;
    private static final long VELOCITY_FIT_WINDOW_MS = 2_000L;
    private static final double MIN_PREDICTION_SPEED = 0.01D;
    private static final double MIN_REASONABLE_DISPLACEMENT = 32.0D;
    private static final double DISPLACEMENT_SAFETY_FACTOR = 4.0D;
    private static final long MIN_SAMPLE_INTERVAL_MS = 1L;

    private final int capacity;
    private final Deque<ClientWorldTrajectorySample> samples;
    private long lastServerAckTimeMs = ClientWorldTrajectorySample.NO_SERVER_ACK;
    private long lastServerAckSequence = -1L;
    private DiscontinuityReason lastDiscontinuity = DiscontinuityReason.NONE;
    /** Allows one new connection sample to follow restored history without bridging segments. */
    private boolean restoredHistoryBoundary;

    /** Causal distance from a candidate's departure endpoint to the current entry point. */
    public record CausalCorridor(
        double alongDistance,
        double lateralDistance,
        double predictedLength,
        double endpointDistance,
        long elapsedMs
    ) {
        public double centerlineDistance() {
            return Math.max(0.0D, predictedLength - alongDistance);
        }
    }

    public ClientWorldTrajectory() {
        this(DEFAULT_CAPACITY);
    }

    public ClientWorldTrajectory(final int capacity) {
        if (capacity < 2) {
            throw new IllegalArgumentException("capacity must be at least 2");
        }
        this.capacity = capacity;
        this.samples = new ArrayDeque<>(capacity);
    }

    /** Rehydrates a bounded trajectory from a persisted checkpoint. */
    public static ClientWorldTrajectory fromSamples(
        final List<ClientWorldTrajectorySample> persisted,
        final int capacity
    ) {
        final ClientWorldTrajectory trajectory = new ClientWorldTrajectory(capacity);
        if (persisted != null) {
            for (final ClientWorldTrajectorySample sample : persisted) {
                if (sample != null) {
                    trajectory.append(sample);
                }
            }
        }
        return trajectory;
    }

    /** Rehydrates history across a process/network boundary without joining generations. */
    public static ClientWorldTrajectory fromHistoricalSamples(
        final List<ClientWorldTrajectorySample> persisted,
        final int capacity
    ) {
        final ClientWorldTrajectory trajectory = new ClientWorldTrajectory(capacity);
        trajectory.restoreHistoricalSamples(persisted);
        return trajectory;
    }

    public int capacity() {
        return capacity;
    }

    public List<ClientWorldTrajectorySample> samples() {
        return List.copyOf(samples);
    }

    public ClientWorldTrajectory copy() {
        final ClientWorldTrajectory copy = new ClientWorldTrajectory(capacity);
        copy.samples.addAll(samples);
        copy.lastServerAckTimeMs = lastServerAckTimeMs;
        copy.lastServerAckSequence = lastServerAckSequence;
        copy.lastDiscontinuity = lastDiscontinuity;
        copy.restoredHistoryBoundary = restoredHistoryBoundary;
        return copy;
    }

    public ClientWorldTrajectorySample latest() {
        return samples.peekLast();
    }

    public long lastServerAckTimeMs() {
        return lastServerAckTimeMs;
    }

    public long lastServerAckSequence() {
        return lastServerAckSequence;
    }

    public boolean hasUsableContinuity(final String dimensionId, final long nowMs, final long maxAgeMs) {
        final ClientWorldTrajectorySample latest = latest();
        return latest != null
            && latest.dimensionId().equals(dimensionId)
            && nowMs >= latest.clientTimeMs()
            && nowMs - latest.clientTimeMs() <= Math.max(0L, maxAgeMs);
    }

    /** Confidence from the nearest point in observed history or its bounded forward corridor. */
    public double corridorConfidence(
        final ClientWorldPosition target,
        final String dimensionId,
        final long nowMs,
        final double baseRadius,
        final long horizonMs
    ) {
        if (!hasUsableContinuity(dimensionId, nowMs, Math.max(DEFAULT_MAX_PREDICTION_MS, horizonMs))) {
            return 0.0D;
        }
        final double distance = nearestDistance(target, nowMs, horizonMs);
        final double uncertainty = uncertaintyRadius(nowMs, baseRadius);
        return ClientWorldProfileResolver.corridorPositionConfidence(distance, uncertainty);
    }

    public DiscontinuityReason lastDiscontinuity() {
        return lastDiscontinuity;
    }

    /** Adds a sample, resetting the history when it cannot be part of the previous trajectory. */
    public AppendResult append(final ClientWorldTrajectorySample sample) {
        Objects.requireNonNull(sample, "sample");
        lastDiscontinuity = DiscontinuityReason.NONE;
        final ClientWorldTrajectorySample previous = samples.peekLast();
        if (previous != null) {
            final DiscontinuityReason discontinuity = discontinuity(previous, sample);
            if (discontinuity != DiscontinuityReason.NONE) {
                if (discontinuity == DiscontinuityReason.CONNECTION_CHANGE && restoredHistoryBoundary) {
                    // Keep old observations for reconnect matching, but make the boundary explicit.
                    lastDiscontinuity = discontinuity;
                    restoredHistoryBoundary = false;
                } else {
                    samples.clear();
                    clearAcknowledgement();
                    lastDiscontinuity = discontinuity;
                }
            }
        }
        if (sample.serverAckTimeMs() != ClientWorldTrajectorySample.NO_SERVER_ACK
            && sample.serverAckTimeMs() >= lastServerAckAckFloor()) {
            lastServerAckTimeMs = Math.max(lastServerAckTimeMs, sample.serverAckTimeMs());
            lastServerAckSequence = Math.max(lastServerAckSequence, sample.sequence());
        }
        samples.addLast(sample);
        while (samples.size() > capacity) {
            samples.removeFirst();
        }
        return new AppendResult(sample, lastDiscontinuity);
    }

    /** Explicit boundary from a teleport, dimension change, reconnect, or server correction. */
    public void reset(final DiscontinuityReason reason) {
        samples.clear();
        clearAcknowledgement();
        lastDiscontinuity = Objects.requireNonNull(reason, "reason");
        restoredHistoryBoundary = false;
    }

    /** Rehydrates a bounded checkpoint while retaining the observed samples as history. */
    public void restoreHistoricalSamples(final List<ClientWorldTrajectorySample> persisted) {
        samples.clear();
        clearAcknowledgement();
        restoredHistoryBoundary = false;
        if (persisted == null) {
            lastDiscontinuity = DiscontinuityReason.CONNECTION_CHANGE;
            return;
        }
        ClientWorldTrajectorySample previous = null;
        for (final ClientWorldTrajectorySample sample : persisted) {
            if (sample == null || sample.dimensionId() == null || sample.dimensionId().isBlank()
                || sample.clientTimeMs() < 0L || sample.clientTick() < 0L || sample.sequence() < 0L
                || sample.serverAckTimeMs() < ClientWorldTrajectorySample.NO_SERVER_ACK
                || sample.connectionGeneration() < 0L) {
                continue;
            }
            if (previous != null && (sample.clientTimeMs() <= previous.clientTimeMs()
                || sample.connectionGeneration() == previous.connectionGeneration()
                    && sample.sequence() <= previous.sequence())) {
                continue;
            }
            samples.addLast(sample);
            if (sample.serverAckTimeMs() != ClientWorldTrajectorySample.NO_SERVER_ACK
                && sample.serverAckTimeMs() >= lastServerAckAckFloor()) {
                lastServerAckTimeMs = Math.max(lastServerAckTimeMs, sample.serverAckTimeMs());
                lastServerAckSequence = Math.max(lastServerAckSequence, sample.sequence());
            }
            previous = sample;
        }
        while (samples.size() > capacity) {
            samples.removeFirst();
        }
        restoredHistoryBoundary = !samples.isEmpty();
        lastDiscontinuity = DiscontinuityReason.CONNECTION_CHANGE;
    }

    /** Returns the age of the newest server acknowledgement, or {@code Long.MAX_VALUE} if absent. */
    public long acknowledgementAgeMs(final long nowMs) {
        if (lastServerAckTimeMs == ClientWorldTrajectorySample.NO_SERVER_ACK) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, nowMs - lastServerAckTimeMs);
    }

    /**
     * Finds the nearest horizontal distance to the observed history and a bounded forward
     * projection. The projection is deliberately finite so a blocked connection cannot create an
     * unlimited world corridor.
     */
    public double nearestDistance(final ClientWorldPosition target, final long nowMs, final long horizonMs) {
        Objects.requireNonNull(target, "target");
        if (samples.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }
        double nearest = Double.POSITIVE_INFINITY;
        ClientWorldTrajectorySample previous = null;
        for (final ClientWorldTrajectorySample sample : samples) {
            if (previous != null && sameSegment(previous, sample)) {
                nearest = Math.min(nearest, distanceToSegment(
                    target.x(), target.z(), previous.x(), previous.z(), sample.x(), sample.z()
                ));
            } else {
                nearest = Math.min(nearest, sample.horizontalDistanceTo(target));
            }
            previous = sample;
        }
        final ClientWorldTrajectorySample latest = samples.peekLast();
        final long projectionMs = projectionMs(nowMs, horizonMs);
        if (projectionMs > 0L) {
            final PredictedVelocity velocity = predictedVelocity();
            final double endX = latest.x() + velocity.xPerSecond() * projectionMs / 1_000.0D;
            final double endZ = latest.z() + velocity.zPerSecond() * projectionMs / 1_000.0D;
            nearest = Math.min(nearest, distanceToSegment(
                target.x(), target.z(), latest.x(), latest.z(), endX, endZ
            ));
        }
        return nearest;
    }

    /**
     * Computes a time-directed corridor from the candidate's last observed point. Historical
     * samples are used only to fit the velocity at that endpoint; an old crossing segment is
     * never treated as evidence that the current world was reached.
     */
    public CausalCorridor causalCorridor(
        final ClientWorldPosition target,
        final String dimensionId,
        final long targetTimeMs,
        final long horizonMs
    ) {
        Objects.requireNonNull(target, "target");
        final ClientWorldTrajectorySample endpoint = latest();
        if (endpoint == null || dimensionId == null || !dimensionId.equals(endpoint.dimensionId())) {
            return new CausalCorridor(
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0.0D,
                Double.POSITIVE_INFINITY, 0L
            );
        }
        final long elapsedMs = Math.max(0L, targetTimeMs - endpoint.clientTimeMs());
        final long boundedHorizon = Math.max(0L, Math.min(DEFAULT_MAX_PREDICTION_MS, horizonMs));
        final long predictionMs = Math.min(elapsedMs, boundedHorizon);
        final PredictedVelocity velocity = predictedVelocity();
        final double predictedLength = Math.hypot(velocity.xPerSecond(), velocity.zPerSecond())
            * predictionMs / 1_000.0D;
        final double dx = target.x() - endpoint.x();
        final double dy = target.y() - endpoint.y();
        final double dz = target.z() - endpoint.z();
        final double endpointDistance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (predictedLength <= 0.0D) {
            return new CausalCorridor(0.0D, endpointDistance, 0.0D, endpointDistance, elapsedMs);
        }
        final double ux = velocity.xPerSecond() / Math.hypot(velocity.xPerSecond(), velocity.zPerSecond());
        final double uz = velocity.zPerSecond() / Math.hypot(velocity.xPerSecond(), velocity.zPerSecond());
        final double projected = dx * ux + dz * uz;
        final double along = Math.max(0.0D, Math.min(predictedLength, projected));
        final double lateralX = dx - ux * along;
        final double lateralZ = dz - uz * along;
        final double lateral = Math.sqrt(lateralX * lateralX + dy * dy + lateralZ * lateralZ);
        return new CausalCorridor(along, lateral, predictedLength, endpointDistance, elapsedMs);
    }

    /**
     * Finds the closest approach between two locally observed paths. Historical segments remain
     * valid after the short prediction window; only each path's forward projection is time-bound.
     */
    public double nearestApproachDistance(
        final ClientWorldTrajectory other,
        final long nowMs,
        final long horizonMs
    ) {
        Objects.requireNonNull(other, "other");
        if (samples.isEmpty() || other.samples.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }
        if (!latest().dimensionId().equals(other.latest().dimensionId())) {
            return Double.POSITIVE_INFINITY;
        }
        final List<Segment> first = segments(nowMs, horizonMs);
        final List<Segment> second = other.segments(nowMs, horizonMs);
        double nearest = Double.POSITIVE_INFINITY;
        for (final Segment left : first) {
            for (final Segment right : second) {
                nearest = Math.min(nearest, segmentDistance(left, right));
            }
        }
        return nearest;
    }

    /** Uncertainty radius grows with missing acknowledgements but remains bounded. */
    public double uncertaintyRadius(final long nowMs, final double baseRadius) {
        final double boundedBase = Math.max(0.0D, baseRadius);
        final long age = acknowledgementAgeMs(nowMs);
        final ClientWorldTrajectorySample latest = latest();
        final long locallyObservedAge = latest == null
            ? 0L : Math.max(0L, nowMs - latest.clientTimeMs());
        final long evidenceAge = age == Long.MAX_VALUE ? locallyObservedAge : age;
        final long boundedAge = Math.min(DEFAULT_MAX_PREDICTION_MS, evidenceAge);
        return boundedBase + Math.min(256.0D, boundedAge / 1_000.0D * 32.0D);
    }

    private long lastServerAckAckFloor() {
        return lastServerAckTimeMs == ClientWorldTrajectorySample.NO_SERVER_ACK
            ? Long.MIN_VALUE : lastServerAckTimeMs;
    }

    private void clearAcknowledgement() {
        lastServerAckTimeMs = ClientWorldTrajectorySample.NO_SERVER_ACK;
        lastServerAckSequence = -1L;
    }

    /** Fits recent displacement and blends it with the locally reported velocity. */
    private PredictedVelocity predictedVelocity() {
        final ClientWorldTrajectorySample latest = samples.peekLast();
        ClientWorldTrajectorySample start = latest;
        final java.util.Iterator<ClientWorldTrajectorySample> reverse = samples.descendingIterator();
        while (reverse.hasNext()) {
            final ClientWorldTrajectorySample sample = reverse.next();
            if (!sameSegment(sample, latest)) {
                break;
            }
            if (latest.clientTimeMs() - sample.clientTimeMs() <= VELOCITY_FIT_WINDOW_MS) {
                start = sample;
            } else {
                break;
            }
        }
        double fittedX = 0.0D;
        double fittedZ = 0.0D;
        final double elapsedSeconds = (latest.clientTimeMs() - start.clientTimeMs()) / 1_000.0D;
        if (elapsedSeconds > 0.0D) {
            fittedX = (latest.x() - start.x()) / elapsedSeconds;
            fittedZ = (latest.z() - start.z()) / elapsedSeconds;
        }
        final double fittedSpeed = Math.hypot(fittedX, fittedZ);
        final double reportedSpeed = latest.horizontalSpeed();
        if (fittedSpeed >= MIN_PREDICTION_SPEED && reportedSpeed >= MIN_PREDICTION_SPEED) {
            final double alignment = (fittedX * latest.horizontalVelocityX()
                + fittedZ * latest.horizontalVelocityZ()) / (fittedSpeed * reportedSpeed);
            if (alignment > 0.0D) {
                return orientWithHeading(new PredictedVelocity(
                    fittedX * 0.65D + latest.horizontalVelocityX() * 0.35D,
                    fittedZ * 0.65D + latest.horizontalVelocityZ() * 0.35D
                ), latest.yawDegrees());
            }
            // A sharp turn makes the older displacement fit stale. Keep the observed path as
            // history, but start the forward corridor in the latest locally reported direction.
            return orientWithHeading(new PredictedVelocity(
                latest.horizontalVelocityX(), latest.horizontalVelocityZ()
            ), latest.yawDegrees());
        }
        if (reportedSpeed >= MIN_PREDICTION_SPEED) {
            return orientWithHeading(new PredictedVelocity(
                latest.horizontalVelocityX(), latest.horizontalVelocityZ()
            ), latest.yawDegrees());
        }
        if (fittedSpeed >= MIN_PREDICTION_SPEED) {
            return orientWithHeading(new PredictedVelocity(fittedX, fittedZ), latest.yawDegrees());
        }
        return new PredictedVelocity(0.0D, 0.0D);
    }

    private static PredictedVelocity orientWithHeading(
        final PredictedVelocity velocity,
        final double yawDegrees
    ) {
        final double speed = Math.hypot(velocity.xPerSecond(), velocity.zPerSecond());
        if (speed < MIN_PREDICTION_SPEED) {
            return velocity;
        }
        // Minecraft yaw 0 points toward +Z and positive rotation points toward -X.
        final double yawRadians = Math.toRadians(yawDegrees);
        final double headingX = -Math.sin(yawRadians) * speed;
        final double headingZ = Math.cos(yawRadians) * speed;
        final double alignment = (velocity.xPerSecond() * headingX
            + velocity.zPerSecond() * headingZ) / (speed * speed);
        if (alignment < 0.5D) {
            return velocity;
        }
        return new PredictedVelocity(
            velocity.xPerSecond() * 0.85D + headingX * 0.15D,
            velocity.zPerSecond() * 0.85D + headingZ * 0.15D
        );
    }

    private static DiscontinuityReason discontinuity(
        final ClientWorldTrajectorySample previous,
        final ClientWorldTrajectorySample current
    ) {
        if (previous.connectionGeneration() != current.connectionGeneration()) {
            return DiscontinuityReason.CONNECTION_CHANGE;
        }
        if (!previous.dimensionId().equals(current.dimensionId())) {
            return DiscontinuityReason.DIMENSION_CHANGE;
        }
        if (current.clientTimeMs() - previous.clientTimeMs() < MIN_SAMPLE_INTERVAL_MS
            || current.sequence() <= previous.sequence()) {
            return DiscontinuityReason.OUT_OF_ORDER;
        }
        final double elapsedSeconds = (current.clientTimeMs() - previous.clientTimeMs()) / 1_000.0D;
        final double displacement = Math.hypot(current.x() - previous.x(), current.z() - previous.z());
        final double expected = Math.max(
            MIN_REASONABLE_DISPLACEMENT,
            Math.max(previous.horizontalSpeed(), current.horizontalSpeed())
                * elapsedSeconds * DISPLACEMENT_SAFETY_FACTOR + MIN_REASONABLE_DISPLACEMENT
        );
        return displacement > expected ? DiscontinuityReason.POSITION_JUMP : DiscontinuityReason.NONE;
    }

    private static double distanceToSegment(
        final double pointX,
        final double pointZ,
        final double startX,
        final double startZ,
        final double endX,
        final double endZ
    ) {
        final double dx = endX - startX;
        final double dz = endZ - startZ;
        final double lengthSquared = dx * dx + dz * dz;
        if (lengthSquared <= 0.0D) {
            return Math.hypot(pointX - startX, pointZ - startZ);
        }
        final double projection = Math.max(0.0D, Math.min(1.0D,
            ((pointX - startX) * dx + (pointZ - startZ) * dz) / lengthSquared));
        return Math.hypot(pointX - (startX + projection * dx), pointZ - (startZ + projection * dz));
    }

    private List<Segment> segments(final long nowMs, final long horizonMs) {
        final java.util.ArrayList<Segment> result = new java.util.ArrayList<>();
        ClientWorldTrajectorySample previous = null;
        for (final ClientWorldTrajectorySample sample : samples) {
            if (previous == null || !sameSegment(previous, sample)) {
                result.add(new Segment(sample.x(), sample.z(), sample.x(), sample.z()));
            } else {
                result.add(new Segment(previous.x(), previous.z(), sample.x(), sample.z()));
            }
            previous = sample;
        }
        final ClientWorldTrajectorySample latest = samples.peekLast();
        final long age = projectionMs(nowMs, horizonMs);
        if (age > 0L) {
            final PredictedVelocity velocity = predictedVelocity();
            result.add(new Segment(
                latest.x(), latest.z(),
                latest.x() + velocity.xPerSecond() * age / 1_000.0D,
                latest.z() + velocity.zPerSecond() * age / 1_000.0D
            ));
        }
        return result;
    }

    private static boolean sameSegment(
        final ClientWorldTrajectorySample previous,
        final ClientWorldTrajectorySample current
    ) {
        return previous.dimensionId().equals(current.dimensionId())
            && previous.connectionGeneration() == current.connectionGeneration();
    }

    private static double segmentDistance(final Segment first, final Segment second) {
        if (segmentsIntersect(first, second)) {
            return 0.0D;
        }
        return Math.min(
            Math.min(
                distanceToSegment(first.startX(), first.startZ(), second.startX(), second.startZ(),
                    second.endX(), second.endZ()),
                distanceToSegment(first.endX(), first.endZ(), second.startX(), second.startZ(),
                    second.endX(), second.endZ())
            ),
            Math.min(
                distanceToSegment(second.startX(), second.startZ(), first.startX(), first.startZ(),
                    first.endX(), first.endZ()),
                distanceToSegment(second.endX(), second.endZ(), first.startX(), first.startZ(),
                    first.endX(), first.endZ())
            )
        );
    }

    private static boolean segmentsIntersect(final Segment first, final Segment second) {
        final double a = cross(first.startX(), first.startZ(), first.endX(), first.endZ(),
            second.startX(), second.startZ());
        final double b = cross(first.startX(), first.startZ(), first.endX(), first.endZ(),
            second.endX(), second.endZ());
        final double c = cross(second.startX(), second.startZ(), second.endX(), second.endZ(),
            first.startX(), first.startZ());
        final double d = cross(second.startX(), second.startZ(), second.endX(), second.endZ(),
            first.endX(), first.endZ());
        return a * b <= 0.0D && c * d <= 0.0D
            && Math.max(Math.min(first.startX(), first.endX()), Math.min(second.startX(), second.endX()))
                <= Math.min(Math.max(first.startX(), first.endX()), Math.max(second.startX(), second.endX()))
            && Math.max(Math.min(first.startZ(), first.endZ()), Math.min(second.startZ(), second.endZ()))
                <= Math.min(Math.max(first.startZ(), first.endZ()), Math.max(second.startZ(), second.endZ()));
    }

    private long projectionMs(final long nowMs, final long horizonMs) {
        final ClientWorldTrajectorySample latest = samples.peekLast();
        if (latest == null) {
            return 0L;
        }
        final long boundedHorizon = Math.max(0L, Math.min(DEFAULT_MAX_PREDICTION_MS, horizonMs));
        final long elapsed = Math.max(0L, nowMs - latest.clientTimeMs());
        if (elapsed <= 0L || elapsed > boundedHorizon) {
            return 0L;
        }
        return elapsed;
    }

    private static double cross(
        final double startX,
        final double startZ,
        final double endX,
        final double endZ,
        final double pointX,
        final double pointZ
    ) {
        return (endX - startX) * (pointZ - startZ) - (endZ - startZ) * (pointX - startX);
    }

    public record AppendResult(ClientWorldTrajectorySample sample, DiscontinuityReason discontinuity) { }

    private record PredictedVelocity(double xPerSecond, double zPerSecond) { }

    private record Segment(double startX, double startZ, double endX, double endZ) { }

    public enum DiscontinuityReason {
        NONE,
        CONNECTION_CHANGE,
        DIMENSION_CHANGE,
        POSITION_JUMP,
        SERVER_CORRECTION,
        OUT_OF_ORDER,
        EXPLICIT_RESET
    }
}

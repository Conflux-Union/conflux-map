package cn.net.rms.confluxmap.core.net.shared;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minecraft-free client state machine for the shared-waypoint protocol.
 *
 * <p>The published {@link View} is immutable and swapped atomically. A delta is applied only to
 * a complete snapshot and only when it advances the global revision by exactly one; a gap clears
 * the potentially stale catalog and asks the transport for another snapshot.
 */
public final class SharedWaypointClientState {
    public static final int HANDSHAKE_TIMEOUT_TICKS = 100;
    public static final int SUBSCRIPTION_RETRY_TICKS = 40;
    private static final double MAX_ABS_COORDINATE = 30_000_000d;

    public enum State {
        UNKNOWN,
        HANDSHAKE,
        UNSUPPORTED,
        SUPPORTED_DISABLED,
        ENABLED
    }

    /** Side effects the Fabric adapter must perform after a state transition. */
    public record Action(boolean subscribe, boolean notifyDisabled, boolean protocolRejected) {
        private static final Action NONE = new Action(false, false, false);
        private static final Action SUBSCRIBE = new Action(true, false, false);
        private static final Action REJECTED = new Action(false, false, true);
        private static final Action RESYNC = new Action(true, false, false);
    }

    /** One lock-free, internally consistent view for renderers and screens. */
    public record View(
        State state,
        List<SharedWaypoint> list,
        boolean operator,
        long revision,
        String worldId,
        int maxWorld,
        int maxPlayer,
        boolean synchronizedSnapshot
    ) {
        public View {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(list, "list");
            Objects.requireNonNull(worldId, "worldId");
            list = List.copyOf(list);
        }

        private static View empty(final State state) {
            return new View(state, List.of(), false, -1L, "", 0, 0, false);
        }
    }

    private final AtomicReference<View> view = new AtomicReference<>(View.empty(State.UNKNOWN));
    private int handshakeTicks;
    private int subscriptionRetryTicks;
    private boolean disabledNoticeShown;

    /** Starts one connection lifecycle and returns whether the transport should send HELLO. */
    public synchronized boolean beginConnection(final boolean channelAvailable) {
        handshakeTicks = 0;
        subscriptionRetryTicks = 0;
        disabledNoticeShown = false;
        view.set(View.empty(channelAvailable ? State.HANDSHAKE : State.UNSUPPORTED));
        return channelAvailable;
    }

    /** Forgets every server-owned value on disconnect. */
    public synchronized void reset() {
        handshakeTicks = 0;
        subscriptionRetryTicks = 0;
        disabledNoticeShown = false;
        view.set(View.empty(State.UNKNOWN));
    }

    /** Converts an outstanding handshake to a safe unsupported fallback after five seconds. */
    public synchronized boolean tick() {
        if (view.get().state() != State.HANDSHAKE) {
            return false;
        }
        handshakeTicks++;
        if (handshakeTicks < HANDSHAKE_TIMEOUT_TICKS) {
            return false;
        }
        view.set(View.empty(State.UNSUPPORTED));
        return true;
    }

    /** Retries a lost or throttled subscription every two seconds while a snapshot is missing. */
    public synchronized boolean tickSubscriptionRetry() {
        final View current = view.get();
        if (current.state() != State.ENABLED || current.synchronizedSnapshot()) {
            subscriptionRetryTicks = 0;
            return false;
        }
        subscriptionRetryTicks++;
        if (subscriptionRetryTicks < SUBSCRIPTION_RETRY_TICKS) {
            return false;
        }
        subscriptionRetryTicks = 0;
        return true;
    }

    /** Handles a capability response and decides whether a full subscription is required. */
    public synchronized Action onStatus(final StatusS2C status) {
        if (status == null) {
            return rejectProtocol();
        }
        final View current = view.get();
        if (current.state() == State.UNKNOWN || current.state() == State.UNSUPPORTED) {
            return Action.NONE;
        }
        if (!status.supported() || status.major() != SharedWaypointProto.PROTO_MAJOR) {
            subscriptionRetryTicks = 0;
            view.set(View.empty(State.UNSUPPORTED));
            return Action.NONE;
        }
        if (!validStatus(status)) {
            return rejectProtocol();
        }

        handshakeTicks = 0;
        if (!status.enabled()) {
            subscriptionRetryTicks = 0;
            final boolean notify = !disabledNoticeShown;
            disabledNoticeShown = true;
            view.set(new View(
                State.SUPPORTED_DISABLED,
                List.of(),
                status.operator(),
                status.revision(),
                status.worldId(),
                status.maxWorld(),
                status.maxPlayer(),
                false
            ));
            return new Action(false, notify, false);
        }

        if (current.state() == State.ENABLED
            && current.worldId().equals(status.worldId())
            && current.synchronizedSnapshot()
            && current.revision() == status.revision()) {
            subscriptionRetryTicks = 0;
            view.set(new View(
                State.ENABLED,
                current.list(),
                status.operator(),
                current.revision(),
                status.worldId(),
                status.maxWorld(),
                status.maxPlayer(),
                true
            ));
            return Action.NONE;
        }

        view.set(new View(
            State.ENABLED,
            List.of(),
            status.operator(),
            status.revision(),
            status.worldId(),
            status.maxWorld(),
            status.maxPlayer(),
            false
        ));
        subscriptionRetryTicks = 0;
        return Action.SUBSCRIBE;
    }

    /** Atomically replaces the catalog with one complete, validated server snapshot. */
    public synchronized Action onSnapshot(final SnapshotS2C snapshot) {
        final View current = view.get();
        if (current.state() != State.ENABLED || snapshot == null) {
            return Action.NONE;
        }
        if (snapshot.revision() < 0L
            || snapshot.list().size() > current.maxWorld()
            || snapshot.revision() < current.revision()) {
            return rejectProtocol();
        }

        final List<SharedWaypoint> replacement = new ArrayList<>(snapshot.list().size());
        final Set<UUID> ids = new HashSet<>();
        for (final SharedWaypoint waypoint : snapshot.list()) {
            if (!validWaypoint(waypoint, snapshot.revision()) || !ids.add(waypoint.id())) {
                return rejectProtocol();
            }
            replacement.add(waypoint);
        }
        view.set(new View(
            State.ENABLED,
            replacement,
            snapshot.operator(),
            snapshot.revision(),
            current.worldId(),
            current.maxWorld(),
            current.maxPlayer(),
            true
        ));
        subscriptionRetryTicks = 0;
        return Action.NONE;
    }

    /** Applies one create/lock delta, ignores duplicates, and resubscribes on a gap. */
    public synchronized Action onUpsert(final UpsertS2C upsert) {
        final View current = view.get();
        if (current.state() != State.ENABLED || upsert == null) {
            return Action.NONE;
        }
        if (!current.synchronizedSnapshot()) {
            return Action.NONE;
        }
        if (upsert.revision() <= current.revision()) {
            return Action.NONE;
        }
        if (!isNextRevision(current.revision(), upsert.revision())) {
            invalidateSnapshot(current);
            return Action.RESYNC;
        }
        if (!validWaypoint(upsert.waypoint(), upsert.revision())
            || upsert.waypoint().revision() != upsert.revision()) {
            return rejectProtocol();
        }

        final List<SharedWaypoint> replacement = new ArrayList<>(current.list());
        int existingIndex = -1;
        for (int i = 0; i < replacement.size(); i++) {
            if (replacement.get(i).id().equals(upsert.waypoint().id())) {
                existingIndex = i;
                break;
            }
        }
        if (existingIndex >= 0) {
            replacement.set(existingIndex, upsert.waypoint());
        } else {
            if (replacement.size() >= current.maxWorld()) {
                return rejectProtocol();
            }
            replacement.add(upsert.waypoint());
        }
        view.set(new View(
            State.ENABLED,
            replacement,
            current.operator(),
            upsert.revision(),
            current.worldId(),
            current.maxWorld(),
            current.maxPlayer(),
            true
        ));
        return Action.NONE;
    }

    /** Applies one delete delta, ignores duplicates, and resubscribes on a gap. */
    public synchronized Action onRemove(final RemoveS2C remove) {
        final View current = view.get();
        if (current.state() != State.ENABLED || remove == null) {
            return Action.NONE;
        }
        if (!current.synchronizedSnapshot()) {
            return Action.NONE;
        }
        if (remove.revision() <= current.revision()) {
            return Action.NONE;
        }
        if (!isNextRevision(current.revision(), remove.revision())) {
            invalidateSnapshot(current);
            return Action.RESYNC;
        }

        final List<SharedWaypoint> replacement = new ArrayList<>(current.list());
        replacement.removeIf(waypoint -> waypoint.id().equals(remove.id()));
        view.set(new View(
            State.ENABLED,
            replacement,
            current.operator(),
            remove.revision(),
            current.worldId(),
            current.maxWorld(),
            current.maxPlayer(),
            true
        ));
        return Action.NONE;
    }

    /** Invalidates a stale optimistic revision and requests a fresh authoritative snapshot. */
    public synchronized Action onRevisionConflict() {
        final View current = view.get();
        if (current.state() != State.ENABLED) {
            return Action.NONE;
        }
        invalidateSnapshot(current);
        return Action.RESYNC;
    }

    /** Drops all server data after a malformed or semantically invalid payload. */
    public synchronized void onProtocolFailure() {
        rejectProtocol();
    }

    public View view() {
        return view.get();
    }

    public boolean canMutate() {
        final View current = view.get();
        return current.state() == State.ENABLED && current.synchronizedSnapshot();
    }

    private Action rejectProtocol() {
        subscriptionRetryTicks = 0;
        view.set(View.empty(State.UNSUPPORTED));
        return Action.REJECTED;
    }

    private void invalidateSnapshot(final View current) {
        subscriptionRetryTicks = 0;
        view.set(new View(
            State.ENABLED,
            List.of(),
            current.operator(),
            current.revision(),
            current.worldId(),
            current.maxWorld(),
            current.maxPlayer(),
            false
        ));
    }

    private static boolean validStatus(final StatusS2C status) {
        return status.minor() >= 0
            && status.revision() >= 0L
            && status.maxWorld() >= 1
            && status.maxWorld() <= SharedWaypointProto.MAX_SNAPSHOT_WAYPOINTS
            && status.maxPlayer() >= 1
            && status.maxPlayer() <= status.maxWorld()
            && validText(status.worldId(), false);
    }

    private static boolean validWaypoint(final SharedWaypoint waypoint, final long snapshotRevision) {
        if (waypoint == null
            || waypoint.revision() < 1L
            || waypoint.revision() > snapshotRevision
            || waypoint.createdAtEpochMs() < 0L
            || (waypoint.colorArgb() & 0xFF000000) != 0xFF000000
            || !validText(waypoint.name(), false)
            || !validText(waypoint.publisherName(), false)
            || !validDimension(waypoint.dimensionId())) {
            return false;
        }
        return validCoordinate(waypoint.x())
            && validCoordinate(waypoint.y())
            && validCoordinate(waypoint.z());
    }

    private static boolean validDimension(final DimensionId dimensionId) {
        if (dimensionId == null
            || !validText(dimensionId.namespace(), false)
            || !validText(dimensionId.path(), false)) {
            return false;
        }
        return validResourcePart(dimensionId.namespace(), false)
            && validResourcePart(dimensionId.path(), true);
    }

    private static boolean validResourcePart(final String value, final boolean path) {
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '_' || c == '-' || c == '.' || (path && c == '/')) {
                continue;
            }
            return false;
        }
        return true;
    }

    private static boolean validCoordinate(final double value) {
        return Double.isFinite(value) && Math.abs(value) <= MAX_ABS_COORDINATE;
    }

    private static boolean validText(final String value, final boolean allowBlank) {
        if (value == null || value.length() > SharedWaypointProto.MAX_UTF8_BYTES) {
            return false;
        }
        if (!allowBlank && value.isBlank()) {
            return false;
        }
        int utf8Bytes = 0;
        for (int offset = 0; offset < value.length();) {
            final char first = value.charAt(offset);
            final int codePoint;
            if (Character.isHighSurrogate(first)) {
                if (offset + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(offset + 1))) {
                    return false;
                }
                codePoint = Character.toCodePoint(first, value.charAt(offset + 1));
                offset += 2;
            } else if (Character.isLowSurrogate(first)) {
                return false;
            } else {
                codePoint = first;
                offset++;
            }
            if (Character.isISOControl(codePoint)
                || codePoint == '\u00a7'
                || Character.getType(codePoint) == Character.FORMAT) {
                return false;
            }
            utf8Bytes += codePoint <= 0x7F ? 1 : codePoint <= 0x7FF ? 2 : codePoint <= 0xFFFF ? 3 : 4;
            if (utf8Bytes > SharedWaypointProto.MAX_UTF8_BYTES) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNextRevision(final long current, final long candidate) {
        return current != Long.MAX_VALUE && candidate == current + 1L;
    }
}

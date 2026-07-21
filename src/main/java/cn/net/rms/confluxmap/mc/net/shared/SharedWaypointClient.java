package cn.net.rms.confluxmap.mc.net.shared;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.core.net.shared.CreateC2S;
import cn.net.rms.confluxmap.core.net.shared.DeleteC2S;
import cn.net.rms.confluxmap.core.net.shared.HelloC2S;
import cn.net.rms.confluxmap.core.net.shared.LockC2S;
import cn.net.rms.confluxmap.core.net.shared.RemoveS2C;
import cn.net.rms.confluxmap.core.net.shared.ResultS2C;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointAvailability;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointClientState;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointCodec;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointMessage;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointProto;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointProtocolException;
import cn.net.rms.confluxmap.core.net.shared.SnapshotS2C;
import cn.net.rms.confluxmap.core.net.shared.StatusS2C;
import cn.net.rms.confluxmap.core.net.shared.SubscribeC2S;
import cn.net.rms.confluxmap.core.net.shared.UpsertS2C;
import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import cn.net.rms.confluxmap.core.shared.SharedWaypointLocationKey;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import io.netty.buffer.Unpooled;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Identifier;

/**
 * Client adapter for the independent {@code confluxmap:waypoints_v1} channel.
 *
 * <p>The adapter never puts public points in the local waypoint store. It publishes immutable
 * server snapshots to consumers and owns connection capability detection, handshake timeout,
 * revision recovery, operation correlation, and disconnect cleanup.
 */
public final class SharedWaypointClient {
    public static final Identifier CHANNEL = new Identifier(SharedWaypointProto.CHANNEL_ID);
    private static final int MAX_PENDING_OPERATIONS = 128;

    public enum OperationKind {
        CREATE,
        DELETE,
        LOCK,
        UNLOCK
    }

    public record OperationResult(
        UUID operationId,
        OperationKind kind,
        boolean applied,
        int statusCode,
        int errorCode
    ) {
        public OperationResult {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(kind, "kind");
        }
    }

    private record PendingOperation(OperationKind kind, SharedWaypointLocationKey createLocation) {
        private PendingOperation {
            Objects.requireNonNull(kind, "kind");
        }
    }

    public interface Listener {
        default void onStateChanged(final SharedWaypointClientState.State state) {
        }

        default void onWaypointsChanged(final List<SharedWaypoint> waypoints, final long revision) {
        }

        default void onOperationResult(final OperationResult result) {
        }
    }

    private final MinecraftClient client;
    private final SharedWaypointClientState stateMachine = new SharedWaypointClientState();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Map<UUID, PendingOperation> pendingOperations = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<UUID, PendingOperation> eldest) {
            return size() > MAX_PENDING_OPERATIONS;
        }
    };

    public SharedWaypointClient(final MinecraftClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public void register() {
        ClientPlayNetworking.registerGlobalReceiver(CHANNEL, this::onReceive);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, currentClient) -> onJoin());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, currentClient) -> onDisconnect());
        ClientTickEvents.END_CLIENT_TICK.register(currentClient -> onTick());
    }

    public SharedWaypointClientState.State state() {
        return stateMachine.view().state();
    }

    /** UI visibility/readiness derived from one internally consistent state snapshot. */
    public SharedWaypointAvailability availability() {
        final SharedWaypointClientState.View view = stateMachine.view();
        return SharedWaypointAvailability.from(view.state(), view.synchronizedSnapshot());
    }

    /** Immutable, atomically published shared-waypoint catalog. */
    public List<SharedWaypoint> list() {
        return stateMachine.view().list();
    }

    public boolean isOperator() {
        return stateMachine.view().operator();
    }

    public long revision() {
        return stateMachine.view().revision();
    }

    public int maxWorld() {
        return stateMachine.view().maxWorld();
    }

    public int maxPlayer() {
        return stateMachine.view().maxPlayer();
    }

    public String worldId() {
        return stateMachine.view().worldId();
    }

    public boolean isSynchronized() {
        return stateMachine.view().synchronizedSnapshot();
    }

    public Optional<SharedWaypoint> find(final UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return list().stream().filter(waypoint -> waypoint.id().equals(id)).findFirst();
    }

    public boolean isLocationShared(final Waypoint waypoint) {
        if (waypoint == null) {
            return false;
        }
        final SharedWaypointLocationKey location = SharedWaypointLocationKey.from(waypoint);
        return list().stream().anyMatch(shared -> SharedWaypointLocationKey.from(shared).equals(location));
    }

    public boolean isCreatePending(final Waypoint waypoint) {
        if (waypoint == null) {
            return false;
        }
        final SharedWaypointLocationKey location = SharedWaypointLocationKey.from(waypoint);
        return pendingOperations.values().stream()
            .anyMatch(pending -> location.equals(pending.createLocation()));
    }

    /** Publishes a copy of a local waypoint using the current authoritative revision. */
    public boolean create(final Waypoint waypoint) {
        if (waypoint == null || !stateMachine.canMutate()
            || isLocationShared(waypoint) || isCreatePending(waypoint)) {
            return false;
        }
        final UUID operationId = UUID.randomUUID();
        final SharedWaypointLocationKey location = SharedWaypointLocationKey.from(waypoint);
        return sendMutation(
            operationId,
            new PendingOperation(OperationKind.CREATE, location),
            new CreateC2S(
                operationId,
                revision(),
                waypoint.name,
                waypoint.dimensionId,
                waypoint.x,
                waypoint.y,
                waypoint.z,
                waypoint.colorArgb,
                waypoint.type
            )
        );
    }

    public boolean delete(final SharedWaypoint waypoint) {
        if (waypoint == null || !stateMachine.canMutate() || (waypoint.locked() && !isOperator())) {
            return false;
        }
        final UUID operationId = UUID.randomUUID();
        return sendMutation(
            operationId,
            new PendingOperation(OperationKind.DELETE, null),
            deleteMessage(operationId, waypoint)
        );
    }

    public boolean setLocked(final SharedWaypoint waypoint, final boolean locked) {
        if (waypoint == null || !stateMachine.canMutate() || !isOperator()) {
            return false;
        }
        final UUID operationId = UUID.randomUUID();
        return sendMutation(
            operationId,
            new PendingOperation(locked ? OperationKind.LOCK : OperationKind.UNLOCK, null),
            lockMessage(operationId, waypoint, locked)
        );
    }

    public void addListener(final Listener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(final Listener listener) {
        listeners.remove(listener);
    }

    static DeleteC2S deleteMessage(final UUID operationId, final SharedWaypoint waypoint) {
        return new DeleteC2S(operationId, waypoint.id(), waypoint.revision());
    }

    static LockC2S lockMessage(
        final UUID operationId,
        final SharedWaypoint waypoint,
        final boolean locked
    ) {
        return new LockC2S(operationId, waypoint.id(), waypoint.revision(), locked);
    }

    private void onJoin() {
        final SharedWaypointClientState.View before = stateMachine.view();
        pendingOperations.clear();
        final boolean channelAvailable;
        try {
            channelAvailable = ClientPlayNetworking.canSend(CHANNEL);
        } catch (final RuntimeException e) {
            ConfluxMapMod.LOGGER.debug("shared-waypoint: channel capability check failed", e);
            stateMachine.beginConnection(false);
            notifyChanges(before, stateMachine.view());
            return;
        }

        final boolean sendHello = stateMachine.beginConnection(channelAvailable);
        notifyChanges(before, stateMachine.view());
        if (!sendHello) {
            ConfluxMapMod.LOGGER.info("shared-waypoint: server does not advertise {}", CHANNEL);
            return;
        }
        if (!send(new HelloC2S(SharedWaypointProto.PROTO_MAJOR, SharedWaypointProto.PROTO_MINOR))) {
            failTransport("HELLO send failed");
        }
    }

    private void onDisconnect() {
        final SharedWaypointClientState.View before = stateMachine.view();
        pendingOperations.clear();
        stateMachine.reset();
        notifyChanges(before, stateMachine.view());
    }

    private void onTick() {
        final SharedWaypointClientState.View before = stateMachine.view();
        if (stateMachine.tick()) {
            ConfluxMapMod.LOGGER.info(
                "shared-waypoint: handshake timed out after {} ticks",
                SharedWaypointClientState.HANDSHAKE_TIMEOUT_TICKS
            );
            notifyChanges(before, stateMachine.view());
        }
        if (stateMachine.tickSubscriptionRetry() && !send(new SubscribeC2S())) {
            failTransport("SUBSCRIBE retry send failed");
        }
    }

    private void onReceive(
        final MinecraftClient currentClient,
        final net.minecraft.client.network.ClientPlayNetworkHandler handler,
        final PacketByteBuf buf,
        final net.fabricmc.fabric.api.networking.v1.PacketSender responseSender
    ) {
        final byte[] payload;
        try {
            payload = readPayload(buf);
        } catch (final SharedWaypointProtocolException | RuntimeException e) {
            ConfluxMapMod.LOGGER.warn("shared-waypoint: dropping malformed S2C payload ({})", e.getMessage());
            executeForConnection(currentClient, handler, this::rejectProtocol);
            return;
        }

        final SharedWaypointMessage message;
        try {
            message = SharedWaypointCodec.decodeS2C(payload);
        } catch (final SharedWaypointProtocolException | RuntimeException e) {
            ConfluxMapMod.LOGGER.warn(
                "shared-waypoint: undecodable S2C payload ({} bytes): {}",
                payload.length,
                e.getMessage()
            );
            executeForConnection(currentClient, handler, this::rejectProtocol);
            return;
        }
        executeForConnection(currentClient, handler, () -> dispatch(message));
    }

    private void dispatch(final SharedWaypointMessage message) {
        if (message instanceof final ResultS2C result) {
            onResult(result);
            return;
        }

        final SharedWaypointClientState.View before = stateMachine.view();
        final SharedWaypointClientState.Action action;
        if (message instanceof final StatusS2C status) {
            action = stateMachine.onStatus(status);
        } else if (message instanceof final SnapshotS2C snapshot) {
            action = stateMachine.onSnapshot(snapshot);
        } else if (message instanceof final UpsertS2C upsert) {
            action = stateMachine.onUpsert(upsert);
        } else if (message instanceof final RemoveS2C remove) {
            action = stateMachine.onRemove(remove);
        } else {
            ConfluxMapMod.LOGGER.warn(
                "shared-waypoint: unexpected S2C {}",
                message.getClass().getSimpleName()
            );
            rejectProtocol();
            return;
        }
        notifyChanges(before, stateMachine.view());
        perform(action);
    }

    private void perform(final SharedWaypointClientState.Action action) {
        if (action.protocolRejected()) {
            ConfluxMapMod.LOGGER.warn("shared-waypoint: server payload failed semantic validation");
        }
        if (action.notifyDisabled()) {
            showMessage("confluxmap.shared_waypoints.disabled");
        }
        if (action.subscribe() && !send(new SubscribeC2S())) {
            failTransport("SUBSCRIBE send failed");
        }
    }

    private void onResult(final ResultS2C result) {
        final PendingOperation pending = pendingOperations.remove(result.operationId());
        if (pending == null) {
            ConfluxMapMod.LOGGER.debug(
                "shared-waypoint: ignoring result for unknown operation {}",
                result.operationId()
            );
            return;
        }

        final boolean applied = result.statusCode() == SharedWaypointProto.RESULT_STATUS_APPLIED
            && result.errorCode() == SharedWaypointProto.RESULT_ERROR_NONE;
        final boolean rejected = result.statusCode() == SharedWaypointProto.RESULT_STATUS_REJECTED
            && knownError(result.errorCode())
            && result.errorCode() != SharedWaypointProto.RESULT_ERROR_NONE;
        if (!applied && !rejected) {
            ConfluxMapMod.LOGGER.warn(
                "shared-waypoint: invalid result codes status={} error={} for operation {}",
                result.statusCode(),
                result.errorCode(),
                result.operationId()
            );
        }

        if (result.errorCode() == SharedWaypointProto.RESULT_ERROR_REVISION_CONFLICT) {
            final SharedWaypointClientState.View before = stateMachine.view();
            final SharedWaypointClientState.Action action = stateMachine.onRevisionConflict();
            notifyChanges(before, stateMachine.view());
            perform(action);
        }

        final OperationResult operationResult = new OperationResult(
            result.operationId(),
            pending.kind(),
            applied,
            result.statusCode(),
            result.errorCode()
        );
        for (final Listener listener : listeners) {
            try {
                listener.onOperationResult(operationResult);
            } catch (final RuntimeException e) {
                ConfluxMapMod.LOGGER.error("shared-waypoint listener failed", e);
            }
        }
        showMessage(resultTranslationKey(applied, result.errorCode()));
    }

    private boolean sendMutation(
        final UUID operationId,
        final PendingOperation pending,
        final SharedWaypointMessage message
    ) {
        pendingOperations.put(operationId, pending);
        if (!send(message)) {
            pendingOperations.remove(operationId);
            return false;
        }
        return true;
    }

    private boolean send(final SharedWaypointMessage message) {
        final byte[] payload;
        try {
            payload = SharedWaypointCodec.encode(message);
        } catch (final SharedWaypointProtocolException | RuntimeException e) {
            ConfluxMapMod.LOGGER.warn(
                "shared-waypoint: cannot encode {}: {}",
                message.getClass().getSimpleName(),
                e.getMessage()
            );
            return false;
        }
        try {
            ClientPlayNetworking.send(CHANNEL, new PacketByteBuf(Unpooled.wrappedBuffer(payload)));
            return true;
        } catch (final IllegalStateException | IllegalArgumentException e) {
            ConfluxMapMod.LOGGER.debug(
                "shared-waypoint: send failed for {}: {}",
                message.getClass().getSimpleName(),
                e.getMessage()
            );
            return false;
        }
    }

    private void failTransport(final String reason) {
        final SharedWaypointClientState.View before = stateMachine.view();
        stateMachine.onProtocolFailure();
        pendingOperations.clear();
        ConfluxMapMod.LOGGER.info("shared-waypoint: {}, using unsupported-server fallback", reason);
        notifyChanges(before, stateMachine.view());
    }

    private void rejectProtocol() {
        final SharedWaypointClientState.View before = stateMachine.view();
        stateMachine.onProtocolFailure();
        pendingOperations.clear();
        notifyChanges(before, stateMachine.view());
    }

    private void notifyChanges(
        final SharedWaypointClientState.View before,
        final SharedWaypointClientState.View after
    ) {
        if (before.state() != after.state()) {
            for (final Listener listener : listeners) {
                try {
                    listener.onStateChanged(after.state());
                } catch (final RuntimeException e) {
                    ConfluxMapMod.LOGGER.error("shared-waypoint listener failed", e);
                }
            }
        }
        if (before.revision() != after.revision()
            || before.synchronizedSnapshot() != after.synchronizedSnapshot()
            || !before.list().equals(after.list())) {
            for (final Listener listener : listeners) {
                try {
                    listener.onWaypointsChanged(after.list(), after.revision());
                } catch (final RuntimeException e) {
                    ConfluxMapMod.LOGGER.error("shared-waypoint listener failed", e);
                }
            }
        }
    }

    private void showMessage(final String translationKey) {
        if (client.player != null) {
            client.player.sendMessage(new TranslatableText(translationKey), false);
        }
    }

    private static boolean knownError(final int errorCode) {
        return errorCode >= SharedWaypointProto.RESULT_ERROR_NONE
            && errorCode <= SharedWaypointProto.RESULT_ERROR_DUPLICATE_LOCATION;
    }

    private static String resultTranslationKey(final boolean applied, final int errorCode) {
        if (applied) {
            return "confluxmap.shared_waypoints.result.applied";
        }
        return switch (errorCode) {
            case SharedWaypointProto.RESULT_ERROR_INVALID_REQUEST ->
                "confluxmap.shared_waypoints.result.invalid_request";
            case SharedWaypointProto.RESULT_ERROR_REVISION_CONFLICT ->
                "confluxmap.shared_waypoints.result.revision_conflict";
            case SharedWaypointProto.RESULT_ERROR_NOT_FOUND ->
                "confluxmap.shared_waypoints.result.not_found";
            case SharedWaypointProto.RESULT_ERROR_FORBIDDEN ->
                "confluxmap.shared_waypoints.result.forbidden";
            case SharedWaypointProto.RESULT_ERROR_WORLD_QUOTA_EXCEEDED ->
                "confluxmap.shared_waypoints.result.world_quota";
            case SharedWaypointProto.RESULT_ERROR_PLAYER_QUOTA_EXCEEDED ->
                "confluxmap.shared_waypoints.result.player_quota";
            case SharedWaypointProto.RESULT_ERROR_RATE_LIMITED ->
                "confluxmap.shared_waypoints.result.rate_limited";
            case SharedWaypointProto.RESULT_ERROR_DUPLICATE_LOCATION ->
                "confluxmap.shared_waypoints.result.duplicate_location";
            case SharedWaypointProto.RESULT_ERROR_PERSISTENCE_FAILED ->
                "confluxmap.shared_waypoints.result.persistence_failed";
            case SharedWaypointProto.RESULT_ERROR_DISABLED ->
                "confluxmap.shared_waypoints.result.disabled";
            default -> "confluxmap.shared_waypoints.result.failed";
        };
    }

    private static byte[] readPayload(final PacketByteBuf buf)
        throws SharedWaypointProtocolException {
        final int readable = buf.readableBytes();
        if (readable < 1) {
            throw new SharedWaypointProtocolException("empty payload");
        }
        if (readable > SharedWaypointProto.MAX_S2C_PAYLOAD) {
            throw new SharedWaypointProtocolException(
                "S2C payload " + readable + " above cap " + SharedWaypointProto.MAX_S2C_PAYLOAD
            );
        }
        final byte[] payload = new byte[readable];
        buf.readBytes(payload);
        return payload;
    }

    private static void executeForConnection(
        final MinecraftClient client,
        final net.minecraft.client.network.ClientPlayNetworkHandler handler,
        final Runnable task
    ) {
        client.execute(() -> {
            if (client.getNetworkHandler() == handler) {
                task.run();
            }
        });
    }
}

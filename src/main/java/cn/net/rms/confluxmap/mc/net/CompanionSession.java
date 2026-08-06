package cn.net.rms.confluxmap.mc.net;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.net.ChunkPatchCodec;
import cn.net.rms.confluxmap.core.net.FlatBaselineS2C;
import cn.net.rms.confluxmap.core.net.HelloPolicyS2C;
import cn.net.rms.confluxmap.core.net.MapCompatibilityS2C;
import cn.net.rms.confluxmap.core.net.MapSyncCompatibility;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.ServerViewDistanceS2C;
import cn.net.rms.confluxmap.core.predict.FlatBaseline;
import cn.net.rms.confluxmap.nativepredict.PredictorVersion;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.annotations.Nullable;

/**
 * Client-side companion handshake state machine.
 *
 * <p>Lifecycle:
 * <pre>
 *   NONE --sendHello()--> HELLO_SENT --HELLO_POLICY--> ACTIVE
 *                            |
 *                            +--timeout (no policy within {@link #TIMEOUT_TICKS})--> NO_COMPANION
 *   any state --reset()--> NONE
 * </pre>
 *
 * <p>Visibility: main thread only for mutations; the getters read the {@link AtomicReference}'s
 * snapshot so render/worker threads can observe the latest state without locks. Three call sites:
 * <ul>
 *   <li>{@code ClientNetworking} calls {@link #onHelloSent()} / {@link #onPolicy(HelloPolicyS2C)}
 *       / {@link #reset()} after marshalling Fabric network callbacks to the client thread.</li>
 *   <li>{@code WorldSessionTracker} calls {@link #tick()} every client tick.</li>
 *   <li>{@code PredictionBootstrap} / {@code WorldSessionTracker} read the getters.</li>
 * </ul>
 *
 * <p>Non-companion servers never respond: while the handshake is pending no world identity is
 * exposed, then the timeout settles at NO_COMPANION and {@link #resolveWorldIdentity(String)}
 * releases the address-based fallback.
 */
public final class CompanionSession {
    /** After JOIN, wait at most this many ticks for HELLO_POLICY before giving up (100 ticks = 5 s). */
    static final int TIMEOUT_TICKS = 100;

    public enum State { NONE, HELLO_SENT, ACTIVE, NO_COMPANION, FAILED }

    private final AtomicReference<State> state = new AtomicReference<>(State.NONE);
    private volatile HelloPolicyS2C policy;
    private volatile FlatBaselineS2C flatBaselines;
    private volatile MapCompatibilityS2C pendingCompatibility;
    private volatile int serverViewDistance = -1;
    private volatile MapSyncCompatibility.ClientMode mapSyncMode =
        MapSyncCompatibility.ClientMode.INCOMPATIBLE;
    private int ticksSinceHello;

    /** Called from {@link ClientNetworking#sendHello()} the moment a C2S HELLO leaves the wire. */
    public void onHelloSent() {
        state.set(State.HELLO_SENT);
        policy = null;
        flatBaselines = null;
        pendingCompatibility = null;
        serverViewDistance = -1;
        mapSyncMode = MapSyncCompatibility.ClientMode.INCOMPATIBLE;
        ticksSinceHello = 0;
    }

    /** Stores an explicit profile selection; HELLO_POLICY still owns session activation. */
    public void onCompatibility(final MapCompatibilityS2C compatibility) {
        if (state.get() == State.HELLO_SENT) {
            pendingCompatibility = compatibility;
        }
    }

    /** Stores the server's effective chunk-send radius before policy activates the session. */
    public void onServerViewDistance(final ServerViewDistanceS2C viewDistance) {
        if (state.get() == State.HELLO_SENT && viewDistance != null) {
            serverViewDistance = viewDistance.chunks();
        }
    }

    /** Called from {@link ClientNetworking}'s receiver when an S2C HELLO_POLICY arrives. */
    public void onPolicy(final HelloPolicyS2C policy) {
        final MapSyncCompatibility.ClientMode selected = selectMapSyncMode(policy);
        mapSyncMode = selected;
        this.policy = selected == MapSyncCompatibility.ClientMode.INCOMPATIBLE
            ? withoutCorrections(policy) : policy;
        state.set(State.ACTIVE);
        ConfluxMapMod.LOGGER.info(
            "companion active (worldId={} worldgen={} seedGranted={} corrections={} mapSyncMode={} biomeMapAllowed={} structureSearchAllowed={} chunkLoadState={} entityRadarAllowed={})",
            policy.worldId(), policy.worldgenVersion(), policy.flags().seedGranted(),
            this.policy.flags().correctionsEnabled(), selected,
            !policy.flags().biomeMapForbidden(), !policy.flags().structureSearchForbidden(),
            policy.flags().chunkLoadStateEnabled(), !policy.flags().entityRadarForbidden()
        );
    }

    private MapSyncCompatibility.ClientMode selectMapSyncMode(final HelloPolicyS2C received) {
        final MapCompatibilityS2C compatibility = pendingCompatibility;
        if (compatibility == null) {
            return MapSyncCompatibility.fallbackClientMode(
                received.flags().correctionsEnabled(),
                received.flags().chunkRangeCorrectionEnabled(),
                PredictorVersion.full()
            );
        }
        final boolean wireMatches =
            compatibility.negotiationVersion() == MapSyncCompatibility.NEGOTIATION_VERSION
            && compatibility.protocolMajor() == Proto.PROTO_MAJOR
            && compatibility.protocolMinor() == Proto.PROTO_MINOR
            && MapSyncCompatibility.supportedProfile(
                compatibility.patchCodecVersion(), compatibility.regionCodecVersion()
            );
        if (!wireMatches || compatibility.correctionMode() == MapCompatibilityS2C.MODE_DISABLED) {
            return MapSyncCompatibility.ClientMode.INCOMPATIBLE;
        }
        if (!received.flags().correctionsEnabled()) {
            return MapSyncCompatibility.ClientMode.SERVER_DISABLED;
        }
        if (compatibility.correctionMode() == MapCompatibilityS2C.MODE_RESIDUAL
            && !PredictorVersion.full().equals(compatibility.serverPredictorVersion())) {
            return MapSyncCompatibility.ClientMode.INCOMPATIBLE;
        }
        return compatibility.correctionMode() == MapCompatibilityS2C.MODE_RESIDUAL
            ? MapSyncCompatibility.ClientMode.OPTIMAL_RESIDUAL
            : MapSyncCompatibility.ClientMode.COMPATIBLE_ABSOLUTE;
    }

    private static HelloPolicyS2C withoutCorrections(final HelloPolicyS2C source) {
        final HelloPolicyS2C.Flags flags = source.flags();
        return new HelloPolicyS2C(
            new HelloPolicyS2C.Flags(
                flags.seedGranted(), false, flags.biomeMapForbidden(),
                flags.chunkLoadStateEnabled(), flags.entityRadarForbidden(),
                false, false, flags.structureSearchForbidden()
            ),
            source.worldId(), source.worldgenVersion(), source.budgets(), source.dims()
        );
    }

    /** Called from {@link ClientNetworking}'s receiver: flat surfaces arrive just before the policy. */
    public void onFlatBaselines(final FlatBaselineS2C message) {
        this.flatBaselines = message;
    }

    /** Called from {@link ClientPlayConnectionEvents#DISCONNECT}; forget everything. */
    public void reset() {
        state.set(State.NONE);
        policy = null;
        flatBaselines = null;
        pendingCompatibility = null;
        serverViewDistance = -1;
        mapSyncMode = MapSyncCompatibility.ClientMode.INCOMPATIBLE;
        ticksSinceHello = 0;
    }

    /** Called from {@link ClientTickEvents#END_CLIENT_TICK} every tick. */
    public void tick() {
        if (state.get() == State.HELLO_SENT) {
            ticksSinceHello++;
            if (ticksSinceHello >= TIMEOUT_TICKS) {
                state.set(State.NO_COMPANION);
                ConfluxMapMod.LOGGER.info(
                    "companion silent for {} ticks, assuming non-companion server", TIMEOUT_TICKS
                );
            }
        }
    }

    public State state() {
        return state.get();
    }

    public boolean isActive() {
        return state.get() == State.ACTIVE;
    }

    /**
     * Resolves a multiplayer cache identity only when the companion capability is known. While a
     * HELLO is outstanding, returning empty prevents the client from briefly opening the shared
     * address fallback before a stable server world id arrives.
     */
    public Optional<WorldIdentity> resolveWorldIdentity(final String address) {
        final State current = state.get();
        if (current == State.HELLO_SENT) {
            return Optional.empty();
        }
        final HelloPolicyS2C currentPolicy = policy;
        if (current == State.ACTIVE && currentPolicy != null) {
            return Optional.of(WorldIdentity.multiplayer(address, currentPolicy.worldId()));
        }
        return Optional.of(WorldIdentity.multiplayer(address));
    }

    /**
     * Returns the seed the server advertised for dimension index {@code dimIndex}, or empty if
     * the companion is not active, has not granted the seed, or the dim has no seed.
     */
    public OptionalLong seedFor(final int dimIndex) {
        if (state.get() != State.ACTIVE || policy == null || !policy.flags().seedGranted()) {
            return OptionalLong.empty();
        }
        if (dimIndex < 0 || dimIndex >= policy.dims().size()) {
            return OptionalLong.empty();
        }
        final HelloPolicyS2C.DimDescriptor d = policy.dims().get(dimIndex);
        if (!d.hasSeed() || !d.predictable()) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(d.seed());
    }

    /** Latest received policy, or {@code null} if none yet. */
    public @Nullable HelloPolicyS2C policy() {
        return policy;
    }

    /**
     * Whether entity radar is permitted for the current connection. No companion, a silent
     * server, and an older companion with no policy bit all preserve the historical allowed
     * behavior. Only an active policy can forbid radar.
     */
    public boolean entityRadarAllowed() {
        final HelloPolicyS2C current = policy;
        return state.get() != State.ACTIVE || current == null || !current.flags().entityRadarForbidden();
    }

    /** Older or absent companion policies preserve the historical biome-map behavior. */
    public boolean biomeMapAllowed() {
        final HelloPolicyS2C current = policy;
        return state.get() != State.ACTIVE || current == null || !current.flags().biomeMapForbidden();
    }

    /** Older or absent companion policies preserve the historical structure-search behavior. */
    public boolean structureSearchAllowed() {
        final HelloPolicyS2C current = policy;
        return state.get() != State.ACTIVE || current == null || !current.flags().structureSearchForbidden();
    }

    /** Whether an active companion explicitly withheld the seed from this client. */
    public boolean seedSharingDisabledByServer() {
        final HelloPolicyS2C current = policy;
        return state.get() == State.ACTIVE && current != null && !current.flags().seedGranted();
    }

    /** Whether an active companion explicitly declined to serve map corrections. */
    public boolean mapCorrectionsDisabledByServer() {
        final HelloPolicyS2C current = policy;
        return state.get() == State.ACTIVE && current != null && !current.flags().correctionsEnabled();
    }

    public MapSyncCompatibility.ClientMode mapSyncMode() {
        return mapSyncMode;
    }

    /** Effective server chunk-send radius, or {@code -1} when an older server did not advertise it. */
    public int serverViewDistance() {
        return serverViewDistance;
    }

    public String mapSyncBaselineProfile() {
        if (mapSyncMode == MapSyncCompatibility.ClientMode.LEGACY_RESIDUAL) {
            return MapSyncCompatibility.STABLE_PREDICTOR;
        }
        final MapCompatibilityS2C compatibility = pendingCompatibility;
        if (mapSyncMode == MapSyncCompatibility.ClientMode.OPTIMAL_RESIDUAL
            && compatibility != null) {
            return compatibility.serverPredictorVersion();
        }
        return "";
    }

    /** Whether this connection selected the source-revision and block-light wire profile. */
    public boolean enhancedMapSyncProfile() {
        final MapCompatibilityS2C compatibility = pendingCompatibility;
        return compatibility != null
            && compatibility.patchCodecVersion() == PatchCodec.FORMAT_VERSION
            && compatibility.regionCodecVersion() == ChunkPatchCodec.FORMAT_VERSION;
    }

    /** Player-facing reason for a disabled sync control, or {@code null} while usable. */
    public @Nullable String mapCorrectionDisabledReasonKey() {
        if (state.get() != State.ACTIVE) {
            return null;
        }
        if (mapSyncMode == MapSyncCompatibility.ClientMode.INCOMPATIBLE) {
            return "confluxmap.screen.config.prediction.sync_incompatible_server";
        }
        return mapCorrectionsDisabledByServer()
            ? "confluxmap.screen.config.prediction.sync_disabled_by_server" : null;
    }

    /**
     * The server-advertised uniform surface for dimension index {@code dimIndex}, or empty when
     * the session is not active or the dim is not superflat (including pre-minor-2 servers,
     * which never send FLAT_BASELINE).
     */
    public Optional<FlatBaseline> flatBaselineFor(final int dimIndex) {
        if (state.get() != State.ACTIVE) {
            return Optional.empty();
        }
        final FlatBaselineS2C current = flatBaselines;
        if (current == null) {
            return Optional.empty();
        }
        for (final FlatBaselineS2C.Entry entry : current.entries()) {
            if (entry.dimIndex() == dimIndex) {
                return Optional.of(entry.baseline());
            }
        }
        return Optional.empty();
    }
}

package cn.net.rms.confluxmap.mc.net;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.net.HelloPolicyS2C;
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
 *       / {@link #reset()} from the Fabric event thread, which fabric-api guarantees is the client main thread.</li>
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
    private int ticksSinceHello;

    /** Called from {@link ClientNetworking#sendHello()} the moment a C2S HELLO leaves the wire. */
    public void onHelloSent() {
        state.set(State.HELLO_SENT);
        policy = null;
        ticksSinceHello = 0;
    }

    /** Called from {@link ClientNetworking}'s receiver when an S2C HELLO_POLICY arrives. */
    public void onPolicy(final HelloPolicyS2C policy) {
        this.policy = policy;
        state.set(State.ACTIVE);
        ConfluxMapMod.LOGGER.info(
            "companion active (worldId={} worldgen={} seedGranted={} corrections={} structures={})",
            policy.worldId(), policy.worldgenVersion(),
            policy.flags().seedGranted(), policy.flags().correctionsEnabled(), policy.flags().structureInfoEnabled()
        );
    }

    /** Called from {@link ClientPlayConnectionEvents#DISCONNECT}; forget everything. */
    public void reset() {
        state.set(State.NONE);
        policy = null;
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
}

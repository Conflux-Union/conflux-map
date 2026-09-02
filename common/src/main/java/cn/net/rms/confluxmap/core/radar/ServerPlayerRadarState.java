package cn.net.rms.confluxmap.core.radar;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.net.PlayerPositionsS2C;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Client-side online-player snapshots received from the server companion. */
public final class ServerPlayerRadarState {
    public record Sample(
        UUID playerId,
        String name,
        DimensionId dimension,
        double x,
        double y,
        double z,
        boolean spectator
    ) {
        public Sample {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(dimension, "dimension");
        }
    }

    public record PlayerView(
        UUID playerId,
        String name,
        DimensionId dimension,
        double x,
        double y,
        double z,
        boolean spectator
    ) {
    }

    public record HighlightView(PlayerView player, boolean ghost, DimensionId destination) {
    }

    private record LastSeen(Sample sample, long receivedAtMs) {
    }

    private Map<UUID, Sample> previous = Map.of();
    private Map<UUID, Sample> current = Map.of();
    private final Map<DimensionId, LastSeen> highlightedPositions = new HashMap<>();
    private long previousAtMs;
    private long currentAtMs;
    private UUID highlightedPlayerId;
    private WorldIdentity world = WorldIdentity.NONE;

    public void accept(final List<Sample> samples, final long receivedAtMs) {
        final Map<UUID, Sample> next = new HashMap<>();
        for (final Sample sample : samples) {
            next.put(sample.playerId(), sample);
        }
        previous = current;
        previousAtMs = currentAtMs;
        current = Map.copyOf(next);
        currentAtMs = receivedAtMs;
        if (highlightedPlayerId != null) {
            final Sample highlighted = current.get(highlightedPlayerId);
            if (highlighted == null) {
                clearHighlight();
            } else {
                highlightedPositions.put(
                    highlighted.dimension(), new LastSeen(highlighted, receivedAtMs)
                );
            }
        }
    }

    public void accept(final PlayerPositionsS2C snapshot, final long receivedAtMs) {
        final List<Sample> samples = new ArrayList<>(snapshot.entries().size());
        for (final PlayerPositionsS2C.Entry entry : snapshot.entries()) {
            samples.add(new Sample(
                entry.playerId(),
                entry.name(),
                DimensionId.parse(entry.dimensionId()),
                entry.x(),
                entry.y(),
                entry.z(),
                entry.spectator()
            ));
        }
        accept(samples, receivedAtMs);
    }

    public List<PlayerView> playersIn(final DimensionId dimension, final long nowMs) {
        final List<PlayerView> result = new ArrayList<>();
        for (final Sample sample : current.values()) {
            if (sample.dimension().equals(dimension)) {
                result.add(interpolate(sample, nowMs));
            }
        }
        return List.copyOf(result);
    }

    public Optional<PlayerView> player(final UUID playerId, final long nowMs) {
        final Sample sample = current.get(playerId);
        return sample == null ? Optional.empty() : Optional.of(interpolate(sample, nowMs));
    }

    public boolean highlight(final UUID playerId) {
        final Sample sample = current.get(playerId);
        if (sample == null) {
            return false;
        }
        highlightedPlayerId = playerId;
        highlightedPositions.clear();
        highlightedPositions.put(
            sample.dimension(), new LastSeen(sample, currentAtMs)
        );
        return true;
    }

    public boolean isHighlighted(final UUID playerId) {
        return Objects.equals(highlightedPlayerId, playerId);
    }

    public Optional<UUID> highlightedPlayerId() {
        return Optional.ofNullable(highlightedPlayerId);
    }

    public Optional<HighlightView> highlightedIn(
        final DimensionId dimension,
        final long nowMs,
        final long ghostDurationMs
    ) {
        if (highlightedPlayerId == null) {
            return Optional.empty();
        }
        final Sample live = current.get(highlightedPlayerId);
        if (live == null) {
            return Optional.empty();
        }
        if (live.dimension().equals(dimension)) {
            return Optional.of(new HighlightView(
                interpolate(live, nowMs), false, live.dimension()
            ));
        }
        final LastSeen lastSeen = highlightedPositions.get(dimension);
        if (lastSeen == null
            || nowMs - lastSeen.receivedAtMs() > Math.max(0L, ghostDurationMs)) {
            return Optional.empty();
        }
        return Optional.of(new HighlightView(
            view(lastSeen.sample()), true, live.dimension()
        ));
    }

    public void clearHighlight() {
        highlightedPlayerId = null;
        highlightedPositions.clear();
    }

    public void onSessionChanged(final SessionGuard.Session session) {
        if (!session.active() || (world.isPresent() && !world.equals(session.world()))) {
            clear();
        }
        world = session.active() ? session.world() : WorldIdentity.NONE;
    }

    public void clear() {
        previous = Map.of();
        current = Map.of();
        previousAtMs = 0L;
        currentAtMs = 0L;
        clearHighlight();
    }

    private PlayerView interpolate(final Sample sample, final long nowMs) {
        final Sample before = previous.get(sample.playerId());
        if (before == null || !before.dimension().equals(sample.dimension())
            || currentAtMs <= previousAtMs) {
            return view(sample);
        }
        final double interval = currentAtMs - previousAtMs;
        final double alpha = clamp((nowMs - currentAtMs) / interval);
        return new PlayerView(
            sample.playerId(),
            sample.name(),
            sample.dimension(),
            lerp(before.x(), sample.x(), alpha),
            lerp(before.y(), sample.y(), alpha),
            lerp(before.z(), sample.z(), alpha),
            sample.spectator()
        );
    }

    private static PlayerView view(final Sample sample) {
        return new PlayerView(
            sample.playerId(), sample.name(), sample.dimension(),
            sample.x(), sample.y(), sample.z(), sample.spectator()
        );
    }

    private static double lerp(final double from, final double to, final double alpha) {
        return from + (to - from) * alpha;
    }

    private static double clamp(final double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}

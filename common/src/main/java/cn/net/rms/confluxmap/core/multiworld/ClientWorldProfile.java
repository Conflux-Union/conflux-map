package cn.net.rms.confluxmap.core.multiworld;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** One client-owned logical world namespace beneath a multiplayer address. */
public final class ClientWorldProfile {
    private String id;
    private String storageId;
    private String displayName;
    private List<Binding> bindings;
    private boolean recognitionDisabled;
    private String velocityServerName;

    ClientWorldProfile() {
        // Gson
    }

    ClientWorldProfile(final String id, final String storageId, final String displayName) {
        this.id = requireText(id, "id");
        this.storageId = requireText(storageId, "storageId");
        this.displayName = requireText(displayName, "displayName");
        this.bindings = new ArrayList<>();
    }

    public String id() {
        return id;
    }

    public String storageId() {
        return storageId;
    }

    public String displayName() {
        return displayName;
    }

    public int bindingCount() {
        return bindings == null ? 0 : bindings.size();
    }

    public Optional<String> velocityServerName() {
        return Optional.ofNullable(velocityServerName);
    }

    void rename(final String name) {
        displayName = requireText(name, "name");
    }

    void clearBindings() {
        bindings().clear();
        velocityServerName = null;
        recognitionDisabled = true;
    }

    boolean recognitionDisabled() {
        return recognitionDisabled;
    }

    boolean matchesVelocityServer(final String serverName) {
        return Objects.equals(velocityServerName, serverName);
    }

    void bindVelocityServer(final String serverName) {
        velocityServerName = requireText(serverName, "serverName");
        recognitionDisabled = false;
    }

    /** Returns whether this profile has previously been observed on the supplied seed hash. */
    public boolean matchesSeed(final long seedHash) {
        for (final Binding binding : bindings()) {
            if (binding.hasSeed && binding.seedHash == seedHash) {
                return true;
            }
        }
        return false;
    }

    int bestSignalMatch(final ClientWorldObservation observation) {
        int best = 0;
        for (final Binding binding : bindings()) {
            int matches = 0;
            for (final Map.Entry<String, String> signal : observation.signals().entrySet()) {
                if (signal.getValue().equals(binding.signals.get(signal.getKey()))) {
                    matches++;
                }
            }
            best = Math.max(best, matches);
        }
        return best;
    }

    void bind(final ClientWorldObservation observation) {
        final Binding next = Binding.from(observation);
        if (!bindings().contains(next)) {
            bindings.add(next);
        }
        recognitionDisabled = false;
    }

    boolean unbind(final ClientWorldObservation observation) {
        return bindings().remove(Binding.from(observation));
    }

    void normalize() {
        id = requireText(id, "id");
        storageId = requireText(storageId, "storageId");
        displayName = requireText(displayName, "displayName");
        if (velocityServerName != null) {
            velocityServerName = requireText(velocityServerName, "velocityServerName");
        }
        final List<Binding> valid = new ArrayList<>();
        if (bindings != null) {
            for (final Binding binding : bindings) {
                if (binding != null) {
                    binding.normalize();
                    if (!valid.contains(binding)) {
                        valid.add(binding);
                    }
                }
            }
        }
        bindings = valid;
    }

    private List<Binding> bindings() {
        if (bindings == null) {
            bindings = new ArrayList<>();
        }
        return bindings;
    }

    private static String requireText(final String value, final String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static final class Binding {
        private boolean hasSeed;
        private long seedHash;
        private Map<String, String> signals;

        private Binding() {
            // Gson
        }

        static Binding from(final ClientWorldObservation observation) {
            final Binding binding = new Binding();
            final OptionalLong seed = observation.seedHash();
            binding.hasSeed = seed.isPresent();
            binding.seedHash = seed.orElse(0L);
            binding.signals = new LinkedHashMap<>(observation.signals());
            return binding;
        }

        void normalize() {
            signals = signals == null ? new LinkedHashMap<>() : new LinkedHashMap<>(signals);
            signals.entrySet().removeIf(entry -> entry.getKey() == null || entry.getKey().isBlank()
                || entry.getValue() == null || entry.getValue().isBlank());
        }

        @Override
        public boolean equals(final Object other) {
            return this == other || other instanceof final Binding binding
                && hasSeed == binding.hasSeed
                && seedHash == binding.seedHash
                && Objects.equals(signals, binding.signals);
        }

        @Override
        public int hashCode() {
            return Objects.hash(hasSeed, seedHash, signals);
        }
    }
}

package cn.net.rms.confluxmap.core.multiworld;

/**
 * Bounded runtime policy for client-owned world recognition. User configuration may tighten
 * these limits, but normalization never permits values above the safety ceilings.
 */
public record ClientWorldPolicy(
    int maxProfilesPerServer,
    int maxBindingsPerProfile,
    int commandConfirmationSeconds,
    int visitRefreshSeconds,
    int visitRefreshDistance
) {
    public static final int DEFAULT_MAX_PROFILES_PER_SERVER = 128;
    public static final int DEFAULT_MAX_BINDINGS_PER_PROFILE = 64;
    public static final int DEFAULT_COMMAND_CONFIRMATION_SECONDS = 10;
    public static final int DEFAULT_VISIT_REFRESH_SECONDS = 3;
    public static final int DEFAULT_VISIT_REFRESH_DISTANCE = 256;

    public static final int MIN_MAX_PROFILES_PER_SERVER = 1;
    public static final int MIN_MAX_BINDINGS_PER_PROFILE = 1;
    public static final int MIN_COMMAND_CONFIRMATION_SECONDS = 5;
    public static final int MIN_VISIT_REFRESH_SECONDS = 3;
    public static final int MIN_VISIT_REFRESH_DISTANCE = 64;

    public static final int MAX_MAX_PROFILES_PER_SERVER = 128;
    public static final int MAX_MAX_BINDINGS_PER_PROFILE = 64;
    public static final int MAX_COMMAND_CONFIRMATION_SECONDS = 30;
    public static final int MAX_VISIT_REFRESH_SECONDS = 300;
    public static final int MAX_VISIT_REFRESH_DISTANCE = 2_048;

    public ClientWorldPolicy {
        maxProfilesPerServer = clamp(
            maxProfilesPerServer, MIN_MAX_PROFILES_PER_SERVER, MAX_MAX_PROFILES_PER_SERVER
        );
        maxBindingsPerProfile = clamp(
            maxBindingsPerProfile, MIN_MAX_BINDINGS_PER_PROFILE, MAX_MAX_BINDINGS_PER_PROFILE
        );
        commandConfirmationSeconds = clamp(
            commandConfirmationSeconds,
            MIN_COMMAND_CONFIRMATION_SECONDS,
            MAX_COMMAND_CONFIRMATION_SECONDS
        );
        visitRefreshSeconds = clamp(
            visitRefreshSeconds, MIN_VISIT_REFRESH_SECONDS, MAX_VISIT_REFRESH_SECONDS
        );
        visitRefreshDistance = clamp(
            visitRefreshDistance, MIN_VISIT_REFRESH_DISTANCE, MAX_VISIT_REFRESH_DISTANCE
        );
    }

    public static ClientWorldPolicy defaults() {
        return new ClientWorldPolicy(
            DEFAULT_MAX_PROFILES_PER_SERVER,
            DEFAULT_MAX_BINDINGS_PER_PROFILE,
            DEFAULT_COMMAND_CONFIRMATION_SECONDS,
            DEFAULT_VISIT_REFRESH_SECONDS,
            DEFAULT_VISIT_REFRESH_DISTANCE
        );
    }

    public int commandConfirmationTicks() {
        return commandConfirmationSeconds * 20;
    }

    public int visitRefreshTicks() {
        return visitRefreshSeconds * 20;
    }

    private static int clamp(final int value, final int minimum, final int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}

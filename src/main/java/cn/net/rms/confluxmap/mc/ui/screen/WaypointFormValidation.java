package cn.net.rms.confluxmap.mc.ui.screen;

import java.util.Optional;

/** Validation boundary for waypoint forms; invalid input is never silently replaced. */
final class WaypointFormValidation {
    enum Error {
        NAME_REQUIRED,
        INVALID_COORDINATES
    }

    record Values(String name, double x, double y, double z) {
    }

    private WaypointFormValidation() {
    }

    static Optional<Error> error(
        final String name,
        final String x,
        final String y,
        final String z
    ) {
        if (name == null || name.trim().isEmpty()) {
            return Optional.of(Error.NAME_REQUIRED);
        }
        try {
            if (!Double.isFinite(Double.parseDouble(x))
                || !Double.isFinite(Double.parseDouble(y))
                || !Double.isFinite(Double.parseDouble(z))) {
                return Optional.of(Error.INVALID_COORDINATES);
            }
        } catch (final NumberFormatException ignored) {
            return Optional.of(Error.INVALID_COORDINATES);
        }
        return Optional.empty();
    }

    static Values values(final String name, final String x, final String y, final String z) {
        if (error(name, x, y, z).isPresent()) {
            throw new IllegalArgumentException("Waypoint form is invalid");
        }
        return new Values(
            name.trim(), Double.parseDouble(x), Double.parseDouble(y), Double.parseDouble(z)
        );
    }
}

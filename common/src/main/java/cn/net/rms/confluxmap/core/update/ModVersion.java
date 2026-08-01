package cn.net.rms.confluxmap.core.update;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Parsed {@code MAJOR.MINOR.PATCH[-prerelease]} version with SemVer 2.0 precedence,
 * lenient about the release-tag conventions this project actually uses: an optional
 * leading {@code v}/{@code V} is stripped and build metadata ({@code +...}) is
 * ignored. Anything else malformed parses to empty rather than throwing, so one
 * odd tag in the release feed never breaks the whole update check.
 */
public final class ModVersion implements Comparable<ModVersion> {
    private final int major;
    private final int minor;
    private final int patch;
    /** SemVer prerelease identifiers; empty means a full release, which outranks any prerelease. */
    private final List<String> prerelease;

    private ModVersion(final int major, final int minor, final int patch, final List<String> prerelease) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.prerelease = prerelease;
    }

    public static Optional<ModVersion> parse(final String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String s = raw.trim();
        if (s.toLowerCase(Locale.ROOT).startsWith("v")) {
            s = s.substring(1);
        }
        final int plus = s.indexOf('+');
        if (plus >= 0) {
            s = s.substring(0, plus);
        }
        final int dash = s.indexOf('-');
        final String core = dash >= 0 ? s.substring(0, dash) : s;
        final String[] parts = core.split("\\.", -1);
        if (parts.length != 3) {
            return Optional.empty();
        }
        final int[] nums = new int[3];
        for (int i = 0; i < 3; i++) {
            if (!isDigits(parts[i]) || parts[i].length() > 9) {
                return Optional.empty();
            }
            nums[i] = Integer.parseInt(parts[i]);
        }
        final List<String> ids = new ArrayList<>();
        if (dash >= 0) {
            for (final String id : s.substring(dash + 1).split("\\.", -1)) {
                if (!isValidIdentifier(id)) {
                    return Optional.empty();
                }
                ids.add(id);
            }
        }
        return Optional.of(new ModVersion(nums[0], nums[1], nums[2], List.copyOf(ids)));
    }

    @Override
    public int compareTo(final ModVersion other) {
        int cmp = Integer.compare(major, other.major);
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(minor, other.minor);
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(patch, other.patch);
        if (cmp != 0) {
            return cmp;
        }
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
            // A release outranks any prerelease of the same core version.
            return Boolean.compare(prerelease.isEmpty(), other.prerelease.isEmpty());
        }
        final int shared = Math.min(prerelease.size(), other.prerelease.size());
        for (int i = 0; i < shared; i++) {
            cmp = compareIdentifiers(prerelease.get(i), other.prerelease.get(i));
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(prerelease.size(), other.prerelease.size());
    }

    /** SemVer rule 11.4: numeric identifiers compare numerically and rank below alphanumeric ones. */
    private static int compareIdentifiers(final String a, final String b) {
        final boolean aNumeric = isDigits(a);
        final boolean bNumeric = isDigits(b);
        if (aNumeric != bNumeric) {
            return aNumeric ? -1 : 1;
        }
        if (!aNumeric) {
            return a.compareTo(b);
        }
        final String aTrim = stripLeadingZeros(a);
        final String bTrim = stripLeadingZeros(b);
        final int byLength = Integer.compare(aTrim.length(), bTrim.length());
        return byLength != 0 ? byLength : aTrim.compareTo(bTrim);
    }

    private static String stripLeadingZeros(final String digits) {
        int i = 0;
        while (i < digits.length() - 1 && digits.charAt(i) == '0') {
            i++;
        }
        return digits.substring(i);
    }

    private static boolean isDigits(final String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidIdentifier(final String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            final boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean equals(final Object o) {
        if (!(o instanceof ModVersion other)) {
            return false;
        }
        return major == other.major && minor == other.minor && patch == other.patch
            && prerelease.equals(other.prerelease);
    }

    @Override
    public int hashCode() {
        return ((major * 31 + minor) * 31 + patch) * 31 + prerelease.hashCode();
    }

    @Override
    public String toString() {
        final String core = major + "." + minor + "." + patch;
        return prerelease.isEmpty() ? core : core + "-" + String.join(".", prerelease);
    }
}

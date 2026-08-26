package cn.net.rms.confluxmap.nativepredict;

/** Decides whether the unsupported-platform warning may open in this client process. */
public final class UnsupportedPlatformWarningPolicy {
    private final boolean officiallySupported;
    private boolean shown;

    public UnsupportedPlatformWarningPolicy(final boolean officiallySupported) {
        this.officiallySupported = officiallySupported;
    }

    public boolean shouldShow(final boolean dismissed) {
        return !officiallySupported && !dismissed && !shown;
    }

    public void markShown() {
        shown = true;
    }
}

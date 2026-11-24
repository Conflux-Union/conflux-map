package cn.net.rms.confluxmap.core.community;

/** Reserved client-local click value used by the community invitation opt-out action. */
public final class CommunityInviteClickPayload {
    private static final String DISMISS = "confluxmap:community:dismiss";

    private CommunityInviteClickPayload() {
    }

    public static String dismiss() {
        return DISMISS;
    }

    public static boolean isDismiss(final String value) {
        return DISMISS.equals(value);
    }
}

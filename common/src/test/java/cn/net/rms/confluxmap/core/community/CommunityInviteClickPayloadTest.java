package cn.net.rms.confluxmap.core.community;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CommunityInviteClickPayloadTest {

    @Test
    void onlyTheReservedDismissPayloadOptsOut() {
        assertTrue(CommunityInviteClickPayload.isDismiss(CommunityInviteClickPayload.dismiss()));
        assertFalse(CommunityInviteClickPayload.isDismiss("ordinary clipboard text"));
        assertFalse(CommunityInviteClickPayload.isDismiss("confluxmap:community:dismiss-extra"));
        assertFalse(CommunityInviteClickPayload.isDismiss("confluxmap:survey:dismiss"));
        assertFalse(CommunityInviteClickPayload.isDismiss(null));
    }
}

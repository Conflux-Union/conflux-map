package cn.net.rms.confluxmap.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.net.FeatureRestrictionsS2C;
import org.junit.jupiter.api.Test;

class CompanionPolicyRestrictionsTest {
    @Test
    void defaultConfigProjectsNoRestrictions() {
        assertTrue(CompanionPolicy.configuredRestrictions(new ServerConfig()).isEmpty());
    }

    @Test
    void eachAllowToggleProjectsItsOwnRestrictionBit() {
        final ServerConfig config = new ServerConfig();
        config.allowMinimap = false;
        config.allowWorldMap = false;
        config.allowWaypoints = false;
        config.allowPrediction = false;

        final FeatureRestrictionsS2C restrictions = CompanionPolicy.configuredRestrictions(config);
        assertTrue(restrictions.minimapForbidden());
        assertTrue(restrictions.worldMapForbidden());
        assertTrue(restrictions.waypointsForbidden());
        assertTrue(restrictions.predictionForbidden());
        assertFalse(restrictions.isEmpty());

        config.enabled = false;
        assertTrue(
            CompanionPolicy.configuredRestrictions(config).isEmpty(),
            "a disabled companion sends no handshake, so no restriction frame either"
        );
    }
}

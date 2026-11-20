package cn.net.rms.confluxmap.server.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SharedWaypointValidatorTest {
    private final SharedWaypointValidator validator = new SharedWaypointValidator(Map.of(
        DimensionId.OVERWORLD, new SharedWaypointValidator.HeightRange(-64, 320)
    ));

    private Optional<SharedWaypointValidator.ValidatedCreate> validate(
        final String description,
        final String imageId
    ) {
        return validator.validate(
            "Village", DimensionId.OVERWORLD, 1.0, 64.0, 2.0, 0xFF336699,
            Waypoint.Type.NORMAL, "", "", description, imageId
        );
    }

    @Test
    void multilineDescriptionsPassWithNewlinesPreserved() {
        final Optional<SharedWaypointValidator.ValidatedCreate> validated =
            validate("产物:\n钻石 ×3\n获取方式:挖矿", "");
        assertTrue(validated.isPresent());
        assertEquals("产物:\n钻石 ×3\n获取方式:挖矿", validated.get().description());
        assertEquals("", validated.get().imageId());
    }

    @Test
    void carriageReturnsAreStrippedNotRejected() {
        final Optional<SharedWaypointValidator.ValidatedCreate> validated =
            validate("line1\r\nline2", "");
        assertTrue(validated.isPresent());
        assertEquals("line1\nline2", validated.get().description());
    }

    @Test
    void descriptionsBeyondTheCodePointCapAreRejected() {
        assertTrue(validate("a".repeat(513), "").isEmpty());
    }

    @Test
    void emptyDescriptionAndImageIdNormalizeToEmptyStrings() {
        final Optional<SharedWaypointValidator.ValidatedCreate> validated = validate(null, "  ");
        assertTrue(validated.isPresent());
        assertEquals("", validated.get().description());
        assertEquals("", validated.get().imageId());
    }

    @Test
    void imageIdsAreCanonicalizedOrRejected() {
        assertTrue(validate("", "0123ABCD-4567-789A-BCDE-F0123456789A").isPresent());
        assertEquals(
            "0123abcd-4567-789a-bcde-f0123456789a",
            validate("", "0123ABCD-4567-789A-BCDE-F0123456789A").get().imageId()
        );
        assertTrue(validate("", "not-a-uuid").isEmpty());
        assertTrue(validate("bad§c", "").isEmpty());
    }
}

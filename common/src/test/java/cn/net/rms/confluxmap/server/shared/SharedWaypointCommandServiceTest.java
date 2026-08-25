package cn.net.rms.confluxmap.server.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;

class SharedWaypointCommandServiceTest {
    private static final SharedWaypointService.Actor PLAYER = actor(1, "Player", false);
    private static final SharedWaypointService.Actor OPERATOR = actor(2, "Operator", true);

    @Test
    void playerCanPublishCurrentPositionAndListItAsAXaeroImport() {
        final Fixture fixture = fixture();

        final SharedWaypointCommandService.Result created = fixture.commands.createHere(
            PLAYER,
            new SharedWaypointCommandService.Position(DimensionId.OVERWORLD, 12.75d, 64d, -4.2d),
            "Village"
        );
        final SharedWaypointCommandService.Page page = fixture.commands.list(1);

        assertTrue(created.applied());
        assertTrue(page.valid());
        assertEquals(1, page.page());
        assertEquals(1, page.totalPages());
        assertEquals(1, page.totalWaypoints());
        assertEquals(1, page.entries().size());
        assertEquals("00000000", page.entries().get(0).idPrefix());
        assertEquals(
            "xaero-waypoint:Village:V:12:64:-5:3:false:0:Internal-overworld-waypoints",
            page.entries().get(0).xaeroMessage()
        );
    }

    @Test
    void administratorCanRenameMoveAndDeleteByDisplayedIdPrefix() {
        final Fixture fixture = fixture();
        final SharedWaypoint created = fixture.commands.createHere(
            PLAYER,
            new SharedWaypointCommandService.Position(DimensionId.OVERWORLD, 1d, 64d, 2d),
            "Old"
        ).waypoint();
        final String id = fixture.commands.list(1).entries().get(0).idPrefix();

        assertTrue(fixture.commands.rename(OPERATOR, id, "New").applied());
        assertTrue(fixture.commands.moveHere(
            OPERATOR,
            id,
            new SharedWaypointCommandService.Position(DimensionId.NETHER, 8d, 70d, 9d)
        ).applied());
        assertTrue(fixture.commands.delete(OPERATOR, id).applied());
        assertTrue(fixture.commands.list(1).entries().isEmpty());
        assertEquals(created.id(), fixture.mutations.get(0).delta().waypoint().id());
        assertEquals(4, fixture.mutations.size());
    }

    @Test
    void enabledOwnersCanManageOnlyTheirOwnCommandEntries() {
        final Fixture fixture = fixture();
        fixture.commands.createHere(
            PLAYER,
            new SharedWaypointCommandService.Position(DimensionId.OVERWORLD, 1d, 64d, 1d),
            "One"
        );
        fixture.commands.createHere(
            OPERATOR,
            new SharedWaypointCommandService.Position(DimensionId.OVERWORLD, 2d, 64d, 2d),
            "Two"
        );

        final List<SharedWaypointCommandService.Entry> entries = fixture.commands.list(1).entries();
        final SharedWaypointCommandService.Result renamed = fixture.commands.rename(
            PLAYER, entries.get(0).idPrefix(), "Owned"
        );
        final SharedWaypointCommandService.Result forbidden = fixture.commands.delete(
            PLAYER, entries.get(1).idPrefix()
        );
        final SharedWaypointCommandService.Result ambiguous = fixture.commands.delete(OPERATOR, "0");
        final SharedWaypointCommandService.Page outside = fixture.commands.list(2);

        assertTrue(renamed.applied());
        assertEquals(SharedWaypointCommandService.Status.FORBIDDEN, forbidden.status());
        assertEquals(SharedWaypointCommandService.Status.AMBIGUOUS_ID, ambiguous.status());
        assertFalse(outside.valid());
        assertEquals(2, fixture.commands.list(1).entries().size());
    }

    private static Fixture fixture() {
        final AtomicLong ids = new AtomicLong(10);
        final SharedWaypointService service = new SharedWaypointService(
            SharedWaypointStore.empty(),
            new SharedWaypointPersistence() {
                @Override
                public SharedWaypointStore.Snapshot load() {
                    return SharedWaypointStore.empty().snapshot();
                }

                @Override
                public void save(final SharedWaypointStore.Snapshot snapshot) throws IOException {
                }
            },
            new SharedWaypointValidator(Map.of(
                DimensionId.OVERWORLD, new SharedWaypointValidator.HeightRange(0, 256),
                DimensionId.NETHER, new SharedWaypointValidator.HeightRange(0, 128)
            )),
            Clock.systemUTC(),
            () -> uuid(ids.getAndIncrement()),
            new SharedWaypointService.Limits(20, 10, 60),
            SharedWaypointService.AccessPolicy.OWNER_MANAGED,
            event -> { },
            LogManager.getLogger("SharedWaypointCommandServiceTest")
        );
        final AtomicLong operations = new AtomicLong(100);
        final List<SharedWaypointService.MutationResult> mutations = new ArrayList<>();
        final SharedWaypointCommandService commands = new SharedWaypointCommandService(
            service, () -> uuid(operations.getAndIncrement()), mutations::add
        );
        return new Fixture(commands, mutations);
    }

    private static SharedWaypointService.Actor actor(
        final long id,
        final String name,
        final boolean operator
    ) {
        return new SharedWaypointService.Actor(uuid(id), name, operator);
    }

    private static UUID uuid(final long value) {
        return new UUID(0L, value);
    }

    private record Fixture(
        SharedWaypointCommandService commands,
        List<SharedWaypointService.MutationResult> mutations
    ) {
    }
}

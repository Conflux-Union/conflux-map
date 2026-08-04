package cn.net.rms.confluxmap.mc.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.predict.StructureIndex;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Queue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StructureViewportQueryTest {
    @TempDir
    Path tempDir;

    @Test
    void returnsImmediatelyAndPublishesCompletedMarkers() {
        final Queue<Runnable> worker = new ArrayDeque<>();
        final List<StructureViewportQuery.Request> completed = new ArrayList<>();
        final StructureIndex.Marker marker = markerAt(32, 48);
        final StructureViewportQuery query = new StructureViewportQuery(
            worker::add,
            ignored -> List.of(marker),
            completed::add
        );
        final StructureViewportQuery.Request request = request(0, 64);

        assertTrue(query.request(request).isEmpty());
        assertEquals(1, worker.size());

        worker.remove().run();

        assertEquals(List.of(marker), query.request(request));
        assertEquals(List.of(request), completed);
        assertTrue(worker.isEmpty());
    }

    @Test
    void publishesOnlyTheLatestViewport() {
        final Queue<Runnable> worker = new ArrayDeque<>();
        final List<Integer> loadedMinX = new ArrayList<>();
        final StructureViewportQuery.Request first = request(0, 64);
        final StructureViewportQuery.Request second = request(128, 192);
        final StructureViewportQuery[] holder = new StructureViewportQuery[1];
        holder[0] = new StructureViewportQuery(
            worker::add,
            request -> {
                loadedMinX.add(request.minBlockX());
                if (request.equals(first)) {
                    assertTrue(holder[0].request(second).isEmpty());
                }
                return List.of(markerAt(request.minBlockX(), 0));
            },
            ignored -> {}
        );

        assertTrue(holder[0].request(first).isEmpty());
        worker.remove().run();

        assertEquals(List.of(markerAt(128, 0)), holder[0].request(second));
        assertEquals(List.of(0, 128), loadedMinX);
        assertTrue(worker.isEmpty());
    }

    @Test
    void clearDropsAnInFlightResult() {
        final Queue<Runnable> worker = new ArrayDeque<>();
        final StructureViewportQuery.Request request = request(0, 64);
        final StructureViewportQuery[] holder = new StructureViewportQuery[1];
        holder[0] = new StructureViewportQuery(
            worker::add,
            ignored -> {
                holder[0].clear();
                return List.of(markerAt(32, 48));
            },
            ignored -> {}
        );

        assertTrue(holder[0].request(request).isEmpty());
        worker.remove().run();

        assertTrue(holder[0].request(request).isEmpty());
        assertEquals(1, worker.size());
    }

    private StructureViewportQuery.Request request(final int minX, final int maxX) {
        return new StructureViewportQuery.Request(
            1L,
            new StructureIndex(
                tempDir,
                WorldIdentity.singleplayer("test"),
                DimensionId.OVERWORLD,
                (type, regionX, regionZ) -> new long[0]
            ),
            minX,
            maxX,
            0,
            64,
            2.0,
            EnumSet.of(StructureIndex.StructureType.VILLAGE)
        );
    }

    private static StructureIndex.Marker markerAt(final int x, final int z) {
        return new StructureIndex.Marker(
            StructureIndex.StructureType.VILLAGE,
            x,
            z,
            StructureIndex.State.CANDIDATE
        );
    }
}

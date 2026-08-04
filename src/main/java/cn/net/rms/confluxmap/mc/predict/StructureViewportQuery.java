package cn.net.rms.confluxmap.mc.predict;

import cn.net.rms.confluxmap.core.predict.StructureIndex;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

/** Runs the latest visible structure query without making the render thread wait for cubiomes. */
final class StructureViewportQuery {
    record Request(
        long generation,
        StructureIndex index,
        int minBlockX,
        int maxBlockX,
        int minBlockZ,
        int maxBlockZ,
        double blocksPerPixel,
        Set<StructureIndex.StructureType> includedTypes
    ) {
        Request {
            includedTypes = Set.copyOf(includedTypes);
        }
    }

    private final Executor executor;
    private final Function<Request, List<StructureIndex.Marker>> loader;
    private final Consumer<Request> completion;
    private Request requested;
    private Request completed;
    private List<StructureIndex.Marker> markers = List.of();
    private boolean running;

    StructureViewportQuery(
        final Executor executor,
        final Function<Request, List<StructureIndex.Marker>> loader,
        final Consumer<Request> completion
    ) {
        this.executor = executor;
        this.loader = loader;
        this.completion = completion;
    }

    synchronized List<StructureIndex.Marker> request(final Request request) {
        if (!request.equals(requested)) {
            requested = request;
            completed = null;
            markers = List.of();
        }
        if (!running && !request.equals(completed)) {
            running = true;
            executor.execute(this::runLatest);
        }
        return request.equals(completed) ? markers : List.of();
    }

    synchronized void clear() {
        requested = null;
        completed = null;
        markers = List.of();
    }

    private void runLatest() {
        while (true) {
            final Request request;
            synchronized (this) {
                request = requested;
                if (request == null) {
                    running = false;
                    return;
                }
            }

            final List<StructureIndex.Marker> loaded = List.copyOf(loader.apply(request));
            completion.accept(request);

            synchronized (this) {
                if (request.equals(requested)) {
                    completed = request;
                    markers = loaded;
                    running = false;
                    return;
                }
            }
        }
    }
}

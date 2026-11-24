package cn.net.rms.confluxmap.core.waypoint.library;

import com.google.gson.JsonDeserializer;
import java.util.function.Function;

final class WaypointJson {
    private WaypointJson() {
    }

    /** Decode through mutable fields so the oldest bundled Gson does not have to instantiate records. */
    static <D, T> JsonDeserializer<T> readUsing(Class<D> dataClass, Function<D, T> toRecord) {
        return (json, type, context) -> toRecord.apply(context.deserialize(json, dataClass));
    }
}

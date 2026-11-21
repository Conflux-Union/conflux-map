package cn.net.rms.confluxmap.core.net.image;

/**
 * Waypoint-image channel messages. C2S carries uploads and fetches; S2C carries
 * upload outcomes and image bytes. Every record validates its own field ranges
 * so a malformed value throws before it can reach storage.
 */
public interface WaypointImageMessage {

    int typeId();

    interface C2S extends WaypointImageMessage {
    }

    interface S2C extends WaypointImageMessage {
    }
}

package cn.net.rms.confluxmap.core.net.shared;

/** Marker for one framed message on {@link SharedWaypointProto#CHANNEL_ID}. */
public interface SharedWaypointMessage {
    int typeId();
}

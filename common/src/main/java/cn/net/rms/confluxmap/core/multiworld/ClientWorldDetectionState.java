package cn.net.rms.confluxmap.core.multiworld;

/** Lifecycle of client-only upstream world recognition. Only STABLE permits map writes. */
public enum ClientWorldDetectionState {
    STABLE,
    SUSPECTED,
    PROBING,
    WAITING_FOR_USER
}

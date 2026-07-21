package cn.net.rms.confluxmap.core.radar;

/**
 * One tracked entity as of the most recent radar scan.
 *
 * <p>{@code x}/{@code z} and {@code yDelta} (height relative to the player) are captured at
 * scan time; the render layer prefers a live interpolated position looked up by
 * {@code entityId} when the entity is still present in the world, falling back to these
 * fields when it is not (e.g. the tick right before the next scan prunes a despawned entry).
 *
 * @param x world X at scan time
 * @param z world Z at scan time
 * @param yDelta world Y minus the player's world Y at scan time
 * @param category the coarse bucket this entity was classified into
 * @param name display name, populated only for {@link RadarCategory#PLAYER} entries; null otherwise
 * @param entityId the client-side entity id, used both as scan-bucket identity and to look up
 *     a live entity reference for smooth per-frame interpolation
 * @param spectator true when the entity was in spectator game mode at scan time; the render
 *     layer draws such entries translucent instead of hiding them
 */
public record RadarEntry(
    double x,
    double z,
    int yDelta,
    RadarCategory category,
    String name,
    int entityId,
    boolean spectator
) {
}

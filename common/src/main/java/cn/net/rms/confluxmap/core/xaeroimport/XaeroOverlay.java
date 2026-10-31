package cn.net.rms.confluxmap.core.xaeroimport;

/**
 * One translucent layer stacked above a column's opaque surface, decoded
 * topmost-first. A {@code null} state means water: the format does not store
 * the water block identity, it is the reader-side default overlay.
 *
 * @param state translucent block identity, null for water
 * @param light block light carried by this layer
 * @param opacity light attenuation 0-15; this is <b>not</b> a water depth (it
 *     saturates at 15, real depth comes from {@link XaeroColumn#translucentDepth()})
 */
public record XaeroOverlay(XaeroBlockState state, int light, int opacity) {
    public boolean isWater() {
        return state == null;
    }
}

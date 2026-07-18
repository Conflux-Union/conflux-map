package cn.net.rms.confluxmap.core.net;

import java.util.List;

/**
 * {@code 0x03 C2S MAP_VIEW_REQ}: the client asks the server to reconsider a batch of tiles in
 * one dimension. S3 frames this message; the request-planning logic (viewport prune, debounce,
 * nearest-first selection, rate limiting) lands in S5.
 *
 * @param reqId     client-chosen correlation id echoed back in each {@link MapPatchS2C}
 * @param dimIndex  index into {@link HelloPolicyS2C#dims()} for the dimension these tiles belong to
 * @param lod       LOD level (0..=4); the server rejects anything above {@link HelloPolicyS2C.Budgets#maxPatchLod()}
 * @param tiles     one {@link TileReq} per tile; capped at {@value Proto#MAX_TILES_PER_REQ}
 */
public record MapViewReqC2S(int reqId, int dimIndex, int lod, List<TileReq> tiles) implements Message {

    /**
     * @param tileX          tile X coordinate (256 output pixels; world span is 256 << lod blocks)
     * @param tileZ          tile Z coordinate
     * @param sinceRevision  last {@code tileRevision} the client has cached for this tile; 0 = cold
     */
    public record TileReq(int tileX, int tileZ, long sinceRevision) {
    }

    @Override
    public int typeId() {
        return Proto.MSG_MAP_VIEW_REQ_C2S;
    }
}

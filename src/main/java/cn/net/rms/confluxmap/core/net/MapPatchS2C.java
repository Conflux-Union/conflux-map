package cn.net.rms.confluxmap.core.net;

/**
 * {@code 0x04 S2C MAP_PATCH}: one tile's worth of server-side correction data.
 *
 * <p><b>S3 framing only.</b> The fixed header fields are fully parsed here; {@link #body} is
 * an opaque blob the codec round-trips without inspecting. {@code PatchCodec.encode/decode}
 * the body lands in S4, at which point the {@code // S4:} comments below mark the seam.
 *
 * @param reqId         echo of {@link MapViewReqC2S#reqId()}
 * @param dimIndex      echo of {@link MapViewReqC2S#dimIndex()}
 * @param lod           echo of {@link MapViewReqC2S#lod()}
 * @param tileX         tile X this patch belongs to
 * @param tileZ         tile Z this patch belongs to
 * @param mode          one of {@link Proto#PATCH_MODE_UNCHANGED} / {@link Proto#PATCH_MODE_RESIDUAL} /
 *                      {@link Proto#PATCH_MODE_ABSOLUTE} / {@link Proto#PATCH_MODE_UNAVAILABLE}
 * @param tileRevision  server's current revision counter for this tile (game-time ticks); the client
 *                      echoes it back as {@link MapViewReqC2S.TileReq#sinceRevision()} on the next request
 * @param presence      exactly {@value Proto#PATCH_PRESENCE_BYTES} bytes; one bit per chunk (16x16 =
 *                      256 chunks per tile). Bit set = server has real data for that chunk in this tile.
 *                      Used by S5's {@code GENERATED_ONLY} prediction view mode.
 * @param body          opaque payload; its interpretation depends on {@code mode}:
 *                      <ul>
 *                        <li>{@link Proto#PATCH_MODE_UNCHANGED} / {@link Proto#PATCH_MODE_UNAVAILABLE}:
 *                            always empty.</li>
 *                        <li>{@link Proto#PATCH_MODE_ABSOLUTE} / {@link Proto#PATCH_MODE_RESIDUAL}:
 *                            PatchCodec-encoded records. S3 carries this verbatim; S4 parses it.</li>
 *                      </ul>
 */
public record MapPatchS2C(
    int reqId,
    int dimIndex,
    int lod,
    int tileX,
    int tileZ,
    int mode,
    long tileRevision,
    byte[] presence,
    byte[] body
) implements Message {
    // S4: PatchCodec.encode/decode the body. Until then, body is opaque bytes whose length
    //     the codec caps at Proto.MAX_S2C_PAYLOAD minus the fixed header size.

    @Override
    public int typeId() {
        return Proto.MSG_MAP_PATCH_S2C;
    }
}

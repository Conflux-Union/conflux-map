package cn.net.rms.confluxmap.core.net;

import java.util.List;

/**
 * {@code 0x04 S2C MAP_PATCH}: one tile's worth of server-side correction data.
 *
 * <p>The fixed header, presence bitmap, structure entries, and compressed sparse body are all
 * parsed by {@link MsgCodec}; {@link PatchCodec} owns the body representation.
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
 * @param presence      exactly {@value Proto#PATCH_PRESENCE_BYTES} bytes; one bit per 16x16 output
 *                      pixel cell. At LOD0 a cell is one chunk; at higher LODs it is the union of
 *                      chunks touched by that cell. Used by S5's {@code GENERATED_ONLY} view mode.
 * @param body          PatchCodec-compressed payload; its interpretation depends on {@code mode}:
 *                      <ul>
 *                        <li>{@link Proto#PATCH_MODE_UNCHANGED} / {@link Proto#PATCH_MODE_UNAVAILABLE}:
 *                            always empty.</li>
 *                        <li>{@link Proto#PATCH_MODE_ABSOLUTE} / {@link Proto#PATCH_MODE_RESIDUAL}:
 *                            PatchCodec-encoded records.</li>
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
    byte[] body,
    List<StructureEntry> structures
) implements Message {
    public record StructureEntry(int structType, int blockX, int blockZ, int state) {
    }

    public MapPatchS2C(
        final int reqId, final int dimIndex, final int lod, final int tileX, final int tileZ, final int mode,
        final long tileRevision, final byte[] presence, final byte[] body
    ) {
        this(reqId, dimIndex, lod, tileX, tileZ, mode, tileRevision, presence, body, List.of());
    }

    public MapPatchS2C {
        presence = presence == null ? null : presence.clone();
        body = body == null ? null : body.clone();
        structures = structures == null ? List.of() : List.copyOf(structures);
        if (structures.size() > 255) {
            throw new IllegalArgumentException("too many structure entries");
        }
    }
    @Override
    public int typeId() {
        return Proto.MSG_MAP_PATCH_S2C;
    }
}

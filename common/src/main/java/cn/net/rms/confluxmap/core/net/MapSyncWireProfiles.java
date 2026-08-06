package cn.net.rms.confluxmap.core.net;

/** Converts enhanced correction bodies to the released legacy profile for older peers. */
public final class MapSyncWireProfiles {
    private MapSyncWireProfiles() {
    }

    public static Message legacy(final Message message) throws ProtoException {
        if (message instanceof final MapPatchS2C patch && hasBody(patch.mode())) {
            return new MapPatchS2C(
                patch.reqId(), patch.dimIndex(), patch.lod(), patch.tileX(), patch.tileZ(),
                patch.mode(), patch.tileRevision(), patch.presence(),
                PatchCodec.encodeLegacy(PatchCodec.decode(patch.body())), patch.structures()
            );
        }
        if (message instanceof final MapRegionPatchS2C patch && hasBody(patch.mode())) {
            return new MapRegionPatchS2C(
                patch.reqId(), patch.dimIndex(), patch.lod(), patch.regionX(), patch.regionZ(),
                patch.minLocalChunkX(), patch.minLocalChunkZ(),
                patch.maxLocalChunkX(), patch.maxLocalChunkZ(), patch.mode(),
                patch.regionRevision(),
                ChunkPatchCodec.encodeLegacy(ChunkPatchCodec.decode(patch.body()))
            );
        }
        return message;
    }

    private static boolean hasBody(final int mode) {
        return mode == Proto.PATCH_MODE_RESIDUAL
            || mode == Proto.PATCH_MODE_ABSOLUTE
            || mode == Proto.PATCH_MODE_PARTIAL;
    }
}

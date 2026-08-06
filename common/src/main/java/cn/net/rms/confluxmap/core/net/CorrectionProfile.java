package cn.net.rms.confluxmap.core.net;

/** One cohesive correction-body format and its cache/source-selection semantics. */
public enum CorrectionProfile {
    LEGACY_V1(
        1, PatchCodec.LEGACY_FORMAT_VERSION, ChunkPatchCodec.LEGACY_FORMAT_VERSION,
        false, false
    ),
    SOURCE_LIGHT_V2(
        2, PatchCodec.SOURCE_LIGHT_FORMAT_VERSION, ChunkPatchCodec.SOURCE_LIGHT_FORMAT_VERSION,
        true, false
    ),
    MATERIAL_COLOR_V3(
        3, PatchCodec.FORMAT_VERSION, ChunkPatchCodec.FORMAT_VERSION,
        true, true
    );

    private final int id;
    private final int patchCodecVersion;
    private final int regionCodecVersion;
    private final boolean sourceMetadata;
    private final boolean materialIdentity;

    CorrectionProfile(
        final int id,
        final int patchCodecVersion,
        final int regionCodecVersion,
        final boolean sourceMetadata,
        final boolean materialIdentity
    ) {
        this.id = id;
        this.patchCodecVersion = patchCodecVersion;
        this.regionCodecVersion = regionCodecVersion;
        this.sourceMetadata = sourceMetadata;
        this.materialIdentity = materialIdentity;
    }

    public int id() {
        return id;
    }

    public int patchCodecVersion() {
        return patchCodecVersion;
    }

    public int regionCodecVersion() {
        return regionCodecVersion;
    }

    public boolean carriesSourceMetadata() {
        return sourceMetadata;
    }

    public boolean carriesMaterialIdentity() {
        return materialIdentity;
    }

    public byte[] encode(final PatchCodec.Patch patch) {
        return materialIdentity ? PatchCodec.encode(patch)
            : sourceMetadata ? PatchCodec.encodeSourceLight(patch)
            : PatchCodec.encodeLegacy(patch);
    }

    public byte[] encode(final ChunkPatchCodec.Patch patch) {
        return materialIdentity ? ChunkPatchCodec.encode(patch)
            : sourceMetadata ? ChunkPatchCodec.encodeSourceLight(patch)
            : ChunkPatchCodec.encodeLegacy(patch);
    }

    public Message prepareOutbound(final Message message) throws ProtoException {
        if (materialIdentity) {
            return message;
        }
        if (message instanceof final MapPatchS2C patch && hasBody(patch.mode())) {
            return new MapPatchS2C(
                patch.reqId(), patch.dimIndex(), patch.lod(), patch.tileX(), patch.tileZ(),
                patch.mode(), patch.tileRevision(), patch.presence(),
                encode(PatchCodec.decode(patch.body())), patch.structures()
            );
        }
        if (message instanceof final MapRegionPatchS2C patch && hasBody(patch.mode())) {
            return new MapRegionPatchS2C(
                patch.reqId(), patch.dimIndex(), patch.lod(), patch.regionX(), patch.regionZ(),
                patch.minLocalChunkX(), patch.minLocalChunkZ(),
                patch.maxLocalChunkX(), patch.maxLocalChunkZ(), patch.mode(),
                patch.regionRevision(), encode(ChunkPatchCodec.decode(patch.body()))
            );
        }
        return message;
    }

    public static CorrectionProfile fromId(final int id) {
        for (final CorrectionProfile profile : values()) {
            if (profile.id == id) {
                return profile;
            }
        }
        return null;
    }

    public static CorrectionProfile fromCodecVersions(
        final int patchCodecVersion,
        final int regionCodecVersion
    ) {
        for (final CorrectionProfile profile : values()) {
            if (profile.patchCodecVersion == patchCodecVersion
                && profile.regionCodecVersion == regionCodecVersion) {
                return profile;
            }
        }
        return null;
    }

    private static boolean hasBody(final int mode) {
        return mode == Proto.PATCH_MODE_RESIDUAL
            || mode == Proto.PATCH_MODE_ABSOLUTE
            || mode == Proto.PATCH_MODE_PARTIAL;
    }
}

package cn.net.rms.confluxmap.core.net.image;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** One ordered piece of an in-flight upload. Non-final chunks get no reply. */
public record ImageUploadChunkC2S(UUID imageId, int seq, byte[] bytes)
    implements WaypointImageMessage.C2S {

    public ImageUploadChunkC2S {
        Objects.requireNonNull(imageId, "imageId");
        Objects.requireNonNull(bytes, "bytes");
        if (seq < 0) {
            throw new IllegalArgumentException("negative chunk sequence " + seq);
        }
        if (bytes.length < 1 || bytes.length > WaypointImageProto.MAX_CHUNK_BYTES) {
            throw new IllegalArgumentException(
                "chunk length " + bytes.length + " outside 1.." + WaypointImageProto.MAX_CHUNK_BYTES
            );
        }
        bytes = bytes.clone();
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof final ImageUploadChunkC2S that
            && imageId.equals(that.imageId)
            && seq == that.seq
            && Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(imageId, seq, Arrays.hashCode(bytes));
    }

    @Override
    public int typeId() {
        return WaypointImageProto.MSG_IMAGE_UPLOAD_CHUNK;
    }
}

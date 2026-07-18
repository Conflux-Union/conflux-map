package cn.net.rms.confluxmap.core.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/** Compact, hostile-input-safe codec for the sparse MAP_PATCH body. */
public final class PatchCodec {
    public static final int PIXELS = 256 * 256;
    public static final int RECORD_BYTES = 6;
    public static final int COARSE_MASK_BYTES = 32;
    public static final int FINE_MASK_BYTES = 32;
    public static final int MAX_RAW_BYTES = 512 * 1024;
    public static final int MAX_COMPRESSED_BYTES = 48 * 1024;

    private PatchCodec() {
    }

    /** One corrected pixel. {@code pixelIndex = z * 256 + x}. */
    public record Sample(int pixelIndex, int biomeId, int surfaceY, int kind, int mapColorId, int fluidDepth) {
        public Sample {
            if (pixelIndex < 0 || pixelIndex >= PIXELS) {
                throw new IllegalArgumentException("pixel index outside tile: " + pixelIndex);
            }
            checkByte("biomeId", biomeId);
            if (surfaceY < Short.MIN_VALUE || surfaceY > Short.MAX_VALUE) {
                throw new IllegalArgumentException("surfaceY outside i16: " + surfaceY);
            }
            checkByte("kind", kind);
            checkByte("mapColorId", mapColorId);
            checkByte("fluidDepth", fluidDepth);
        }

        private static void checkByte(final String field, final int value) {
            if (value < 0 || value > 255) {
                throw new IllegalArgumentException(field + " outside u8: " + value);
            }
        }
    }

    /** Decoded sparse records, sorted by pixel index. */
    public record Patch(List<Sample> samples) {
        public Patch {
            samples = List.copyOf(samples);
        }

        public int size() {
            return samples.size();
        }

        public List<Sample> records() {
            return samples;
        }

        public Sample sampleAt(final int pixelIndex) {
            for (final Sample sample : samples) {
                if (sample.pixelIndex() == pixelIndex) {
                    return sample;
                }
            }
            return null;
        }
    }

    public static byte[] encode(final Patch patch) {
        if (patch == null) {
            throw new IllegalArgumentException("patch is null");
        }
        final List<Sample> samples = new ArrayList<>(patch.samples());
        samples.sort(Comparator.comparingInt(Sample::pixelIndex));
        final byte[] coarse = new byte[COARSE_MASK_BYTES];
        final byte[][] fine = new byte[PIXELS / 256][];
        final boolean[] seen = new boolean[PIXELS];
        for (final Sample sample : samples) {
            final int pixel = sample.pixelIndex();
            if (seen[pixel]) {
                throw new IllegalArgumentException("duplicate pixel index " + pixel);
            }
            seen[pixel] = true;
            final int x = pixel & 255;
            final int z = pixel >>> 8;
            final int coarseIndex = (z >>> 4) * 16 + (x >>> 4);
            final int fineIndex = (z & 15) * 16 + (x & 15);
            setBit(coarse, coarseIndex);
            if (fine[coarseIndex] == null) {
                fine[coarseIndex] = new byte[FINE_MASK_BYTES];
            }
            setBit(fine[coarseIndex], fineIndex);
        }
        try {
            final ByteArrayOutputStream rawBytes = new ByteArrayOutputStream(COARSE_MASK_BYTES + samples.size() * 38);
            final DataOutputStream raw = new DataOutputStream(rawBytes);
            raw.write(coarse);
            for (int coarseIndex = 0; coarseIndex < fine.length; coarseIndex++) {
                if (fine[coarseIndex] == null) {
                    continue;
                }
                raw.write(fine[coarseIndex]);
                for (int fineIndex = 0; fineIndex < 256; fineIndex++) {
                    if (hasBit(fine[coarseIndex], fineIndex)) {
                        final int pixel = (((coarseIndex >>> 4) << 4 | (fineIndex >>> 4)) << 8)
                            | ((coarseIndex & 15) << 4 | (fineIndex & 15));
                        final Sample sample = find(samples, pixel);
                        raw.writeByte(sample.biomeId());
                        raw.writeShort(sample.surfaceY());
                        raw.writeByte(sample.kind());
                        raw.writeByte(sample.mapColorId());
                        raw.writeByte(sample.fluidDepth());
                    }
                }
            }
            raw.flush();
            final byte[] uncompressed = rawBytes.toByteArray();
            if (uncompressed.length > MAX_RAW_BYTES) {
                throw new IllegalArgumentException("patch body exceeds raw cap");
            }
            final ByteArrayOutputStream compressed = new ByteArrayOutputStream(uncompressed.length);
            final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
            try {
                final DeflaterOutputStream out = new DeflaterOutputStream(compressed, deflater, 8192);
                out.write(uncompressed);
                out.finish();
            } finally {
                deflater.end();
            }
            final byte[] result = compressed.toByteArray();
            if (result.length > MAX_COMPRESSED_BYTES) {
                throw new IllegalArgumentException("patch body exceeds compressed cap: " + result.length);
            }
            return result;
        } catch (final IOException e) {
            throw new IllegalStateException("in-memory patch encoding failed", e);
        }
    }

    public static byte[] encode(final Iterable<Sample> samples) {
        final List<Sample> list = new ArrayList<>();
        for (final Sample sample : samples) {
            list.add(sample);
        }
        return encode(new Patch(list));
    }

    public static byte[] encode(final java.util.Map<Integer, Sample> samples) {
        return encode(samples.values());
    }

    public static Patch decode(final byte[] body) throws ProtoException {
        if (body == null || body.length == 0 || body.length > MAX_COMPRESSED_BYTES) {
            throw new ProtoException("invalid patch body length: " + (body == null ? -1 : body.length));
        }
        final byte[] raw = inflate(body);
        try {
            final DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
            final byte[] coarse = new byte[COARSE_MASK_BYTES];
            in.readFully(coarse);
            final List<Sample> samples = new ArrayList<>();
            for (int coarseIndex = 0; coarseIndex < 256; coarseIndex++) {
                if (!hasBit(coarse, coarseIndex)) {
                    continue;
                }
                final byte[] fine = new byte[FINE_MASK_BYTES];
                in.readFully(fine);
                final int coarseX = coarseIndex & 15;
                final int coarseZ = coarseIndex >>> 4;
                for (int fineIndex = 0; fineIndex < 256; fineIndex++) {
                    if (!hasBit(fine, fineIndex)) {
                        continue;
                    }
                    final int x = (coarseX << 4) | (fineIndex & 15);
                    final int z = (coarseZ << 4) | (fineIndex >>> 4);
                    samples.add(new Sample(
                        (z << 8) | x,
                        in.readUnsignedByte(), in.readShort(), in.readUnsignedByte(),
                        in.readUnsignedByte(), in.readUnsignedByte()
                    ));
                }
            }
            if (in.available() != 0) {
                throw new ProtoException("trailing bytes in patch body: " + in.available());
            }
            return new Patch(samples);
        } catch (final EOFException | IllegalArgumentException e) {
            throw new ProtoException("malformed patch body: " + e.getMessage(), e);
        } catch (final IOException e) {
            throw new ProtoException("malformed patch body", e);
        }
    }

    private static byte[] inflate(final byte[] body) throws ProtoException {
        try {
            final InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(body));
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_RAW_BYTES) {
                    throw new ProtoException("inflated patch body exceeds cap");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (final ProtoException e) {
            throw e;
        } catch (final IOException e) {
            throw new ProtoException("invalid deflate patch body", e);
        }
    }

    private static void setBit(final byte[] bits, final int index) {
        bits[index >>> 3] |= (byte) (1 << (index & 7));
    }

    private static Sample find(final List<Sample> samples, final int pixel) {
        int low = 0;
        int high = samples.size() - 1;
        while (low <= high) {
            final int mid = (low + high) >>> 1;
            final int value = samples.get(mid).pixelIndex();
            if (value < pixel) {
                low = mid + 1;
            } else if (value > pixel) {
                high = mid - 1;
            } else {
                return samples.get(mid);
            }
        }
        throw new IllegalStateException("mask has no sample for pixel " + pixel);
    }

    private static boolean hasBit(final byte[] bits, final int index) {
        return (bits[index >>> 3] & (1 << (index & 7))) != 0;
    }
}

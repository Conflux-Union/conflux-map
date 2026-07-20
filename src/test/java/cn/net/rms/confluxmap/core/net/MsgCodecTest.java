package cn.net.rms.confluxmap.core.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Round-trip, cap-enforcement, and fuzz coverage for {@link MsgCodec}. Every message type is
 * covered individually, then randomized over a few hundred iterations to exercise edge cases
 * (empty bodies, boundary lengths, hostile byte sequences).
 */
class MsgCodecTest {

    // ---- Per-type round-trip ----

    @Test
    void helloC2SRoundTrips() throws ProtoException {
        final HelloC2S original = new HelloC2S("0.2.0", "cb:e61f90580cbd|shim:1|base:1");
        final HelloC2S decoded = (HelloC2S) MsgCodec.decode(MsgCodec.encode(original));
        assertEquals(original.modVersion(), decoded.modVersion());
        assertEquals(original.predictorVersion(), decoded.predictorVersion());
        assertEquals(Proto.MSG_HELLO_C2S, decoded.typeId());
    }

    @Test
    void helloPolicyRoundTripsWithSeed() throws ProtoException {
        final HelloPolicyS2C original = new HelloPolicyS2C(
            new HelloPolicyS2C.Flags(true, true, false),
            "11111111-2222-3333-4444-555555555555",
            "1.17",
            new HelloPolicyS2C.Budgets(65_536, 8, 300, 2),
            List.of(
                new HelloPolicyS2C.DimDescriptor("minecraft:overworld", "overworld", true, true, -4587293450L),
                new HelloPolicyS2C.DimDescriptor("minecraft:the_end", "the_end", true, true, -4587293450L)
            )
        );
        final HelloPolicyS2C decoded = (HelloPolicyS2C) MsgCodec.decode(MsgCodec.encode(original));
        assertEquals(original.flags(), decoded.flags());
        assertEquals(original.worldId(), decoded.worldId());
        assertEquals(original.worldgenVersion(), decoded.worldgenVersion());
        assertEquals(original.budgets(), decoded.budgets());
        assertIterableEquals(original.dims(), decoded.dims());
        assertEquals(Proto.MSG_HELLO_POLICY_S2C, decoded.typeId());
    }

    @Test
    void helloPolicyRoundTripsWithoutSeed() throws ProtoException {
        final HelloPolicyS2C original = new HelloPolicyS2C(
            new HelloPolicyS2C.Flags(false, true, true),
            "deadbeef-0000-0000-0000-000000000000",
            "1.17",
            new HelloPolicyS2C.Budgets(32_768, 4, 500, 1),
            List.of(
                new HelloPolicyS2C.DimDescriptor("minecraft:overworld", "overworld", true, false, 0L)
            )
        );
        final HelloPolicyS2C decoded = (HelloPolicyS2C) MsgCodec.decode(MsgCodec.encode(original));
        assertEquals(original.flags(), decoded.flags());
        assertEquals(original.worldId(), decoded.worldId());
        assertEquals(1, decoded.dims().size());
        assertEquals(original.dims().get(0), decoded.dims().get(0));
    }

    @Test
    void mapViewReqRoundTrips() throws ProtoException {
        final MapViewReqC2S original = new MapViewReqC2S(
            0x12345678,
            1,
            2,
            List.of(
                new MapViewReqC2S.TileReq(-1, -2, 0L),
                new MapViewReqC2S.TileReq(100, -100, 1_700_000_000L),
                new MapViewReqC2S.TileReq(Integer.MAX_VALUE, Integer.MIN_VALUE, Long.MAX_VALUE)
            )
        );
        final MapViewReqC2S decoded = (MapViewReqC2S) MsgCodec.decode(MsgCodec.encode(original));
        assertEquals(original.reqId(), decoded.reqId());
        assertEquals(original.dimIndex(), decoded.dimIndex());
        assertEquals(original.lod(), decoded.lod());
        assertIterableEquals(original.tiles(), decoded.tiles());
        assertEquals(Proto.MSG_MAP_VIEW_REQ_C2S, decoded.typeId());
    }

    @Test
    void mapPatchStubRoundTripsOpaqueBody() throws ProtoException {
        final byte[] presence = new byte[Proto.PATCH_PRESENCE_BYTES];
        for (int i = 0; i < presence.length; i++) {
            presence[i] = (byte) (i * 7 + 3);
        }
        final byte[] body = new byte[] {1, 2, 3, 0x7F, -128, 0, 0, 'X', 'Y', 'Z'};
        final MapPatchS2C original = new MapPatchS2C(
            42, 0, 1, -5, 7, Proto.PATCH_MODE_RESIDUAL, 123_456_789L, presence, body
        );
        final MapPatchS2C decoded = (MapPatchS2C) MsgCodec.decode(MsgCodec.encode(original));
        assertEquals(original.reqId(), decoded.reqId());
        assertEquals(original.dimIndex(), decoded.dimIndex());
        assertEquals(original.lod(), decoded.lod());
        assertEquals(original.tileX(), decoded.tileX());
        assertEquals(original.tileZ(), decoded.tileZ());
        assertEquals(original.mode(), decoded.mode());
        assertEquals(original.tileRevision(), decoded.tileRevision());
        assertArrayEquals(original.presence(), decoded.presence());
        assertArrayEquals(original.body(), decoded.body());
        assertEquals(Proto.MSG_MAP_PATCH_S2C, decoded.typeId());
    }

    @Test
    void mapPatchWithEmptyBodyRoundTrips() throws ProtoException {
        final byte[] presence = new byte[Proto.PATCH_PRESENCE_BYTES];
        final MapPatchS2C original = new MapPatchS2C(
            0, 0, 0, 0, 0, Proto.PATCH_MODE_UNAVAILABLE, 0L, presence, new byte[0]
        );
        final MapPatchS2C decoded = (MapPatchS2C) MsgCodec.decode(MsgCodec.encode(original));
        assertEquals(0, decoded.body().length);
    }

    @Test
    void policyUpdateRoundTrips() throws ProtoException {
        final PolicyUpdateS2C original = new PolicyUpdateS2C(
            new HelloPolicyS2C.Flags(false, true, false),
            new HelloPolicyS2C.Budgets(8_192, 2, 1_000, 0)
        );
        final PolicyUpdateS2C decoded = (PolicyUpdateS2C) MsgCodec.decode(MsgCodec.encode(original));
        assertEquals(original.flags(), decoded.flags());
        assertEquals(original.budgets(), decoded.budgets());
        assertEquals(Proto.MSG_POLICY_UPDATE_S2C, decoded.typeId());
    }

    @Test
    void errorRoundTrips() throws ProtoException {
        final ErrorS2C original = new ErrorS2C(ErrorS2C.ERR_RATE_LIMITED, "slow down");
        final ErrorS2C decoded = (ErrorS2C) MsgCodec.decode(MsgCodec.encode(original));
        assertEquals(original.code(), decoded.code());
        assertEquals(original.detail(), decoded.detail());
        assertEquals(Proto.MSG_ERROR_S2C, decoded.typeId());
    }

    // ---- Cap enforcement ----

    @Test
    void encodeRejectsTooLongUtf8() {
        final byte[] raw = new byte[Proto.MAX_UTF8_BYTES + 1];
        Arrays.fill(raw, (byte) 'A');
        final String tooLong = new String(raw, StandardCharsets.UTF_8);
        final HelloC2S msg = new HelloC2S(tooLong, "ok");
        assertThrows(ProtoException.class, () -> MsgCodec.encode(msg));
    }

    @Test
    void decodeRejectsTooLongUtf8() throws ProtoException {
        // Encode a valid hello, then patch the u16 length prefix of the first utf field
        // to exceed MAX_UTF8_BYTES so the decoder refuses it.
        final byte[] payload = MsgCodec.encode(new HelloC2S("ok", "ok"));
        payload[1] = (byte) ((Proto.MAX_UTF8_BYTES + 1) >>> 8);
        payload[2] = (byte) ((Proto.MAX_UTF8_BYTES + 1) & 0xFF);
        assertThrows(ProtoException.class, () -> MsgCodec.decode(payload));
    }

    @Test
    void decodeRejectsTruncatedPayload() throws ProtoException {
        final byte[] payload = MsgCodec.encode(new HelloC2S("hello", "world"));
        // Truncate mid-string: keep type byte and the u16 length, drop all utf bytes.
        final byte[] truncated = Arrays.copyOf(payload, 3);
        assertThrows(ProtoException.class, () -> MsgCodec.decode(truncated));
    }

    @Test
    void decodeRejectsTrailingBytesAfterACompleteMessage() throws ProtoException {
        final byte[] encoded = MsgCodec.encode(new HelloC2S("hello", "world"));
        final byte[] payload = Arrays.copyOf(encoded, encoded.length + 1);
        payload[payload.length - 1] = 0x55;

        assertThrows(ProtoException.class, () -> MsgCodec.decode(payload));
    }

    @Test
    void decodeRejectsMalformedUtf8() {
        final byte[] payload = new byte[] {
            (byte) Proto.MSG_HELLO_C2S,
            0, 1, (byte) 0xC3,
            0, 0
        };

        assertThrows(ProtoException.class, () -> MsgCodec.decode(payload));
    }

    @Test
    void decodeRejectsUnknownTypeByte() {
        // Type byte 0x00 is outside MSG_MIN..MSG_MAX; payload is otherwise irrelevant.
        final byte[] payload = new byte[] {0x00, 'x', 'y'};
        assertThrows(ProtoException.class, () -> MsgCodec.decode(payload));
    }

    @Test
    void decodeRejectsEmptyPayload() {
        assertThrows(ProtoException.class, () -> MsgCodec.decode(new byte[0]));
    }

    @Test
    void encodeRejectsTooManyTiles() {
        final MapViewReqC2S.TileReq[] tiles = new MapViewReqC2S.TileReq[Proto.MAX_TILES_PER_REQ + 1];
        Arrays.fill(tiles, new MapViewReqC2S.TileReq(0, 0, 0L));
        final MapViewReqC2S msg = new MapViewReqC2S(1, 0, 0, Arrays.asList(tiles));
        assertThrows(ProtoException.class, () -> MsgCodec.encode(msg));
    }

    @Test
    void encodeRejectsTooManyDims() {
        final HelloPolicyS2C.DimDescriptor[] dims = new HelloPolicyS2C.DimDescriptor[Proto.MAX_DIM_ENTRIES + 1];
        Arrays.fill(dims, new HelloPolicyS2C.DimDescriptor("minecraft:overworld", "overworld", true, true, 0L));
        final HelloPolicyS2C msg = new HelloPolicyS2C(
            new HelloPolicyS2C.Flags(false, false, false),
            "id", "1.17",
            new HelloPolicyS2C.Budgets(1, 1, 1, 1),
            Arrays.asList(dims)
        );
        assertThrows(ProtoException.class, () -> MsgCodec.encode(msg));
    }

    @Test
    void encodeRejectsWrongPresenceLength() {
        final MapPatchS2C msg = new MapPatchS2C(
            0, 0, 0, 0, 0, Proto.PATCH_MODE_UNCHANGED, 0L,
            new byte[Proto.PATCH_PRESENCE_BYTES - 1], new byte[0]
        );
        assertThrows(ProtoException.class, () -> MsgCodec.encode(msg));
    }

    @Test
    void decodeRejectsTooManyTiles() throws ProtoException {
        // Build a MAP_VIEW_REQ by hand with a tile count above the cap.
        final byte[] payload = MsgCodec.encode(new MapViewReqC2S(0, 0, 0, List.of()));
        // Patch the tile count byte (offset 1+4+1+1=7: type + reqId + dimIndex + lod).
        payload[7] = (byte) (Proto.MAX_TILES_PER_REQ + 1);
        assertThrows(ProtoException.class, () -> MsgCodec.decode(payload));
    }

    // ---- Fuzz ----

    @Test
    void fuzzRoundTripsEveryTypeHundredsOfIterations() throws ProtoException {
        final Random rng = new Random(20260718L);
        for (int i = 0; i < 500; i++) {
            final Message original = randomMessage(rng);
            final byte[] encoded = MsgCodec.encode(original);
            final Message decoded = MsgCodec.decode(encoded);
            assertMessagesEqual(original, decoded, "iteration " + i + " failed for " + original.getClass().getSimpleName());
        }
    }

    @Test
    void fuzzRandomGarbageDoesNotCrashOrCorrupt() {
        final Random rng = new Random(424242L);
        int valid = 0;
        int rejected = 0;
        for (int i = 0; i < 2_000; i++) {
            final int len = rng.nextInt(64);
            final byte[] garbage = new byte[len];
            rng.nextBytes(garbage);
            try {
                final Message m = MsgCodec.decode(garbage);
                // If it decoded, it must re-encode without exception and to the same bytes.
                final byte[] reEncoded = MsgCodec.encode(m);
                final Message m2 = MsgCodec.decode(reEncoded);
                assertMessagesEqual(m, m2, "garbage iteration " + i);
                valid++;
            } catch (final ProtoException | NegativeArraySizeException | OutOfMemoryError e) {
                // NegativeArraySizeException / OOM must NEVER leak from a hostile payload; the
                // test's job here is to surface them. ProtoException is the expected rejection.
                if (e instanceof ProtoException) {
                    rejected++;
                } else {
                    throw new AssertionError("iteration " + i + " leaked " + e.getClass().getSimpleName()
                        + " for payload " + Arrays.toString(garbage), e);
                }
            }
        }
        assertEquals(2_000, valid + rejected, "every hostile payload must either decode exactly or be rejected");
        assertTrue(rejected > 0, "fuzz produced no rejections (seed: 424242)");
    }

    /**
     * Records auto-generate {@code equals} using reference-equality for {@code byte[]} fields, so
     * {@link MapPatchS2C} needs field-by-field comparison via {@link Assertions#assertArrayEquals}.
     * Every other message type is composed of primitives and strings, whose record-generated
     * {@code equals} is correct as-is.
     */
    private static void assertMessagesEqual(final Message expected, final Message actual, final String msg) {
        if (expected instanceof final MapPatchS2C e && actual instanceof final MapPatchS2C a) {
            assertEquals(e.reqId(), a.reqId(), msg);
            assertEquals(e.dimIndex(), a.dimIndex(), msg);
            assertEquals(e.lod(), a.lod(), msg);
            assertEquals(e.tileX(), a.tileX(), msg);
            assertEquals(e.tileZ(), a.tileZ(), msg);
            assertEquals(e.mode(), a.mode(), msg);
            assertEquals(e.tileRevision(), a.tileRevision(), msg);
            assertArrayEquals(e.presence(), a.presence(), msg);
            assertArrayEquals(e.body(), a.body(), msg);
        } else {
            assertEquals(expected, actual, msg);
        }
    }

    private static Message randomMessage(final Random rng) {
        switch (rng.nextInt(6)) {
            case 0:
                return new HelloC2S(randomUtf8(rng, 8), randomUtf8(rng, 12));
            case 1:
                return new HelloPolicyS2C(
                    new HelloPolicyS2C.Flags(rng.nextBoolean(), rng.nextBoolean(), rng.nextBoolean()),
                    randomUuidString(rng), "1.17",
                    new HelloPolicyS2C.Budgets(rng.nextInt(1 << 16), rng.nextInt(16) + 1, rng.nextInt(5_000), rng.nextInt(5)),
                    randomDims(rng)
                );
            case 2:
                return new MapViewReqC2S(rng.nextInt(), rng.nextInt(4), rng.nextInt(5), randomTiles(rng));
            case 3: {
                final byte[] presence = new byte[Proto.PATCH_PRESENCE_BYTES];
                rng.nextBytes(presence);
                final int bodyLen = rng.nextInt(32);
                final byte[] body = new byte[bodyLen];
                rng.nextBytes(body);
                return new MapPatchS2C(
                    rng.nextInt(), rng.nextInt(4), rng.nextInt(5), rng.nextInt(), rng.nextInt(),
                    rng.nextInt(4), rng.nextLong(), presence, body
                );
            }
            case 4:
                return new PolicyUpdateS2C(
                    new HelloPolicyS2C.Flags(rng.nextBoolean(), rng.nextBoolean(), rng.nextBoolean()),
                    new HelloPolicyS2C.Budgets(rng.nextInt(1 << 16), rng.nextInt(16) + 1, rng.nextInt(5_000), rng.nextInt(5))
                );
            default:
                return new ErrorS2C(rng.nextInt(5), randomUtf8(rng, 16));
        }
    }

    private static List<MapViewReqC2S.TileReq> randomTiles(final Random rng) {
        final int n = rng.nextInt(Proto.MAX_TILES_PER_REQ) + 1;
        final MapViewReqC2S.TileReq[] tiles = new MapViewReqC2S.TileReq[n];
        for (int i = 0; i < n; i++) {
            tiles[i] = new MapViewReqC2S.TileReq(rng.nextInt(), rng.nextInt(), rng.nextLong());
        }
        return Arrays.asList(tiles);
    }

    private static List<HelloPolicyS2C.DimDescriptor> randomDims(final Random rng) {
        final int n = rng.nextInt(Proto.MAX_DIM_ENTRIES) + 1;
        final HelloPolicyS2C.DimDescriptor[] dims = new HelloPolicyS2C.DimDescriptor[n];
        for (int i = 0; i < n; i++) {
            final boolean predictable = rng.nextBoolean();
            final boolean hasSeed = rng.nextBoolean();
            dims[i] = new HelloPolicyS2C.DimDescriptor(
                "minecraft:dim" + i, "dim" + i, predictable, hasSeed, rng.nextLong()
            );
        }
        return Arrays.asList(dims);
    }

    private static String randomUtf8(final Random rng, final int maxLen) {
        final int len = rng.nextInt(maxLen) + 1;
        final StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) ('a' + rng.nextInt(26)));
        }
        return sb.toString();
    }

    private static String randomUuidString(final Random rng) {
        final byte[] raw = new byte[16];
        rng.nextBytes(raw);
        return java.util.UUID.nameUUIDFromBytes(raw).toString();
    }
}

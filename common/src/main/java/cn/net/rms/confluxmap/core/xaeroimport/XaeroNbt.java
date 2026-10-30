package cn.net.rms.confluxmap.core.xaeroimport;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal reader for the uncompressed named NBT compounds the format embeds as
 * inline block states. Only the shape produced by the vanilla block-state
 * serializer appears in practice (a root compound of strings plus one nested
 * string compound), but every standard tag type must be skippable so unknown
 * payloads cannot derail decoding. NBT strings share {@code writeUTF} framing,
 * so {@link DataInputStream#readUTF()} decodes them directly.
 */
final class XaeroNbt {
    private static final int TAG_END = 0;
    private static final int TAG_BYTE = 1;
    private static final int TAG_SHORT = 2;
    private static final int TAG_INT = 3;
    private static final int TAG_LONG = 4;
    private static final int TAG_FLOAT = 5;
    private static final int TAG_DOUBLE = 6;
    private static final int TAG_BYTE_ARRAY = 7;
    private static final int TAG_STRING = 8;
    private static final int TAG_LIST = 9;
    private static final int TAG_COMPOUND = 10;
    private static final int TAG_INT_ARRAY = 11;
    private static final int TAG_LONG_ARRAY = 12;

    private XaeroNbt() {
    }

    /**
     * Reads one inline block-state compound and maps it to a state reference.
     * The root carries an empty name; the payload keys depend on the save
     * format's major version (see {@link XaeroSaveFormat#renamedStateKeys()}).
     */
    static XaeroBlockState readBlockState(final DataInputStream in, final XaeroSaveFormat format)
            throws IOException {
        final int rootType = in.readUnsignedByte();
        if (rootType != TAG_COMPOUND) {
            throw new XaeroImportException("inline state is not a compound tag: " + rootType);
        }
        skipFully(in, in.readUnsignedShort());
        final Map<String, Object> root = readCompoundPayload(in);
        final String nameKey = format.renamedStateKeys() ? "id" : "Name";
        final String propertyKey = format.renamedStateKeys() ? "properties" : "Properties";
        if (!(root.get(nameKey) instanceof final String blockId) || blockId.isEmpty()) {
            throw new XaeroImportException("inline state compound lacks key " + nameKey);
        }
        if (!(root.get(propertyKey) instanceof final Map<?, ?> properties)) {
            return new XaeroBlockState(blockId, Map.of());
        }
        final Map<String, String> props = new LinkedHashMap<>();
        for (final Map.Entry<?, ?> entry : properties.entrySet()) {
            if (entry.getKey() instanceof final String key && entry.getValue() instanceof final String value) {
                props.put(key, value);
            } else {
                throw new XaeroImportException("inline state property " + entry.getKey() + " is not a string");
            }
        }
        return new XaeroBlockState(blockId, props);
    }

    /** Values are {@link String} or nested string compounds; other payloads are skipped. */
    private static Map<String, Object> readCompoundPayload(final DataInputStream in) throws IOException {
        final Map<String, Object> values = new LinkedHashMap<>();
        while (true) {
            final int type = in.readUnsignedByte();
            if (type == TAG_END) {
                return values;
            }
            final String name = in.readUTF();
            if (type == TAG_STRING) {
                values.put(name, in.readUTF());
            } else if (type == TAG_COMPOUND) {
                values.put(name, readCompoundPayload(in));
            } else {
                skipPayload(in, type);
            }
        }
    }

    private static void skipPayload(final DataInputStream in, final int type) throws IOException {
        switch (type) {
            case TAG_BYTE -> skipFully(in, 1);
            case TAG_SHORT -> skipFully(in, 2);
            case TAG_INT, TAG_FLOAT -> skipFully(in, 4);
            case TAG_LONG, TAG_DOUBLE -> skipFully(in, 8);
            case TAG_BYTE_ARRAY -> skipFully(in, readArrayLength(in));
            case TAG_STRING -> skipFully(in, in.readUnsignedShort());
            case TAG_LIST -> {
                final int elementType = in.readUnsignedByte();
                final int length = readArrayLength(in);
                for (int i = 0; i < length; i++) {
                    skipPayload(in, elementType);
                }
            }
            case TAG_COMPOUND -> readCompoundPayload(in);
            case TAG_INT_ARRAY -> skipFully(in, 4L * readArrayLength(in));
            case TAG_LONG_ARRAY -> skipFully(in, 8L * readArrayLength(in));
            default -> throw new XaeroImportException("unknown NBT tag type " + type);
        }
    }

    private static int readArrayLength(final DataInputStream in) throws IOException {
        final int length = in.readInt();
        if (length < 0) {
            throw new XaeroImportException("negative NBT array length " + length);
        }
        return length;
    }

    private static void skipFully(final DataInputStream in, final long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            final long skipped = in.skip(remaining);
            if (skipped <= 0) {
                throw new EOFException("truncated NBT payload");
            }
            remaining -= skipped;
        }
    }
}

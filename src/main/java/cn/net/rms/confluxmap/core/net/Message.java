package cn.net.rms.confluxmap.core.net;

/**
 * Common supertype for every message in the {@link Proto#CHANNEL_ID} protocol.
 *
 * <p>Implementations are bare data carriers - no behavior, no validation beyond
 * {@code null}-checking in their constructors. The {@link MsgCodec} enforces all length caps
 * and hostile-input rules; the records just hold whatever the codec accepted.
 *
 * <p>{@link MapPatchS2C} carries a compressed sparse body whose representation is shared with the
 * server summary and client correction codecs.
 *
 * <p>Not {@code sealed} because this subproject targets Java 16 (sealed interfaces are a
 * Java 17 preview). The codec's encode/decode dispatch is exhaustive over the six known
 * subtypes via explicit {@code instanceof} chains; a new message type adds one branch on each side.
 */
public interface Message {
    /** Type id from {@link Proto} ({@code MSG_HELLO_C2S}..{@code MSG_ERROR_S2C}); used by {@link MsgCodec}. */
    int typeId();
}

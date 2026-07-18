package cn.net.rms.confluxmap.core.net;

/**
 * {@code 0x01 C2S HELLO}: first message of the handshake.
 * Client announces its mod version and {@link cn.net.rms.confluxmap.nativepredict.PredictorVersion#full()}
 * string; server uses the predictor-version field to decide whether residual coding is possible
 * against this client (mismatch falls back to absolute mode in {@code PatchBuilder}, not a hard error).
 *
 * @param modVersion       friendly mod version, e.g. {@code "0.2.0"} (≤ {@value Proto#MAX_UTF8_BYTES} UTF-8 bytes)
 * @param predictorVersion wire form of the prediction pipeline identity (cubiomes commit + shim ABI + baseline algo)
 */
public record HelloC2S(String modVersion, String predictorVersion) implements Message {
    @Override
    public int typeId() {
        return Proto.MSG_HELLO_C2S;
    }
}

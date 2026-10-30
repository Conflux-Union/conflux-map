package cn.net.rms.confluxmap.core.xaeroimport;

import java.io.IOException;

/** A region stream that violates the format grammar. Callers quarantine the file and move on. */
public class XaeroImportException extends IOException {
    public XaeroImportException(final String message) {
        super(message);
    }
}

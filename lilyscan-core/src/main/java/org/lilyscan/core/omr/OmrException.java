package org.lilyscan.core.omr;

/** An {@code .omr} project that cannot be read (Python {@code OmrError}). */
public final class OmrException extends IllegalArgumentException {
    public OmrException(String message) {
        super(message);
    }

    public OmrException(String message, Throwable cause) {
        super(message, cause);
    }
}

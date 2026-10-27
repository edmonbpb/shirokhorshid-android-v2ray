package app.whitedns.util;

/**
 * Profile parse failure. Messages are short ASCII text and never contain the encryption key or
 * any part of the submitted payload.
 */
public final class WhiteDnsException extends Exception {

    private static final long serialVersionUID = 1L;

    public WhiteDnsException(String message) {
        super(message);
    }
}

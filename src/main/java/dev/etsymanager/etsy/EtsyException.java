package dev.etsymanager.etsy;

/** Etsy answered with an error. The message is Etsy's own text, passed through unchanged. */
public class EtsyException extends RuntimeException {
    private final int status;

    public EtsyException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}

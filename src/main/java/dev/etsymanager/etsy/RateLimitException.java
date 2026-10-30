package dev.etsymanager.etsy;

public class RateLimitException extends EtsyException {
    public RateLimitException(String message) {
        super(429, message);
    }
}

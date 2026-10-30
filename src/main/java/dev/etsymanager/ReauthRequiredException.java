package dev.etsymanager;

public class ReauthRequiredException extends EtsyException {
    public ReauthRequiredException(String message) {
        super(401, message);
    }
}

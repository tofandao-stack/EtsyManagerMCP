package dev.etsymanager.model;

import java.time.Instant;

public record TokenSet(String accessToken, String refreshToken, Instant expiresAt) {

    @Override
    public String toString() {
        return "TokenSet[redacted]";
    }
}

package dev.etsymanager;

/** Everything the server needs from Etsy. Reads and writes touch only the owner's own shop. */
public interface EtsyClient {

    Shop getShop();

    /** @param state one of active, inactive, draft */
    ListingPage listListings(String state, int limit, int offset);

    Listing getListing(long listingId);

    /** Sends only the non-null fields of {@code edit} and returns the listing as Etsy now holds it. */
    Listing updateListingText(long listingId, TextFields edit);

    /** Trades an authorization code for tokens, persists them and returns them. */
    TokenSet exchangeCode(String code, String codeVerifier);

    /** Uses the stored refresh token to obtain and persist a new token set. */
    TokenSet refreshTokens();
}

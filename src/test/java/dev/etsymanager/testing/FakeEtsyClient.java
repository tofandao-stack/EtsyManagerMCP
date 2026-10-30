package dev.etsymanager.testing;

import dev.etsymanager.etsy.EtsyClient;
import dev.etsymanager.etsy.EtsyException;
import dev.etsymanager.model.Listing;
import dev.etsymanager.model.ListingPage;
import dev.etsymanager.model.Shop;
import dev.etsymanager.model.TextFields;
import dev.etsymanager.model.TokenSet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory Etsy with synthetic listings; records every write it receives. */
public class FakeEtsyClient implements EtsyClient {
    public final Map<Long, Listing> listings = new LinkedHashMap<>();
    public final List<Map.Entry<Long, TextFields>> writes = new ArrayList<>();
    public EtsyException failNextWrite;

    public FakeEtsyClient add(Listing l) {
        listings.put(l.id(), l);
        return this;
    }

    @Override
    public Shop getShop() {
        long active = listings.values().stream().filter(l -> l.state().equals("active")).count();
        return new Shop(42, "SyntheticShop", "Handmade synthetic things", (int) active);
    }

    @Override
    public ListingPage listListings(String state, int limit, int offset) {
        List<Listing> inState = listings.values().stream().filter(l -> l.state().equals(state)).toList();
        int from = Math.min(offset, inState.size());
        int to = Math.min(from + limit, inState.size());
        return new ListingPage(inState.size(), inState.subList(from, to));
    }

    @Override
    public Listing getListing(long listingId) {
        Listing l = listings.get(listingId);
        if (l == null) throw new EtsyException(404, "Listing " + listingId + " not found");
        return l;
    }

    @Override
    public Listing updateListingText(long listingId, TextFields edit) {
        if (failNextWrite != null) {
            EtsyException e = failNextWrite;
            failNextWrite = null;
            throw e;
        }
        Listing old = getListing(listingId);
        writes.add(Map.entry(listingId, edit));
        Listing updated = new Listing(
                listingId,
                edit.title() != null ? edit.title() : old.title(),
                edit.description() != null ? edit.description() : old.description(),
                edit.tags() != null ? edit.tags() : old.tags(),
                edit.materials() != null ? edit.materials() : old.materials(),
                old.state(), old.views(), old.favorites());
        listings.put(listingId, updated);
        return updated;
    }

    @Override
    public TokenSet exchangeCode(String code, String codeVerifier) {
        throw new UnsupportedOperationException();
    }

    @Override
    public TokenSet refreshTokens() {
        throw new UnsupportedOperationException();
    }
}

package dev.etsymanager.model;

import java.util.List;

/** One page of listings; {@code totalCount} is the number of listings in the state, across all pages. */
public record ListingPage(int totalCount, List<Listing> listings) {}

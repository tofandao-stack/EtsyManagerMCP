package dev.etsymanager;

import java.util.List;

/** A listing as read from Etsy: the four editable text fields plus read-only context. */
public record Listing(
        long id,
        String title,
        String description,
        List<String> tags,
        List<String> materials,
        String state,
        int views,
        int favorites) {

    public Listing {
        tags = List.copyOf(tags);
        materials = List.copyOf(materials);
    }

    public TextFields text() {
        return new TextFields(title, description, tags, materials);
    }
}

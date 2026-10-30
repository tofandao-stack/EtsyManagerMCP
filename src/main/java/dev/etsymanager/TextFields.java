package dev.etsymanager;

import java.util.List;

/**
 * The four listing fields the server is allowed to edit. In an edit, a null component means
 * "leave this field alone"; in a snapshot all components are set.
 */
public record TextFields(String title, String description, List<String> tags, List<String> materials) {

    public boolean isEmpty() {
        return title == null && description == null && tags == null && materials == null;
    }
}

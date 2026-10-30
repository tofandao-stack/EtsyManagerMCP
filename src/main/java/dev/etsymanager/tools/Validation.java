package dev.etsymanager.tools;

import dev.etsymanager.model.TextFields;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Local, warn-only checks against the limits commonly cited for Etsy listings. They are assumptions,
 * not guarantees: Etsy's own response is the authority, so these never block a write.
 */
final class Validation {
    static final int MAX_TITLE = 140;
    static final int MAX_TAGS = 13;
    static final int MAX_TAG_LENGTH = 20;

    // Per the character rules in Etsy's published listing spec.
    private static final Pattern TITLE_DISALLOWED = Pattern.compile("[^\\p{L}\\p{Nd}\\p{P}\\p{Sm}\\p{Zs}™©®]");
    private static final Pattern TAG_DISALLOWED = Pattern.compile("[^\\p{L}\\p{Nd}\\p{Zs}\\-'™©®]");
    private static final Pattern MATERIAL_DISALLOWED = Pattern.compile("[^\\p{L}\\p{Nd}\\p{Zs}]");
    private static final String SINGLE_USE_IN_TITLE = "%:&+";

    private Validation() {}

    static List<String> warnings(TextFields edit) {
        List<String> w = new ArrayList<>();
        if (edit.title() != null) {
            String t = edit.title();
            if (t.codePointCount(0, t.length()) > MAX_TITLE) {
                w.add("title is " + t.codePointCount(0, t.length()) + " characters; the assumed limit is " + MAX_TITLE);
            }
            disallowed(w, "title", t, TITLE_DISALLOWED);
            for (char c : SINGLE_USE_IN_TITLE.toCharArray()) {
                if (t.chars().filter(x -> x == c).count() > 1) {
                    w.add("title uses '" + c + "' more than once; Etsy allows it only once in a title");
                }
            }
        }
        if (edit.tags() != null) {
            if (edit.tags().size() > MAX_TAGS) {
                w.add("there are " + edit.tags().size() + " tags; the assumed limit is " + MAX_TAGS + " tags");
            }
            for (String tag : edit.tags()) {
                if (tag.codePointCount(0, tag.length()) > MAX_TAG_LENGTH) {
                    w.add("tag '" + tag + "' is longer than " + MAX_TAG_LENGTH + " characters");
                }
                disallowed(w, "tag '" + tag + "'", tag, TAG_DISALLOWED);
            }
        }
        if (edit.materials() != null) {
            for (String m : edit.materials()) disallowed(w, "material '" + m + "'", m, MATERIAL_DISALLOWED);
        }
        return w;
    }

    private static void disallowed(List<String> w, String what, String text, Pattern bad) {
        var seen = new java.util.LinkedHashSet<String>();
        bad.matcher(text).results().forEach(m -> seen.add(m.group()));
        for (String c : seen) w.add(what + " contains '" + c + "', which Etsy does not allow in this field");
    }
}

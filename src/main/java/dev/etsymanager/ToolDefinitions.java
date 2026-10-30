package dev.etsymanager;

import java.util.List;

/** Names, descriptions and input schemas of the tools the server exposes. There is deliberately no delete tool. */
final class ToolDefinitions {
    record Def(String name, String description, String inputSchema) {}

    private static final String LISTING_ID = "\"listing_id\":{\"type\":\"integer\",\"description\":\"Etsy listing id\"}";
    private static final String CONFIRM = "\"confirm\":{\"type\":\"boolean\",\"description\":"
            + "\"Must be true to apply. Without it the call is a preview and changes nothing.\"}";
    private static final String REASON = "\"reason\":{\"type\":\"string\",\"description\":"
            + "\"Required when confirm is true: the hypothesis behind this change.\"}";

    static final List<Def> ALL = List.of(
            new Def("get_shop",
                    "Return the owner's shop id, name and active listing count.",
                    "{\"type\":\"object\",\"properties\":{}}"),
            new Def("list_listings",
                    "List the shop's listings in one state, one page at a time. Follow next_offset until it is null.",
                    "{\"type\":\"object\",\"properties\":{"
                            + "\"state\":{\"type\":\"string\",\"enum\":[\"active\",\"inactive\",\"draft\"],\"default\":\"active\"},"
                            + "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":100,\"default\":25},"
                            + "\"offset\":{\"type\":\"integer\",\"minimum\":0,\"default\":0}}}"),
            new Def("get_listing",
                    "Return a listing's title, description, tags, materials, state, views and favorites.",
                    "{\"type\":\"object\",\"properties\":{" + LISTING_ID + "},\"required\":[\"listing_id\"]}"),
            new Def("update_listing_text",
                    "Edit a listing's title, tags, description and/or materials. Fields you omit are left untouched. "
                            + "By default returns a preview (old vs new, plus warnings) and changes nothing; "
                            + "pass confirm: true with a non-empty reason to apply. Every applied change is logged.",
                    "{\"type\":\"object\",\"properties\":{" + LISTING_ID + ","
                            + "\"title\":{\"type\":\"string\"},"
                            + "\"description\":{\"type\":\"string\"},"
                            + "\"tags\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},"
                            + "\"materials\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},"
                            + CONFIRM + "," + REASON + "},\"required\":[\"listing_id\"]}"),
            new Def("list_changes",
                    "List logged changes, optionally for one listing and/or within a date range (ISO-8601 dates or instants).",
                    "{\"type\":\"object\",\"properties\":{" + LISTING_ID + ","
                            + "\"since\":{\"type\":\"string\"},\"until\":{\"type\":\"string\"}}}"),
            new Def("get_listing_history",
                    "Show a listing's logged changes with the views and favorites recorded at each change, "
                            + "plus the current counts, to judge whether an edit helped. Views update once a day.",
                    "{\"type\":\"object\",\"properties\":{" + LISTING_ID + "},\"required\":[\"listing_id\"]}"),
            new Def("restore_listing",
                    "Reapply the previous text recorded in a logged change. Same preview/confirm/reason rules as "
                            + "update_listing_text, and the restore is itself logged. Defaults to the listing's latest change.",
                    "{\"type\":\"object\",\"properties\":{" + LISTING_ID + ","
                            + "\"change_id\":{\"type\":\"integer\",\"description\":\"Change whose previous version to restore\"},"
                            + CONFIRM + "," + REASON + "},\"required\":[\"listing_id\"]}"));

    private ToolDefinitions() {}

    static List<String> names() {
        return ALL.stream().map(Def::name).toList();
    }
}

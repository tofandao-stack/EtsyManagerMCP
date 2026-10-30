package dev.etsymanager;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** The tool layer: JSON arguments in, JSON result out. Knows nothing about MCP transport. */
public class ToolHandlers {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> LISTING_STATES = Set.of("active", "inactive", "draft");
    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 100;

    private final EtsyClient etsy;
    private final ChangeLog changeLog;

    public ToolHandlers(EtsyClient etsy, ChangeLog changeLog) {
        this.etsy = etsy;
        this.changeLog = changeLog;
    }

    public ToolResult call(String tool, JsonNode args) {
        try {
            return new ToolResult(dispatch(tool, args == null ? JSON.createObjectNode() : args), false);
        } catch (RuntimeException e) {
            return error(e.getMessage());
        }
    }

    private JsonNode dispatch(String tool, JsonNode args) {
        return switch (tool) {
            case "get_shop" -> getShop();
            case "list_listings" -> listListings(args);
            case "get_listing" -> getListing(args);
            case "update_listing_text" -> updateListingText(args);
            case "list_changes" -> listChanges(args);
            case "get_listing_history" -> getListingHistory(args);
            case "restore_listing" -> restoreListing(args);
            default -> throw new IllegalArgumentException("Unknown tool: " + tool);
        };
    }

    private JsonNode getShop() {
        Shop s = etsy.getShop();
        ObjectNode o = JSON.createObjectNode();
        o.put("shop_id", s.id());
        o.put("name", s.name());
        o.put("title", s.title());
        o.put("active_listing_count", s.activeListingCount());
        return o;
    }

    private JsonNode listListings(JsonNode args) {
        String state = args.path("state").asString("active");
        if (!LISTING_STATES.contains(state)) {
            throw new IllegalArgumentException("state must be one of active, inactive, draft");
        }
        int limit = Math.max(1, Math.min(args.path("limit").asInt(DEFAULT_LIMIT), MAX_LIMIT));
        int offset = Math.max(0, args.path("offset").asInt(0));
        ListingPage page = etsy.listListings(state, limit, offset);
        ObjectNode o = JSON.createObjectNode();
        o.put("total_count", page.totalCount());
        ArrayNode items = o.putArray("listings");
        for (Listing l : page.listings()) {
            ObjectNode i = items.addObject();
            i.put("listing_id", l.id());
            i.put("title", l.title());
            i.put("state", l.state());
            i.put("views", l.views());
            i.put("favorites", l.favorites());
        }
        int next = offset + page.listings().size();
        if (next < page.totalCount() && !page.listings().isEmpty()) o.put("next_offset", next);
        else o.putNull("next_offset");
        return o;
    }

    private JsonNode getListing(JsonNode args) {
        return listingJson(etsy.getListing(requireListingId(args)));
    }

    private JsonNode updateListingText(JsonNode args) {
        long id = requireListingId(args);
        TextFields edit = new TextFields(
                optionalString(args, "title"),
                optionalString(args, "description"),
                optionalStrings(args, "tags"),
                optionalStrings(args, "materials"));
        if (edit.isEmpty()) {
            throw new IllegalArgumentException("Give at least one of title, tags, description or materials");
        }
        return proposeOrApply(etsy.getListing(id), edit, args, "update");
    }

    private JsonNode listChanges(JsonNode args) {
        Long listingId = args.path("listing_id").isIntegralNumber() ? args.path("listing_id").asLong() : null;
        List<ChangeEntry> entries = changeLog.list(
                listingId, parseInstant(args, "since", false), parseInstant(args, "until", true));
        ObjectNode o = JSON.createObjectNode();
        o.set("changes", changesJson(entries));
        return o;
    }

    private JsonNode getListingHistory(JsonNode args) {
        long id = requireListingId(args);
        Listing current = etsy.getListing(id);
        ObjectNode o = JSON.createObjectNode();
        o.put("listing_id", id);
        o.set("changes", changesJson(changeLog.list(id, null, null)));
        ObjectNode now = o.putObject("current");
        now.put("title", current.title());
        now.put("views", current.views());
        now.put("favorites", current.favorites());
        o.put("note", "Views update once a day; allow several days between changes before comparing.");
        return o;
    }

    private JsonNode restoreListing(JsonNode args) {
        long id = requireListingId(args);
        List<ChangeEntry> entries = changeLog.list(id, null, null);
        ChangeEntry target;
        if (args.path("change_id").isIntegralNumber()) {
            int changeId = args.path("change_id").asInt();
            target = changeLog.list(null, null, null).stream()
                    .filter(e -> e.id() == changeId).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("No logged change with id " + changeId));
            if (target.listingId() != id) {
                throw new IllegalArgumentException("Change " + changeId + " belongs to listing " + target.listingId());
            }
        } else if (entries.isEmpty()) {
            throw new IllegalArgumentException("No logged change for listing " + id + " to restore");
        } else {
            target = entries.get(entries.size() - 1);
        }
        return proposeOrApply(etsy.getListing(id), target.previous(), args, "restore");
    }

    private static ArrayNode changesJson(List<ChangeEntry> entries) {
        ArrayNode arr = JSON.createArrayNode();
        for (ChangeEntry e : entries) arr.add(JSON.valueToTree(e));
        return arr;
    }

    /** Accepts an ISO instant or a plain date; a plain {@code until} date includes that whole day. */
    private static Instant parseInstant(JsonNode args, String field, boolean endOfDay) {
        String v = optionalString(args, field);
        if (v == null || v.isBlank()) return null;
        try {
            if (v.length() <= 10) {
                LocalDate d = LocalDate.parse(v);
                return (endOfDay ? d.plusDays(1) : d).atStartOfDay(ZoneOffset.UTC).toInstant();
            }
            return Instant.parse(v);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " must be an ISO-8601 date or instant, got '" + v + "'");
        }
    }

    /**
     * Shared write contract: diff against the live listing, preview unless confirmed, require a reason,
     * send only changed fields, and log the result.
     */
    private JsonNode proposeOrApply(Listing current, TextFields requested, JsonNode args, String kind) {
        TextFields edit = onlyChanged(current, requested);
        ObjectNode out = JSON.createObjectNode();
        out.put("listing_id", current.id());
        if (edit.isEmpty()) {
            out.put("mode", "no_changes");
            out.put("message", "Every requested value already matches the live listing; nothing to do.");
            return out;
        }
        ObjectNode changes = out.putObject("changes");
        addChange(changes, "title", current.title(), edit.title());
        addChange(changes, "description", current.description(), edit.description());
        addChange(changes, "tags", current.tags(), edit.tags());
        addChange(changes, "materials", current.materials(), edit.materials());
        ArrayNode warnings = out.putArray("warnings");
        Validation.warnings(edit).forEach(warnings::add);

        if (!args.path("confirm").asBoolean(false)) {
            out.put("mode", "preview");
            out.put("message", "Preview only; nothing was changed. Call again with confirm: true and a reason to apply.");
            return out;
        }
        String reason = args.path("reason").asString("").strip();
        if (reason.isEmpty()) {
            throw new IllegalArgumentException("A non-empty reason is required to confirm a change");
        }
        Listing updated = etsy.updateListingText(current.id(), edit);
        ChangeEntry entry = changeLog.append(Instant.now(), current.id(), kind, reason,
                current.text(), updated.text(), current.views(), current.favorites());
        out.put("mode", "applied");
        out.put("change_id", entry.id());
        return out;
    }

    private static TextFields onlyChanged(Listing cur, TextFields req) {
        return new TextFields(
                differs(cur.title(), req.title()),
                differs(cur.description(), req.description()),
                differs(cur.tags(), req.tags()),
                differs(cur.materials(), req.materials()));
    }

    private static <T> T differs(T current, T requested) {
        return requested == null || requested.equals(current) ? null : requested;
    }

    private static void addChange(ObjectNode changes, String field, Object old, Object updated) {
        if (updated == null) return;
        ObjectNode c = changes.putObject(field);
        c.set("old", JSON.valueToTree(old));
        c.set("new", JSON.valueToTree(updated));
    }

    private static String optionalString(JsonNode args, String field) {
        JsonNode n = args.path(field);
        if (n.isMissingNode() || n.isNull()) return null;
        if (!n.isString()) throw new IllegalArgumentException(field + " must be a string");
        return n.asString();
    }

    private static List<String> optionalStrings(JsonNode args, String field) {
        JsonNode n = args.path(field);
        if (n.isMissingNode() || n.isNull()) return null;
        if (!n.isArray()) throw new IllegalArgumentException(field + " must be an array of strings");
        List<String> out = new ArrayList<>();
        for (JsonNode item : n) {
            if (!item.isString()) throw new IllegalArgumentException(field + " must be an array of strings");
            out.add(item.asString());
        }
        return out;
    }

    static long requireListingId(JsonNode args) {
        JsonNode id = args.path("listing_id");
        if (!id.isIntegralNumber()) throw new IllegalArgumentException("listing_id is required");
        return id.asLong();
    }

    static ObjectNode listingJson(Listing l) {
        ObjectNode o = JSON.createObjectNode();
        o.put("listing_id", l.id());
        o.put("title", l.title());
        o.put("description", l.description());
        o.set("tags", JSON.valueToTree(l.tags()));
        o.set("materials", JSON.valueToTree(l.materials()));
        o.put("state", l.state());
        o.put("views", l.views());
        o.put("favorites", l.favorites());
        return o;
    }

    private static ToolResult error(String message) {
        ObjectNode o = JSON.createObjectNode();
        o.put("error", message);
        return new ToolResult(o, true);
    }
}

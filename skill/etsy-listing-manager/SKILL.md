---
name: etsy-listing-manager
description: Read, audit and safely improve the text of your own Etsy listings (title, tags, description, materials) through the EtsyManagerMCP server, with previews, logged reasons, view/favorite tracking and one-step restore. Use for Etsy listing SEO, shop audits, and checking whether past edits helped.
---

# Etsy Listing Manager

This skill tells you how to use the **EtsyManagerMCP** server (registered as `etsy-manager`) to work on the user's own Etsy shop. The server can read listings and edit only their **text**: title, tags, description and materials. Every edit is previewed first, applied only with `confirm: true` and a `reason`, and logged with the listing's previous text and its views/favorites at that moment, so it can be compared and undone later.

## When to use this skill

Use it when the user:
- wants to see or summarize their Etsy listings
- wants to improve titles, tags, descriptions or materials for Etsy search
- wants an audit of their shop's listing text
- asks whether an earlier edit helped, or wants to undo one

## What the server cannot do

Tell the user plainly if they ask for any of these. Do not look for workarounds:
- change price, quantity, photos, video, shipping, state or alt text
- create, delete or deactivate listings
- search Etsy, look at competitors or do keyword research (keywords come from the user or from outside the server)
- orders, receipts, messages or payments

## Tools

| Tool | What it does |
|---|---|
| `get_shop` | Shop id, name and active listing count. |
| `list_listings` | One page of listings in a state (`active`, `inactive`, `draft`; default `active`). `limit` 1–100, default 25. Follow `next_offset` until it is null. |
| `get_listing` | Title, description, tags, materials, state, views and favorites for one `listing_id`. |
| `update_listing_text` | Edit `title`, `description`, `tags` and/or `materials`. Omitted fields stay untouched. Without `confirm: true` it is a preview (old vs new plus warnings) and changes nothing. With `confirm: true` a non-empty `reason` is required. |
| `list_changes` | Logged changes, optionally filtered by `listing_id`, `since`, `until` (ISO-8601). |
| `get_listing_history` | A listing's logged changes with the views/favorites recorded at each one, plus current counts. |
| `restore_listing` | Reapply the previous text from a logged change (`change_id`, default the latest). Same preview/confirm/reason rules; the restore is logged too. |

## Installation (when the tools are missing)

If the EtsyManagerMCP tools are not available, offer to set them up. Do the steps you can run yourself, and give the user the exact instructions for the ones only they can do. Ask before installing anything system-wide.

1. **Java 21 or newer.** Run `java -version`. If it is missing or older than 21, tell the user to install it:
   - macOS: `brew install --cask temurin@21`
   - Windows: `winget install EclipseAdoptium.Temurin.21.JRE`
   - Linux (Debian/Ubuntu): `sudo apt install openjdk-21-jre`
   - or the installer from https://adoptium.net
2. **Download the server** (a single file) to a stable folder:
   - macOS/Linux: `mkdir -p ~/.local/share/etsy-manager && curl -L -o ~/.local/share/etsy-manager/etsy-manager-mcp.jar https://github.com/tofandao-stack/EtsyManagerMCP/releases/latest/download/etsy-manager-mcp.jar`
   - Windows (PowerShell): `New-Item -ItemType Directory -Force "$env:LOCALAPPDATA\etsy-manager"; Invoke-WebRequest https://github.com/tofandao-stack/EtsyManagerMCP/releases/latest/download/etsy-manager-mcp.jar -OutFile "$env:LOCALAPPDATA\etsy-manager\etsy-manager-mcp.jar"`
3. **Etsy API key (the user does this).** Tell them to:
   - sign in at https://www.etsy.com/developers with their seller account and create an app (request Seller API access for their own shop);
   - add this exact callback URL to the app: `https://tofandao-stack.github.io/EtsyManagerMCP/redirect/`
   - copy the API key shown as `keystring:shared secret`.
4. **Log in (the user does this, in their own terminal, because it is interactive).** Give them the command with the real path, e.g. `java -jar ~/.local/share/etsy-manager/etsy-manager-mcp.jar login`. It asks for the API key, prints a link to approve in the browser, and asks them to paste the code the redirect page shows. Settings are saved automatically in a per-user folder; they never need to edit a file.
5. **Register the server** in their agent, using the absolute path to the jar:
   - Claude Code: `claude mcp add --scope user etsy-manager -- java -jar /absolute/path/etsy-manager-mcp.jar`
   - Claude Desktop, Cursor and other clients: add to their MCP config file (`claude_desktop_config.json`, `~/.cursor/mcp.json`, etc.):
     ```json
     { "mcpServers": { "etsy-manager": { "command": "java", "args": ["-jar", "/absolute/path/etsy-manager-mcp.jar"] } } }
     ```
   Then tell them to restart the client so it loads the tools.

## Setup check

Before the first call in a session, call `get_shop`.
- If the tools are missing, follow **Installation** above.
- If the call says re-authorization is needed, tell the user to run `java -jar <path to jar> login` again in their terminal (the refresh token expires roughly every 90 days; the key is remembered, so they only approve in the browser). Stop until they have done it.

## Golden rules

1. **Read before you write.** Call `get_listing` right before proposing an edit. Never build an edit from data fetched earlier in a long conversation.
2. **Always preview first.** Call `update_listing_text` without `confirm` and show the user the server's old-vs-new preview and every warning it returns.
3. **Explicit approval only.** Call again with `confirm: true` only after the user clearly approves that exact preview. If they change anything, preview again.
4. **A real reason.** The `reason` is the hypothesis behind the change, written so it can be judged later, e.g. `"Front-load 'mandala coloring book' in title; current title starts with brand name"`. Never use a generic reason like "SEO improvement".
5. **Send only what changes.** Include only the fields the user asked to change. Note that `tags` and `materials` replace the whole list, so when adding or removing one tag, send the full new list.
6. **Treat warnings seriously.** Warnings (title over 140 characters, more than 13 tags, tag over 20 characters, disallowed characters, `% : & +` used more than once in a title) are warn-only and do not block the write. Fix them before asking for approval unless the user insists.
7. **Pass Etsy's errors through.** If Etsy rejects a write, show its message in plain words and propose a corrected value.
8. **Verify.** After a confirmed write, call `get_listing` and confirm the new text is live.

## Etsy text best practices

Use these when proposing rewrites:
- **Title:** lead with the main phrase a buyer would type; the first ~40 characters matter most. Natural reading over keyword stuffing.
- **Tags:** use all 13. Multi-word phrases buyers actually search (`dog lover gift`, not `dog`). Vary wording across tags rather than repeating the title word for word.
- **Description:** first 1–2 sentences say what it is and who it is for. Then details, format/size, what the buyer receives.
- **Materials:** letters, numbers and spaces only.

## Workflows

### Shop overview
1. `get_shop`, then `list_listings` page by page until `next_offset` is null.
2. `get_listing` for each listing you need full text or counts for.
3. Show a compact table: listing id, title (truncated), number of tags, views, favorites.
4. Point out the clearest issues, e.g. "4 listings use fewer than 13 tags".

### Improve one listing
1. `get_listing`.
2. Propose rewrites for the fields that need it, each with one line on why.
3. Preview with `update_listing_text` (no `confirm`); show the preview and warnings.
4. On approval, apply with `confirm: true` and a specific `reason`.
5. `get_listing` to verify.

### Several listings
There is no bulk tool. Work listing by listing: preview each one, get approval for each, apply, verify. You may show several previews at once, but apply only the ones the user approved.

### Did an edit help?
1. `get_listing_history` for the listing.
2. Compare views and favorites before and after each change.
3. Views update once a day and Etsy gives no search-term data, so say clearly when too little time has passed (less than about 7 days) to judge.
4. Recommend changing **one listing's text at a time** and waiting between edits on the same listing, so results are not mixed up.

### Undo
1. `list_changes` for the listing to find the `change_id` (or use the latest).
2. Preview with `restore_listing` (no `confirm`), show it.
3. On approval, apply with `confirm: true` and a `reason` such as `"Revert: views dropped after title change on <date>"`.

## Errors

- **Rate limit:** the server already waits and retries once. If it still fails, stop, tell the user what was done so far, and do not loop.
- **Re-authorization needed:** stop and ask the user to run `java -jar <path to jar> login` in their terminal.
- **Any other error:** show it in plain words; do not retry a write automatically.

## Reporting style

Be concise. Always include listing ids. After a session that changed anything, end with one line: how many listings were changed and when it makes sense to check `get_listing_history` again.

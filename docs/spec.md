## Problem Statement

I run an Etsy shop that has had no sales so far. I believe my listings' titles, tags, descriptions and materials can be improved, and I want an AI agent to apply those improvements for me. Keyword research is done outside this tool. Today the agent has no way to read my live listings or change them, and I don't trust an agent to edit a live shop without seeing exactly what will change, why, and being able to undo it. Etsy also offers no analytics API, so I can't tell afterwards whether a change helped.

## Solution

A local MCP server, written in Java and run over stdio, that gives an agent controlled access to my own Etsy shop's listings. The agent can read listings and propose text edits (title, tags, description, materials). Every edit is previewed first and only applied when explicitly confirmed with a stated reason. Each confirmed edit is recorded in a local change log together with the listing's previous values and its view and favorite counts at that moment, so I can restore any listing and later compare the numbers. The server never deletes anything and never touches price, quantity, photos, shipping or alt text. It only ever fetches my own shop's data.

## User Stories

1. As a shop owner, I want to log in to Etsy once with the minimum scopes (listings read, listings write, shops read), so that the agent can act on my shop without receiving more access than it needs.
2. As a shop owner, I want a `login` command that prints the Etsy authorization URL, so that I can approve access in my browser.
3. As a shop owner, I want the login to use PKCE, so that the flow meets Etsy's requirements.
4. As a shop owner, I want a static HTTPS redirect page hosted from the repo that shows the returned code, so that I can complete login without running a local HTTPS server.
5. As a shop owner, I want to paste the returned code into the `login` command, so that the server can exchange it for tokens.
6. As a shop owner, I want the tokens written to my local `.env`, so that I don't have to manage a separate store.
7. As a shop owner, I want the server to refresh the access token automatically when it expires, so that the agent never fails on an hourly expiry.
8. As a shop owner, I want only the token lines in `.env` rewritten after a refresh, so that the rest of my configuration is preserved.
9. As a shop owner, I want the `.env` rewrite to be atomic, so that a crash cannot corrupt my credentials.
10. As a shop owner, I want a clear error telling me to run `login` again when the refresh token has expired, so that I know how to recover.
11. As a shop owner, I want `.env`, the change log and any real shop data excluded from git from the first commit, so that nothing sensitive reaches the public repo.
12. As a shop owner, I want a `.env.example` listing the variable names without values, so that setup is documented safely.
13. As an agent, I want a `get_shop` tool, so that I can learn which shop I'm working on and its basic counts.
14. As an agent, I want a `list_listings` tool with a state filter, so that I can find active, inactive and draft listings.
15. As an agent, I want `list_listings` to be paginated, so that I can walk the whole shop regardless of size.
16. As an agent, I want a `get_listing` tool that returns title, description, tags, materials, state, views and favorites, so that I have the full current picture before proposing changes.
17. As an agent, I want an `update_listing_text` tool for title, tags, description and materials only, so that my edits stay within what the owner has allowed.
18. As an agent, I want `update_listing_text` to return a preview showing old against new for each changed field by default, so that the owner can review it.
19. As an agent, I want a preview to make no change to the shop, so that proposing is always safe.
20. As a shop owner, I want a write to execute only when `confirm: true` is passed, so that nothing changes by accident.
21. As a shop owner, I want a confirmed write to be rejected unless it includes a non-empty `reason`, so that every change records the hypothesis behind it.
22. As a shop owner, I want fields the agent did not mention left untouched, so that a tag edit cannot overwrite my description.
23. As a shop owner, I want a warning in the preview when a title is over 140 characters, so that I catch likely rejections early.
24. As a shop owner, I want a warning when there are more than 13 tags or a tag is over 20 characters, so that I catch likely rejections early.
25. As a shop owner, I want a warning when text contains characters Etsy does not allow in that field, or repeats a character that may appear only once in a title, so that errors are caught before the write.
26. As a shop owner, I want validation to warn rather than block, so that valid edits are not refused if Etsy's real limits differ from the assumed ones.
27. As a shop owner, I want Etsy's own rejection message returned to the agent unchanged, so that it can correct the edit.
28. As a shop owner, I want the previous title, tags, description and materials saved before every confirmed write, so that I can undo it.
29. As a shop owner, I want the listing's views and favorites at the time of each write saved in the change log, so that I can compare the numbers later.
30. As a shop owner, I want the change log to include a timestamp and the reason for every entry, so that I can reconstruct my experiments.
31. As an agent, I want a `list_changes` tool with optional listing and date filters, so that I can see what has already been tried.
32. As an agent, I want a `get_listing_history` tool that shows a listing's changes alongside its recorded views and favorites over time, so that I can judge whether an edit helped.
33. As a shop owner, I want a `restore_listing` tool that reapplies a logged previous version, so that I can revert a bad change.
34. As a shop owner, I want `restore_listing` to follow the same preview and confirm rules and to be logged as a change itself, so that a restore is as visible and reversible as any other edit.
35. As a shop owner, I want the server to expose no delete tool, so that the agent cannot remove listings.
36. As a shop owner, I want price, quantity, photos, shipping and alt text to be unreachable through any tool, so that the agent cannot change them.
37. As a shop owner, I want the server to fetch only my own shop's data, with no search or competitor endpoints, so that I stay within the lower-risk reading of Etsy's API terms.
38. As a shop owner, I want the server to wait and retry once on an Etsy rate-limit response, so that brief limits do not fail the agent's task.
39. As a shop owner, I want a persistent rate-limit error returned to the agent with a clear message, so that it can stop rather than loop.
40. As a shop owner, I want secrets never written to logs or returned in tool output, so that the agent and any transcripts never contain my tokens.
41. As a shop owner, I want to register the server through a plain `java -jar` entry in my MCP configuration, so that I can use it from Claude Code and Claude Desktop.
42. As a developer, I want the whole tool layer to be testable without Etsy approval or network access, so that I can build while the app application is pending.
43. As a developer, I want an opt-in live-API test profile, so that I can verify real behaviour once the app is approved without ever running it in routine builds.

## Implementation Decisions

- **Language and build:** Java 21, Maven, official MCP Java SDK over stdio. The Etsy client uses the JDK's built-in HTTP client and Jackson. No Spring. The build produces one runnable fat jar via the Shade plugin.
- **Distribution:** Local only. Not published to a package registry. The repository is public.
- **Scope of access:** The scopes requested are `listings_r`, `listings_w` and `shops_r`. The delete scope is never requested.
- **Authentication:** OAuth2 authorization code flow with PKCE (S256). Etsy requires an HTTPS redirect URI that matches the registered one exactly. The redirect is a single static page served from the repo through GitHub Pages that displays the code. A `login` command prints the authorization URL, reads the pasted code and exchanges it. Re-login is needed roughly every 90 days, when the refresh token expires.
- **Token storage:** Access and refresh tokens live in the local `.env`. After each refresh only the token lines are rewritten, atomically (temporary file then rename). This handles refresh-token rotation, which is unverified and must be checked once the app is approved. Refresh uses the `refresh_token` grant, not the `refresh_code` spelling that appears in one Etsy example.
- **Etsy client:** A thin interface with a real implementation and a fake. It exposes shop lookup, listing list and detail, listing text update, and token operations. Every request carries the API key header; private calls also carry the bearer token.
- **Rate limiting:** On a 429 the client waits for the `retry-after` period and retries once, then surfaces the error. Etsy's actual per-app limits are visible only in the developer portal and are not hard-coded.
- **Tools:** `get_shop`, `list_listings` (state filter, pagination), `get_listing`, `update_listing_text`, `list_changes`, `get_listing_history`, `restore_listing`. No bulk update (the shop is expected to be small; revisit if it is not).
- **Write contract:** `update_listing_text` accepts title, tags, description and materials; omitted fields are not sent. Without `confirm: true` it returns a preview and changes nothing. A confirmed call requires a non-empty `reason`. `restore_listing` follows the same contract and is itself logged.
- **Change log:** A local JSON file next to `.env`, gitignored. Each entry holds the timestamp, listing identifier, reason, previous values of the four editable fields, the new values, and the listing's views and favorite count at write time. Views update once a day, so comparisons need several days between changes.
- **Validation:** Local, warn-only checks in the preview against the commonly cited limits (140-character title, 13 tags, 20 characters per tag) and the per-field character rules from Etsy's published spec. These limits are not confirmed by the spec; Etsy's response is the authority and its error text is passed through.
- **Read-only fields:** Price, quantity, photos, shipping, state and alt text are not editable. Alt text is excluded because the listing update call does not accept it.
- **Data policy:** Only the owner's own shop data is fetched. No search, competitor or keyword endpoints. The owner will read Etsy's current API terms, which could not be read during design, and confirm that passing their own listings to an AI agent is acceptable.
- **Secrets and the public repo:** `.env`, the change log and any real shop data are gitignored from the first commit. A `.env.example` documents the variable names. Fixtures are synthetic. Secrets are never logged or returned in tool output.

## Testing Decisions

- **What makes a good test:** It exercises external behaviour only: given tool arguments and the state of a fake or stubbed Etsy, it checks the returned result, what was sent to Etsy, and what was recorded. It does not assert on internal classes, method calls or log wording.
- **Seam A, the tool layer (primary):** Tests call the tool handlers with JSON arguments and check JSON results. The Etsy client is a fake behind its interface and the change log writes to a temporary directory. This covers preview against confirm, the required reason, untouched fields, warn-only validation, change-log contents, history, restore, and the absence of any delete or out-of-scope edit.
- **Seam B, the Etsy HTTP boundary (secondary):** The real Etsy client talks to a local stub HTTP server serving synthetic fixtures. This covers the code exchange, token refresh and rotation, the atomic `.env` rewrite, 429 retry, header and form encoding, error pass-through, and pagination.
- **Live tests:** An opt-in Maven profile against the real API, run only by the owner once the app is approved. Never part of the default build.
- **Framework:** JUnit 5.
- **Prior art:** None. The repository has no code yet.

## Out of Scope

- Keyword research and any competitor or market analysis.
- Etsy search, competitor and public-listing endpoints.
- Orders, receipts, transactions, customer messaging and fulfilment.
- Creating or deleting listings.
- Price, quantity, photo, video, shipping and alt-text changes.
- Bulk updates in v1.
- Multi-shop or multi-user support.
- Publishing the server to a package registry or hosting it remotely.
- An in-server analytics feature beyond the recorded views and favorites snapshots.

## Further Notes

- **Approval risk:** The Etsy seller app must be registered by the owner. Etsy has said it is revisiting its approval process, there is no official timeline, and developer reports range from about three weeks to outright denial. Application details should not be posted publicly. Until approval everything is built and tested against mocks.
- **Unverified assumptions to check after approval:** Etsy's actual title, tag and character limits; whether the refresh token rotates on refresh; and the real rate limits shown in the developer portal.
- **API terms:** An archived version of Etsy's terms restricts using API data for machine learning without authorization. The owner must read the current terms. Sending only the owner's own shop data to the agent is the lower-risk design.
- **Experiment hygiene:** Views update once a day and Etsy provides no visit or search-term data, so the change log is the only record for judging whether an edit worked. Changing one listing's text at a time gives the cleanest comparison.

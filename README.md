# Etsy Manager MCP

A local MCP server (Java 21, stdio) that lets an AI agent read and carefully edit the **text** of your own
Etsy shop's listings: title, tags, description, materials. Every edit is previewed, applied only with
`confirm: true` and a `reason`, and logged with the previous values and the listing's views/favorites so it
can be restored and compared later. It never deletes anything and cannot touch price, quantity, photos,
shipping or alt text. It only fetches your own shop.

## Setup

1. Register an Etsy app (scopes used: `listings_r`, `listings_w`, `shops_r`). Host `docs/redirect/` with GitHub
   Pages and register its HTTPS URL as the redirect URI.
2. `cp .env.example .env` and set `ETSY_API_KEY` and `ETSY_REDIRECT_URI`.
3. Run `./etsy-manager.sh login` (builds the jar if needed) and follow the prompts.
   Repeat roughly every 90 days, when the refresh token expires.
4. Register the server in your MCP configuration:

```json
{
  "mcpServers": {
    "etsy-manager": {
      "command": "/absolute/path/to/etsy-manager.sh"
    }
  }
}
```

The script wraps `java -jar target/etsy-manager-mcp-0.1.0.jar`, which also works directly. `ETSY_ENV_FILE` defaults to `<repo>/.env` in the script and `./.env` for the bare jar. The change log is `change-log.json` next to it. Both are gitignored.

## Layout

`dev.etsymanager` has `model` (listing data types), `etsy` (client interface and HTTP implementation), `storage`
(`.env` and change log), `tools` (tool handlers, definitions, validation), `auth` (login) and `mcp` (SDK wiring).

## Tools

`get_shop`, `list_listings`, `get_listing`, `update_listing_text`, `list_changes`, `get_listing_history`,
`restore_listing`.

## Tests

`mvn test` runs everything offline: the tool layer against a fake Etsy, and the HTTP client against a local
stub server. Tests tagged `live` hit the real API and run only with `mvn test -Plive` once your app is approved.

# Etsy Manager MCP

A local MCP server (Java 21, stdio) that lets an AI agent read and carefully edit the **text** of your own
Etsy shop's listings: title, tags, description, materials. Every edit is previewed, applied only with
`confirm: true` and a `reason`, and logged with the previous values and the listing's views/favorites so it
can be restored and compared later. It never deletes anything and cannot touch price, quantity, photos,
shipping or alt text. It only fetches your own shop.

## Setup

1. Get Etsy API access for your shop (scopes used: `listings_r`, `listings_w`, `shops_r`) and register
   `https://tofandao-stack.github.io/EtsyManagerMCP/redirect/` as your app's callback URL.
2. Run `java -jar etsy-manager-mcp.jar login`. The first time, it asks for your API key ("keystring:shared secret"),
   then prints a link: open it, approve access, and paste the code the redirect page shows.
   Repeat roughly every 90 days, when the refresh token expires (the key is remembered).
3. Settings, tokens and the change log are kept in a per-user folder, so the server finds them however it is started:
   `~/.config/etsy-manager/` on Linux, `~/Library/Application Support/etsy-manager/` on macOS,
   `%APPDATA%\etsy-manager\` on Windows. `ETSY_ENV_FILE` points elsewhere if you need to (the launcher script
   uses `<repo>/.env` for development).
4. Register the server in your MCP configuration:

```json
{
  "mcpServers": {
    "etsy-manager": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/etsy-manager-mcp.jar"]
    }
  }
}
```

### Development

`cp .env.example .env`, fill it in, and use `./etsy-manager.sh login` and `./etsy-manager.sh` as the MCP command;
the script builds the jar when needed and points `ETSY_ENV_FILE` at `<repo>/.env`. A different
`ETSY_REDIRECT_URI` (for example your own GitHub Pages copy of `docs/redirect/`) can be set there.
If `ETSY_REDIRECT_URI` is an ngrok domain, `login` starts the tunnel for you (to `ETSY_LOGIN_PORT`, default
8080) and listens on that port itself, so Etsy's redirect is caught automatically and you only approve in
the browser. Don't run another app on that port or ngrok domain at the same time. If the port is busy, or
after 5 minutes without a redirect, it falls back to asking you to paste the code or the whole redirected
URL. `ETSY_NGROK=0` skips the tunnel; `ETSY_LOGIN_PORT=0` disables the listener. `.env` and `change-log.json`
are gitignored.

## Layout

`dev.etsymanager` has `model` (listing data types), `etsy` (client interface and HTTP implementation), `storage`
(`.env` and change log), `tools` (tool handlers, definitions, validation), `auth` (login) and `mcp` (SDK wiring).

## Tools

`get_shop`, `list_listings`, `get_listing`, `update_listing_text`, `list_changes`, `get_listing_history`,
`restore_listing`.

## Tests

`mvn test` runs everything offline: the tool layer against a fake Etsy, and the HTTP client against a local
stub server. Tests tagged `live` hit the real API and run only with `mvn test -Plive` once your app is approved.

# RelayAPI
Turn any AI chat website into an OpenAI-style API. Add a provider URL → a headless browser finds the text box, sends your message, scrapes the reply.
Deploy: Render → New Web Service → Docker (render.yaml included).

## Persistent storage (important on Render free tier)
Without a database, `data.json` is wiped on every restart. Set these env vars on Render:
- `TURSO_URL` (e.g. `libsql://yourdb-you.turso.io`) and `TURSO_TOKEN` — stores everything in one table `relayapi_kv` (safe to share an existing Turso DB)
- `SECRET_KEY` — any long random string; encrypts saved sessions
- optional `CHROME_PATH` — custom Chromium binary

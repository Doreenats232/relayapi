# RelayAPI
Turn any AI chat website into an OpenAI-style API. Add a provider URL → a headless browser finds the text box, sends your message, scrapes the reply.
Deploy: Render → New Web Service → Docker (render.yaml included).

## Persistent storage (important on Render free tier)
Without a database, `data.json` is wiped on every restart. Set these env vars on Render:
- `TURSO_URL` (e.g. `libsql://yourdb-you.turso.io`) and `TURSO_TOKEN` — stores everything in one table `relayapi_kv` (safe to share an existing Turso DB)
- `SECRET_KEY` — any long random string; encrypts saved sessions
- optional `CHROME_PATH` — custom Chromium binary

## Sign in with Google (optional)
1. console.cloud.google.com → APIs & Services → **OAuth consent screen** (External, fill app name/email, then **Publish app**).
2. **Credentials → Create credentials → OAuth client ID → Web application**. Add your site URL (e.g. `https://relayapi-xxxx.onrender.com`) under **Authorized JavaScript origins**.
3. Copy the Client ID into Render env var `GOOGLE_CLIENT_ID` and redeploy. (No client secret needed.)
Email/password stays available under "Use email & password instead".

## Chats
- Every API key belongs to one provider and keeps one chat open on that site for good. The chat URL is saved in the database, so it is reopened after restarts or idle closes. `model` and any conversation id sent by the client are ignored. A second key on the same provider gets its own chat.
- The dashboard Playground keeps one chat per provider. Messages are kept in the browser, and the "New chat" button is the only way to replace the chat.

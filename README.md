# RelayAPI
Turn any AI chat website into an OpenAI-style API. Add a provider URL → a headless browser finds the text box, sends your message, scrapes the reply.
Deploy: Render → New Web Service → Docker (render.yaml included).

## PC Agent (recommended for ChatGPT / DeepSeek)
Run `agent/` on your own PC: it drives your real Chrome (home IP). You pass any human check once in the visible window.
`cd agent && npm install && node agent.js --server https://YOUR-APP.onrender.com --token TOKEN` — later just run `start-agent.bat`.

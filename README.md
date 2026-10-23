# Seekora

Seekora is a Java 21 / Spring Boot web research and summarization app. For questions, it reads public DuckDuckGo HTML search results, fetches a few public result pages, and returns source links. With an AI provider configured, it generates an answer grounded in those retrieved sources. Without a provider key, it returns a clearly local extractive summary instead. Pasted text is summarized locally.

Spring Boot's web starter runs the app on embedded Tomcat 10.1. The frontend is served from `src/main/resources/static/index.html`. This project builds an executable JAR, not a WAR; copying it into an external Tomcat `webapps` folder will not deploy Seekora.

## Run locally

Requirements: Java 21+, Maven 3.9+, and outbound internet access for public search and page reads.

```powershell
mvn -s maven-settings.xml spring-boot:run
```

Open `http://localhost:8080`, then sign up (a 12+ character password) to use the search and chat; everything under `/api/` except sign-up, log-in, log-out, `/auth/me` and `/status` returns 401 without a session. Run the tests with `mvn -s maven-settings.xml test`. Health status is at `http://localhost:8080/actuator/health`. Maven caches dependencies inside `.m2repo`; conversation data is stored under `./data`.

### Build and deploy

Run `mvn -s maven-settings.xml clean package`, then start the executable with `java -jar target/seekora-0.1.0.jar`. Keep the process running and point a reverse proxy or ngrok at port 8080. There is no external Tomcat deployment directory, WAR, `web.xml`, or `/api/search` mapping in this checkout. The chat API used by the existing page is `POST /api/v1/chat`.

The project uses Spring Boot 3 and embedded Tomcat 10.1 (Jakarta Servlet 6). An external Tomcat 9 installation is incompatible with these servlet APIs; use the executable JAR as shown, or plan a deliberate container migration before targeting Tomcat 9.

## Share locally with ngrok

The ngrok Windows agent is kept in the ignored `.tools/ngrok` folder. Add your ngrok account token in PowerShell (keep the token private):

```powershell
& '.\.tools\ngrok\ngrok.exe' config add-authtoken '<YOUR_NGROK_AUTHTOKEN>'
& '.\.tools\ngrok\ngrok.exe' http 8080
```

Open the HTTPS forwarding URL printed by ngrok. Anyone with the URL can create an account, and an account is required to ask questions. Each account sees only its own saved conversations.

## API

### AI answer provider

The backend accepts any provider implementing the common OpenAI chat-completions API shape. Set its credentials on the server, never in browser code:

```powershell
$env:SEEKORA_AI_API_KEY = '<provider-key>'
$env:SEEKORA_AI_BASE_URL = 'https://api.openai.com/v1' # optional; change for a compatible provider
$env:SEEKORA_AI_MODEL = 'gpt-4o-mini'                  # optional
mvn -s maven-settings.xml spring-boot:run
```

When configured, chat uses fetched source text and recent conversation turns as context. Provider timeouts, authentication/configuration failures, rate limits, and upstream failures return an error status; the service does not store a fabricated success. If no key is configured, chat returns the existing extractive research behavior with an explicit label and the `answerMode` field set to `extractive`; generated answers set it to `ai`. No live provider request is made by the automated tests.

### Web research chat

`POST /api/v1/chat`

```json
{
  "message": "What are the main causes of coral bleaching?",
  "conversationId": null
}
```

The response includes `assistantMessage.content` and a `sources` array containing each page's title, URL, and a short excerpt. Send the returned `conversationId` to retain a conversation history.

### Summarize provided text

`POST /api/v1/summarize`

```json
{
  "text": "Text to summarize goes here.",
  "instruction": "Summarize in 4 bullet points"
}
```

The local extractive summary recognizes a requested number of bullets or sentences from 1 to 10. It does not generate new facts.

### Conversation history

- `POST /api/v1/conversations` with optional `{"title":"Research"}`
- `GET /api/v1/conversations`
- `GET /api/v1/conversations/{id}`
- `DELETE /api/v1/conversations/{id}`

Every endpoint below requires a signed-in session (401 otherwise). Conversations are scoped to the signed-in account; another account's id returns 404.

### Accounts

- `POST /api/v1/auth/register` with `{"name":"Your Name","email":"you@example.com","password":"at-least-12-characters","confirmPassword":"at-least-12-characters"}` creates an account and signs in (`confirmPassword` is optional for API clients; when sent it must match).
- `POST /api/v1/auth/login` with an email and password signs in.
- `GET /api/v1/auth/me` returns the current account.
- `POST /api/v1/auth/logout` ends the current session.

Passwords must contain at least 12 characters and are stored as salted PBKDF2 hashes. The browser uses an HTTP-only session cookie. Email verification and password recovery are not configured.

## Limits and behavior

- Search/page reading and configured AI answers need outbound internet access.
- Search reads at most four public result pages, with request time and page size limits.
- Some sites block automated reads, require JavaScript, or change their markup. Those pages may be skipped; source links are returned for readable pages.
- The summary is extractive and can miss context. Follow the returned source links to check important claims.
- Keep the public tunnel private while evaluating the prototype. Email verification and account recovery are not implemented.
- Cross-origin browser mutations are rejected and baseline browser security headers are set. Apply request rate limits at the trusted reverse proxy/ingress in production so limits use the real client IP and work across multiple app instances.

## Configuration reference

| Environment variable | Default | Purpose |
|---|---|---|
| `SEEKORA_AI_API_KEY` | _(empty)_ | Provider key. Not needed for a local server (Ollama / LM Studio on `localhost`). |
| `SEEKORA_AI_BASE_URL` | `https://api.openai.com/v1` | Any OpenAI-compatible endpoint (Groq, OpenRouter, Gemini's compat endpoint, `http://localhost:11434/v1` for Ollama). |
| `SEEKORA_AI_MODEL` | `gpt-4o-mini` | Model id. |
| `SEEKORA_AI_TIMEOUT_SECONDS` | `60` | Read timeout for the provider. |
| `SEEKORA_AI_TEMPERATURE` | `0.2` | Leave empty to omit (some newer models reject it). |
| `SEEKORA_AI_REQUIRED` | `false` | `true` returns a configuration error instead of the extractive fallback. |
| `SEEKORA_COOKIE_SECURE` | `false` | Set `true` when served only over HTTPS (ngrok / reverse proxy). |
| `SPRING_PROFILES_ACTIVE` | _(none)_ | `prod` selects PostgreSQL and secure cookies (the Dockerfile sets it). See `RENDER_SETUP.md` for all production variables. |
| `SEEKORA_CORS_ORIGINS` | _(empty)_ | Comma-separated origins, only if the UI is hosted from a different origin. |

## Behaviour notes

* Chat answers carry `answerMode` (`ai` / `extractive`), `searchStatus` (`used` / `skipped` / `unavailable`) and a plain-language `notice`. If web search is down but a model is configured, the model still answers and the notice says nothing was verified.
* Greetings and pure writing/coding requests skip the web search; questions and "latest/news/today" style requests use it.
* There are no guest conversations any more: sign-in is mandatory and enforced by the server. Sign-in attempts are throttled per email (10 failures / 15 min), and each account is limited to 60 questions per hour.
* `GET /api/v1/conversations` returns summaries (no messages); fetch one conversation to get its messages.
* The page is served with a strict Content-Security-Policy (`script-src 'self'`), so the UI uses no inline scripts or `on*=` attributes.
* Search uses DuckDuckGo's HTML endpoint with a Wikipedia fallback. DuckDuckGo may rate-limit automated requests from some IPs; for production use a proper search API.

## Deploy on Render

See `RENDER_SETUP.md` (exact dashboard settings, environment variables, and first checks) and `render.yaml` (Blueprint).

## Production test checklist

Use a private browser window on your `https://...onrender.com` URL (allow ~1 minute if a free service was asleep).

1. Logged out, ask a question in the demo box or the chat bubble: the login window opens and nothing is sent.
2. `curl -i -X POST $URL/api/v1/chat -H 'Content-Type: application/json' -d '{"message":"hi"}'` returns `401`.
3. Sign up with a new email and a 12+ character password; the nav bar shows "Hi, <name>".
4. Ask "What is artificial intelligence?": an answer appears with the "Generated by <model>" note.
5. Ask an unrelated question (for example "How do tides work?") and check it is answered.
6. Log out: asking a question opens the login window again, and the `curl` call above still returns 401 with the old cookie.
7. Log in with a wrong password: the generic "Email or password is incorrect." message appears.
8. In Render, *Manual Deploy > Restart service*, then log in again with the same account: it still exists.
9. Create a second account and confirm it does not see the first account's conversation history (`GET /api/v1/conversations`).
10. Browser dev tools > Network: every request goes to your `onrender.com` host, none to `localhost`.
11. To test failure handling, temporarily set a wrong `SEEKORA_AI_API_KEY`: the answer box shows "The AI provider rejected its credentials or configuration", not a blank area. Restore the key afterwards.

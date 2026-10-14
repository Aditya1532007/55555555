# Seekora AI - audit summary

Verified by: 56 automated backend tests (JUnit/MockMvc/local stub servers) and a 20-check real-browser run (Playwright + Chromium, desktop and 390px mobile) against the running app with a stub AI provider.

## Defects found and fixed

| # | Severity | Problem (reproduced on the original code) | Fix |
|---|---|---|---|
| 1 | High (privacy) | Any guest could list every other guest's conversations (`GET /conversations` returned all anonymous chats). | Guest conversations are scoped to the browser session that created them (`Caller`). Cross-session get/delete/chat returns 404. Tests cover guest-vs-guest and user-vs-user isolation. |
| 2 | High | Unknown conversation id, unknown `/api/**` path, wrong content type, wrong method all returned HTTP 500. | `ApiExceptionHandler` now extends `ResponseEntityExceptionHandler`: 404 / 415 / 405 / 400 as appropriate; unexpected errors still 500 with a generic message. |
| 3 | High | Search failure = chat failure, even with an AI model configured; no fallback; 20,000-char messages were sent as the search query. | DuckDuckGo then Wikipedia fallback, snippet fallback, 10-minute cache, 200-char query cap; with a model configured, chat still answers (marked unverified) when search is down. |
| 4 | High | Long AI/search calls held a DB transaction/connection open. | `chat()` is no longer transactional as a whole; only short read/write steps are. |
| 5 | Medium | AI answers were not honest about provenance. | `answerMode`, `searchStatus`, `notice` on every answer; stored with the message; shown in the UI. |
| 6 | Medium | Provider failures: slow provider produced an unhandled `CancellationException` (HTTP 500). | Mapped to 504; 401/403/429/4xx/5xx mapped to safe messages; provider body and API key never returned or logged unredacted. |
| 7 | Medium | Login timing revealed which emails exist. | Decoy password hash check for unknown emails. |
| 8 | Medium | No CSP / security headers; no cross-origin protection on session-backed mutations; API responses cacheable. | `SecurityHeadersFilter` (CSP, nosniff, frame deny, referrer, no-store on API, origin check), opt-in CORS allow-list. |
| 9 | Medium | SSRF surface in page fetching. | Public-host validation (incl. IPv6, CGNAT, link-local, metadata ranges), re-validated on every redirect hop, port allow-list, size/time limits. |
| 10 | Medium (UI) | Custom cursor ring only moved one lerp step per mouse event (lagged permanently); hover state never applied. | Animation-frame follow loop; hover class on interactive elements. |
| 11 | Medium (UI) | Mobile: account button hidden, horizontal scrolling. | Visible compact button, overflow fixed. |
| 12 | Low (UI) | Guests saw a 401 console error on every page load; stale saved conversation id broke chat after a session expiry. | Uses `/status`; the UI retries as a new conversation on "not found". |
| 13 | Low (UI) | AI answers (Markdown) were shown as raw text. | Safe DOM-based Markdown renderer (no `innerHTML`; scripts in answers stay inert text - tested). |

## Changed files
`ai/*`, `web/WebResearchService`, `api/*`, `conversation/ChatMessage`, `conversation/ConversationRepository`, `account/AccountService`, `security/*`, `application.yml`, `static/index.html` (+ `css/seekora.css`, `js/seekora.js`), README, tests.

## Known limits / recommendations
* DuckDuckGo's HTML endpoint can rate-limit or block server IPs; use a real search API for production (Brave, Bing, SerpAPI).
* H2 file database is fine for a demo; use PostgreSQL for production and set `DDL_AUTO=validate` with migrations (Flyway).
* No rate limiting on `/chat` or `/auth/*` yet (each chat call costs money / search quota). Add Bucket4j or a reverse-proxy limit.
* Newsletter/pricing buttons are placeholders and say so.
* The repo zip contains a 224 MB `.git` folder with no commits, a 32 MB `ngrok.exe`, and a 103 MB `.m2repo`; keep them out of source control (`.gitignore` already lists most).
* I could not run Maven in this sandbox (no network); tests were compiled with `javac` against the project's bundled `.m2repo` jars. Please run `mvn -s maven-settings.xml test` once on your machine to confirm.

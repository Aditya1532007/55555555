# Deploying Seekora on Render

Seekora is one Spring Boot app that serves both the website and the API from the same origin, so there is no
separate frontend URL, no CORS setup and no `localhost` in the browser code. You need one **Web Service** (Docker)
and one **PostgreSQL** database.

## 1. Plan choice (important)

* Render's free web services have an **ephemeral filesystem**: anything written to disk, including the old H2 file
  database, is lost on every redeploy, restart and idle spin-down. That is why Seekora now uses PostgreSQL in production.
* **Free PostgreSQL expires 30 days after creation** (14-day grace period, then the data is deleted) and has no backups.
  Use it only to try the app; use a paid database for real users.
* Free web services spin down after 15 minutes idle; the next request takes about a minute. The page can look
  broken during that minute. A paid web service does not spin down.

Check Render's current pricing/plan names before choosing; they change.

## 2. Create the services

**Option A: Blueprint.** In the Render dashboard choose *New > Blueprint*, select the repository, and it reads
`render.yaml` (a Docker web service plus a Postgres database wired together). When asked, enter `SEEKORA_AI_API_KEY`.
If a plan name in `render.yaml` is rejected, change `plan:` to one your account offers.

**Option B: by hand.**
1. *New > PostgreSQL*: name `seekora-db`, database `seekora`, user `seekora`, same region as the web service.
2. *New > Web Service*: connect the GitHub repository.
   * Language/Runtime: **Docker** (the repository's `Dockerfile` builds with Maven and runs the JAR; nothing to enter for build/start commands).
   * Health Check Path: `/actuator/health`
   * Instance count: **1** (rate limits and sessions are held in memory per instance).
3. Add the environment variables below.

## 3. Environment variables

| Variable | Required | Value |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | yes (the Dockerfile already sets it) | `prod` |
| `SEEKORA_DB_HOST` | yes | the database's **Internal** hostname (Render > database > *Connections*) |
| `SEEKORA_DB_PORT` | no | `5432` |
| `SEEKORA_DB_NAME` | yes | database name, e.g. `seekora` |
| `SEEKORA_DB_USER` | yes | database user |
| `SEEKORA_DB_PASSWORD` | yes | database password (Render > database > *Connections*) |
| `SEEKORA_DB_URL` | no | full JDBC URL instead of host/port/name, e.g. `jdbc:postgresql://HOST:5432/seekora` (add `?sslmode=require` for the External URL) |
| `SEEKORA_AI_API_KEY` | **yes** | your provider key, set only here, never committed |
| `SEEKORA_AI_BASE_URL` | no | default `https://api.openai.com/v1`; any OpenAI-compatible endpoint |
| `SEEKORA_AI_MODEL` | no | default `gpt-4o-mini`; must exist for your provider |
| `SEEKORA_AI_TIMEOUT_SECONDS` | no | read timeout, default `60` |
| `SEEKORA_AI_CONNECT_TIMEOUT_SECONDS` | no | default `5` |
| `SEEKORA_AI_MAX_RETRIES` | no | default `1`; only for HTTP 500/502/503 and connection failures |
| `SEEKORA_AI_REQUIRED` | no | production default `true`: a missing key returns a clear 503 instead of a weak web-scrape fallback |
| `SEEKORA_COOKIE_SECURE` | no | production default `true` |
| `SEEKORA_SESSION_TIMEOUT` | no | idle session timeout, default `8h` |
| `SEEKORA_CHAT_PER_ACCOUNT_PER_HOUR` | no | default `60` |
| `SEEKORA_CHAT_GLOBAL_PER_HOUR` | no | default `1000` |
| `SEEKORA_FAILED_LOGINS_PER_15_MIN` | no | default `10` (per email address) |

Do not set `DDL_AUTO` to `create` or `create-drop` in production. The default `update` only adds missing tables and
columns and never deletes data.

## 4. First checks after the deploy goes live

Replace `$URL` with your `https://<name>.onrender.com` address.

```bash
curl -s $URL/actuator/health                       # {"status":"UP"}
curl -s $URL/api/v1/status                         # "aiConfigured":true  <- if false, the key is missing/unset
curl -s -X POST $URL/api/v1/chat -H 'Content-Type: application/json' \
     -d '{"message":"What is artificial intelligence?"}'          # must be 401, not an answer
```

Then, in a private browser window, follow the checklist in the project README ("Production test checklist").
If answers fail, open Render > the service > *Logs*. Seekora logs the provider host, model, and HTTP status of
provider failures (never the key), for example `AI provider api.openai.com (model X) returned HTTP 401`.
The browser shows the matching message (credentials rejected, rate limit, timeout, wrong model name).

## 5. Updating and rolling back

Pushing to the connected branch redeploys. User data lives in PostgreSQL and survives redeploys. Back up a paid
database before schema-changing releases.

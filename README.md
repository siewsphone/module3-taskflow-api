# TaskFlow API — Module 4: Dockerize & Deploy

**Live application:** <https://module3-taskflow-api-production.up.railway.app/api/tasks>

A Spring Boot 4 REST API for managing tasks, containerized with a multi-stage
Dockerfile and deployed to Railway.

---

## Live deployment

![Railway dashboard — deployment active](screenshots/dashboard.png)

![Live application URL returning JSON](screenshots/url.png)

| | |
|---|---|
| **Live URL** | <https://module3-taskflow-api-production.up.railway.app/api/tasks> |
| **Platform** | Railway (region: Southeast Asia, 1 replica) |
| **Runtime** | Java 21 (`java@21.0.12`) |
| **Source** | Docker image `siewsphone/taskflow-api:latest`, built and published by the CircleCI pipeline below; Railway runs that image |
| **Build** | multi-stage `Dockerfile` in the project root |

---

## Task 1 — Dockerfile (multi-stage build)

The `Dockerfile` sits in the project root:

```dockerfile
# Stage 1: Build the JAR with Maven
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests

# Stage 2: Run the JAR in a slim runtime image
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
```

**Why two stages?** `target/` is listed in `.gitignore`, so the compiled JAR is
never pushed to GitHub. The CI pipeline builds the image from the source with
Maven *inside* stage 1. Stage 2 then copies only that one JAR into a small
Alpine JRE image and runs it — the Maven build toolchain (several hundred MB)
is discarded with the build stage and never ships to production.

A `.dockerignore` keeps `target/`, `.git/` and the IDE folders out of the build
context so the build stays fast.

---

## Task 2 — Build and test the image locally

```bash
# 1. Build the image
docker build -t taskflow:latest .

# 2. Run the container
docker run -p 8080:8080 taskflow:latest

# 3. Test it (second terminal)
curl http://localhost:8080/api/tasks
```

The first build takes a few minutes while Maven downloads dependencies.

> **Verification note.** Both stages of the Dockerfile were verified
> individually: `mvn clean package -DskipTests` was run and produced
> `target/taskflow-api-0.0.1-SNAPSHOT.jar`, and that JAR was then started with
> `java -jar` on port 8080 and exercised with the full endpoint sweep below —
> which is exactly what Stage 2 does. The `docker build` / `docker run` commands
> themselves run in the CI pipeline (see Stretch goal, Option 1); see
> [Notes and limitations](#notes-and-limitations).

---

## Task 3 — Deployed to Railway

1. Committed the `Dockerfile` and pushed to `main`.
2. Railway → **New Project** → deployed the TaskFlow service.
3. The service runs the published image
   `siewsphone/taskflow-api:latest` from Docker Hub, rebuilt by the CI
   pipeline on every push to `main`.
4. **Settings → Networking → Generate Domain**, target port **8080**.

---

## Task 4 — Verified the deployment

Every endpoint was exercised against the **live** Railway URL with `curl`
(the same requests the module documents in Postman, just scripted). Results:

| Method | Path | Status | Response |
|---|---|---|---|
| GET | `/api/tasks` | **200** | `[]` — list of tasks |
| POST | `/api/tasks` | **201** | `{"id":3,"title":"Live verify B","completed":true}` |
| GET | `/api/tasks` | **200** | array containing the created tasks |
| GET | `/api/tasks/{id}` | **200** | the requested task |
| GET | `/api/tasks/999999` | **404** | `{"message":"Task not found with id: 999999","timestamp":"..."}` |
| PUT | `/api/tasks/{id}` | **200** | updated task |
| PUT | `/api/tasks/{id}/complete` | **200** | `completed` flipped to `true` |
| GET | `/api/tasks/summary` | **200** | AI-generated summary of the tasks |
| DELETE | `/api/tasks/{id}` | **200** | task removed |
| GET | `/api/tasks/{id}` after delete | **404** | standard error shape |

The same sweep was run against the local build with the project's black-box test
script — **all 38 checks passed**, including the edge cases:

- a client-supplied `id` is ignored (the server assigns one)
- `"completed": null` is normalised to `false` rather than rejected
- a missing task returns **404**, not 500, with the shared error shape and no stack trace
- `/api/tasks/summary` is not swallowed by the `/api/tasks/{id}` template
- a retired id is never handed out again

The 404 path is the one worth highlighting: an absent id is an invalid request,
so it is a 404 — and it is produced by a single `@RestControllerAdvice` handler,
so no controller contains a `try/catch` and no stack trace ever reaches the client.

---

## Stretch goals

### Option 1 — CI pipeline (CircleCI): build → test → publish to Docker Hub → redeploy

`.circleci/config.yml` runs a single `build-test-publish` workflow on every push.
It is a **CI pipeline** running alongside the deployment: every green run
produces a fresh image in Docker Hub and then asks Railway to pull it.

**`build`** (runs on every branch, so a broken change is caught before it
reaches `main`):

1. Caches `~/.m2/repository`, keyed on a `pom.xml` checksum
2. `mvn -B clean package` — the test phase included, not `-DskipTests`
3. **Smoke-tests the packaged JAR** by booting it and asserting each endpoint's
   status code, including the failure path:

   ```
   PASS  GET  -> 200          PASS  PUT /1/complete -> 200
   PASS  GET /999999 -> 404   PASS  GET /1 -> 200
   PASS  POST -> created id 1 PASS  DELETE /1 -> 200
   PASS  PUT /1 -> 200        PASS  GET /1 -> 404
   PASS  GET /summary -> 200  PASS  GET /version -> 200 (reports 1.1.0)
   ```

   The project has no JUnit classes, so `mvn test` passes vacuously — this step is
   the pipeline's real test. It needs no model API key, because `/summary`
   short-circuits before calling the model when there are no tasks.

**`publish`** (only on `main`, only after `build` passes, so a failing change
can never reach the registry): builds from the repository's `Dockerfile` via
`setup_remote_docker` and pushes **three tags** — `latest` (the tag the Railway
service tracks), the SemVer version read from `pom.xml`, and `sha-<short SHA>`.
See [Option 4](#option-4--published-versioned-images) for why all three exist.

**`deploy`** (after `publish`): asks Railway to pull the new image via the
Railway CLI, installed with Railway's own installer because the npm package's
`postinstall` binary fetch is skipped by CI's script policy. Two variables —
`RAILWAY_TOKEN` and `RAILWAY_SERVICE_ID` — are needed for this job to do
anything; while they are absent it prints what to do instead and halts
**successfully** (`circleci-agent step halt`), so a missing credential reads as
"not configured" rather than a red pipeline. See note 6 in [Notes and
limitations](#notes-and-limitations).

**Credentials are not in the repository.** `DOCKER_USERNAME` and
`DOCKER_TOKEN` are read from a CircleCI **context** (`docker-hub`); the job
fails fast with a clear message if either is missing. The token should be a
Docker Hub access token with Read & Write scope, never the account password.

| | |
|---|---|
| Pipeline run (green, publishes the current image) | <https://app.circleci.com/pipelines/gh/siewsphone/module3-taskflow-api/5> |
| Image on Docker Hub | <https://hub.docker.com/r/siewsphone/taskflow-api> |

### Option 2 — Frontend on Netlify

**Live frontend: <https://ntu-taskflow.netlify.app>**

A small React single-page app that consumes the deployed TaskFlow API — no
local backend, every call goes to the live Railway URL.

| | |
|---|---|
| **Frontend URL** | <https://ntu-taskflow.netlify.app> |
| **Hosted on** | Netlify (static build of `frontend/`, folder `frontend/` in this repo) |
| **Stack** | Vite + React 19, state via Context + `useReducer` |
| **API calls** | `GET /api/tasks`, `POST /api/tasks`, `PUT /api/tasks/{id}/complete`, `PUT /api/tasks/{id}`, `DELETE /api/tasks/{id}` |

**What it does:** lists the tasks from the live API on load, adds new tasks,
completes and un-completes them, deletes them, and shows live counts
(total / done / to do). API failures surface a friendly banner with a retry
suggestion instead of a stack trace.

**CORS.** With the frontend on Netlify and the API on Railway, the browser
blocks every fetch until the API names the frontend's origin. Added
`src/main/java/sg/edu/ntu/taskflowapi/config/CorsConfig.java`:

```java
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("https://*.netlify.app", "http://localhost:5173")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS");
    }
}
```

The pattern covers the deployed Netlify domain (and any future subdomain) plus
the Vite dev origin. Verified by booting the packaged JAR locally and checking
the preflight response:

```
OPTIONS /api/tasks  (Origin: https://ntu-taskflow.netlify.app)
  -> Access-Control-Allow-Origin: https://ntu-taskflow.netlify.app
     Access-Control-Allow-Methods: GET,POST,PUT,DELETE,OPTIONS
```

![TaskFlow frontend on Netlify showing live tasks from the API](screenshots/frontend.png)

Captured from a real headless-browser session running on the Netlify origin
against the live Railway API — not a mock-up: the list rendered from
`GET /api/tasks`, a task was created through the form (`POST /api/tasks → 201`),
then deleted through the UI (`DELETE /api/tasks/{id} → 200`). No CORS error, no
console message, and the counters returned to their original state afterwards.
That is the end-to-end proof that the preflight response above is what the
deployed image really answers, not just what the local JAR answered.

### Option 4 — Published, versioned images

Every build publishes **three tags**, not just `latest`:

| Tag | Kind | Meaning |
|---|---|---|
| `latest` | moving | what the Railway service tracks; always the newest build |
| `1.1.0` | SemVer | `MAJOR.MINOR.PATCH` (Lesson 4.5), read from `pom.xml` |
| `sha-21f4efe` | immutable | short commit SHA of the build |

**The version is derived, never typed.** The publish job parses the project
version out of `pom.xml` — the first `<version>` *after* `</parent>`, so it
cannot pick up the Spring Boot parent's version instead — strips a `-SNAPSHOT`
suffix if present, and tags the image with it. Bumping `pom.xml` is therefore
the only way to publish a new number, and an image cannot claim a version the
code does not declare.

The SHA tag covers the opposite need: `latest` and `1.1.0` both move as new
builds land, but `sha-<commit>` always resolves to the build of that exact
commit. When something is broken in production, `sha-<commit>` is the tag that
identifies the binary you are actually looking at.

SemVer as practised here: MAJOR for breaking API changes, MINOR for new
endpoints, PATCH for fixes. `1.0.0` was the first release; `1.1.0` added
`GET /api/tasks/version`.

**Verifiable at runtime.** That new endpoint reads the version from the JAR
manifest (the Spring Boot plugin copies the Maven version into it), so a
running container can say which tag it came from — no dashboard required:

```console
$ curl https://module3-taskflow-api-production.up.railway.app/api/tasks/version
{"version":"1.1.0"}
```

The CI smoke test asserts this and fails when the runtime version and the POM
version disagree. A versioned image that lies about its version is worse than an
unversioned one.

Two released versions in Docker Hub, with `latest` pointing at the newer:

![Docker Hub tags: latest, sha-21f4efe, 1.1.0, sha-3113023, 1.0.0](screenshots/dockerhub-tags.png)

The bare-SHA tags further down the page (`40c3ccf`, `8bf0d75`) are from the
earlier scheme, before the `sha-` prefix and the SemVer tags were added; they are
left in place as history rather than deleted.

### Option 5 — dependency security checks

Scanning the 121 resolved runtime dependencies found **17 published advisories
across 5 transitive packages**, including three authentication/authorisation
defects in `tomcat-embed-core 11.0.24` reachable over HTTP. All five were
remediated by overriding the Spring Boot BOM versions (`tomcat.version`,
`jackson-bom.version`, plus an explicit pin for the legacy Jackson 2.x line). A
re-scan reports **0 findings**, and the full 38-check endpoint sweep still passes
on the rebuilt application.

Full detail — method, before/after results, the tooling detour, and the
container-scan gap — is in [`SECURITY.md`](SECURITY.md).

---

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/tasks` | list all tasks |
| GET | `/api/tasks/{id}` | fetch one task |
| POST | `/api/tasks` | create a task → **201** |
| PUT | `/api/tasks/{id}` | replace a task |
| PUT | `/api/tasks/{id}/complete` | mark a task complete |
| DELETE | `/api/tasks/{id}` | delete a task |
| GET | `/api/tasks/summary` | AI-generated plain-English summary |
| GET | `/api/tasks/version` | deployed build version from the JAR manifest |

---

## Running without Docker

```bash
./mvnw spring-boot:run
# or
mvn clean package -DskipTests && java -jar target/*.jar
```

The frontend (optional):

```bash
cd frontend
npm install
npm run dev      # dev server on :5173, calls the live Railway API
npm run build    # static site in frontend/dist, ready for Netlify
```

---

## Notes and limitations

**1. `/api/tasks/summary` returns 500 on the live deployment.**
The summary endpoint calls a language model through Spring AI
(`spring.ai.openai.api-key=${OPENAI_API_KEY}`). No `OPENAI_API_KEY` variable is
set on the Railway service, so the outbound call is made without credentials and
the provider replies `401: Missing Authentication header`, which the global
exception handler surfaces as a 500.

The fix is configuration, not code: add `OPENAI_API_KEY` under
**Railway → service → Variables**. The endpoint works locally, where the variable
is set — it returned
`Pending: Buy groceries. Completed: Finish Module 4 Dockerfile.`

Worth noting the good part of this failure: the error came back as the standard
JSON error shape with **no stack trace exposed**, which is exactly what the
central exception handler is for.

**Resolved.** `OPENAI_API_KEY` is now set on the Railway service and the endpoint
returns **200** against the live URL, with a real model-generated summary:

> The pending task is to buy groceries. The completed task is finishing the
> Module 4 Dockerfile.

One operational detail worth recording: adding a variable to a Railway service
triggers a **redeploy**, and the old container keeps serving until that finishes —
so the change is not live the instant you save it. I confirmed the switchover by
watching the in-memory id counter: it kept climbing (same process) until it
reset to 1, which only happens when the JVM restarts, and `/summary` returned 200
from that moment on.

One thing worth stating plainly: this endpoint **sends task titles to a
third-party model**. `application.properties` points Spring AI at
`https://openrouter.ai/api/v1`, so whatever is in the list is transmitted to that
provider to produce the summary. That is acceptable for a demo with placeholder
tasks, and it is the one part of this API that is not self-contained — do not put
anything real in it.

**2. `docker build` and `docker run` were not run on a local machine with a Docker
daemon.** The environment where this README was prepared has the Docker CLI but
no daemon. Both stages were verified separately (Maven build produced the JAR;
the JAR was run on port 8080 and passed the full endpoint sweep), and the same
`docker build` + `docker push` path now runs green in the CircleCI `publish`
job on every push to `main`.

**3. Task state is in-memory.**
Tasks live in a `TaskRepository` backed by a list, not a database, so they are
lost when the container restarts or Railway redeploys. That is expected for
Module 3's scope — no database was part of the brief.

**4. Ids are never reused.**
The repository increments an internal counter that is not reset on delete, so
after emptying the store the next id continues from the counter rather than
returning to 1. This is deliberate — a client holding id 1 can never be handed a
different task — but it means the server's history, not its current contents,
decides the next id.

**5. A bodyless `PUT` returns 500, not 400.**
`PUT /api/tasks/{id}` takes `@RequestBody`, so a request sent without a body is
rejected before it ever reaches the service. Module 3 specified no field
validation, so there is no `@Valid` / `BindingResult` path that would turn this
into a 400 — the catch-all handler reports it as a 500. Worth knowing when
testing by hand (and in Postman, where an empty body is easy to send by
accident), because it looks like a server fault when it is a malformed request.
Send a JSON body with `Content-Type: application/json`:

```
curl -X PUT http://localhost:8080/api/tasks/1 \
     -H 'Content-Type: application/json' \
     -d '{"title":"Updated title","completed":false}'
```

**6. The Railway image lags the pipeline by one manual click — resolved, with one
manual step left.**
The Railway service pulls `siewsphone/taskflow-api:latest` from Docker Hub and
does not re-pull on its own when CI pushes a new `latest`, so a published image
is not live until something asks Railway to redeploy.

That redeploy is now a working command rather than a broken one. The earlier
`deploy` job called `npx @railway/cli`, which exits immediately with *"could not
find the CLI binary"* because the npm package downloads its real binary from a
`postinstall` script and CI's script policy skips it. The job now installs the
CLI with Railway's own installer and gates on credentials: with
`RAILWAY_TOKEN` + `RAILWAY_SERVICE_ID` present it runs
`railway redeploy --service "$RAILWAY_SERVICE_ID" --yes`; with them absent it
explains the manual path and halts **successfully**, so the workflow stays green
and an unconfigured credential is not reported as a code failure. Those two
variables are the remaining manual step for an unattended deploy.

The CORS build is nevertheless **live**. The image published by pipeline run
[#6](https://app.circleci.com/pipelines/gh/siewsphone/module3-taskflow-api/6)
(`sha256:d76b8646…`, pushed 21 Oct) was pulled by Railway on 24 Oct 02:02 UTC,
and the deployed instance now answers the preflight with
`Access-Control-Allow-Origin: https://ntu-taskflow.netlify.app`,
`Access-Control-Allow-Methods: GET,POST,PUT,DELETE,OPTIONS` and
`Access-Control-Max-Age: 1800`. The end-to-end check was run from a headless
browser sitting on the Netlify origin: `GET /api/tasks → 200`,
`POST /api/tasks → 201`, `DELETE /api/tasks/{id} → 200`, no console errors —
see the screenshot in [Option 2](#option-2--frontend-on-netlify).

Until the two Railway variables are set, shipping a new image is one click:
**Railway dashboard → service → Deploys → Redeploy latest image**.

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
> `target/taskflow-api-1.1.0.jar`, and that JAR was then started with
> `java -jar` on port 8080 and exercised with the full endpoint sweep below —
> which is exactly what Stage 2 does. The `docker build` / `docker run` commands
> themselves run in the CI pipeline on every push to `main` (Stretch goal,
> Option 1), which is a real Docker daemon executing the real `Dockerfile`.

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

**Screenshots of the live application URL:**

![Live API URL in a browser returning JSON](screenshots/url.png)

![The same live URL returning two tasks](screenshots/url-with-data.png)

The first was taken when the in-memory store was empty, which is why the body is
`[]`; the second is the same live URL with tasks present. The store lives in
memory, so it resets on every redeploy — an empty array is a healthy response,
not a failure. Both screenshots are the Railway URL, unmodified.

---

## Stretch goals

Five stretch goals were completed. Each section below states what was built and links
to the evidence: **Option 1** CI pipeline, **Option 2** frontend on Netlify,
**Option 3** the Simple CRM route, **Option 4** versioned images, **Option 5** security
checks.

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
"not configured" rather than a red pipeline. Those two variables are the one
remaining manual step; until they are set, shipping a new image is one click on
**Railway → service → Deploys → Redeploy latest image**, because the Railway
service does not re-pull `latest` on its own when CI pushes a new one.

**Credentials are not in the repository.** `DOCKER_USERNAME` and
`DOCKER_TOKEN` are read from a CircleCI **context** (`docker-hub`); the job
fails fast with a clear message if either is missing. The token should be a
Docker Hub access token with Read & Write scope, never the account password.

| | |
|---|---|
| Pipeline runs (build → publish → deploy, newest first) | <https://app.circleci.com/pipelines/gh/siewsphone/module3-taskflow-api> (run **#11** is the latest green one) |
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

### Option 3 — the Simple CRM projects

Option 3 is the alternative route: containerize and deploy the **Simple CRM** API and
its React frontend *instead of* TaskFlow. Both halves are live, and their full write-up
lives in the API repository, because that is where the Dockerfile, the database wiring
and the CORS configuration actually are:

| | |
|---|---|
| Live API (Railway) | <https://simple-crm-lite-production-4fe9.up.railway.app/customers> |
| Live frontend (Netlify) | <https://simple-crm-lite.netlify.app> |
| Full write-up | <https://github.com/siewsphone/simple-crm-lite#module-4--stretch-goal-option-3-simple-crm-deployed> |
| Image on Docker Hub | <https://hub.docker.com/r/siewsphone/simple-crm-lite> |

Short version of what that route involved, since the details are one click away:

- **Same multi-stage Dockerfile pattern**, but this API cannot run standalone —
  `application.properties` has no default datasource, so the container needs
  `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` *and* a reachable Postgres before it
  will start. `docker-compose.yml` supplies both locally, gated on a database healthcheck.
- **Railway service variables** carry those three values; the service could not be
  serving requests otherwise, because there are no fallback defaults in code.
- **CORS** had to be added server-side: the Netlify origin calls the Railway origin
  directly, so the API answers the preflight with `Access-Control-Allow-Origin` and
  `expose-headers: Location`.
- **CI** runs `build → test → publish`, and the `test` job starts a real
  `cimg/postgres:16.0` service container — which is what makes it a genuine datasource
  test rather than a mocking exercise.
- Endpoints verified against the deployed URL: `GET /customers` 200, `POST` 201,
  `GET /customers/{id}` 200, `PUT` 200, `DELETE` 204, re-`GET` after delete 404.

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

Each version came from a green pipeline run, so the tag is reproducible from the
run that produced it:

| Version | Pipeline | Commit |
|---|---|---|
| `1.0.0` | [#9](https://app.circleci.com/pipelines/gh/siewsphone/module3-taskflow-api/9) | `3113023` |
| `1.1.0` | [#10](https://app.circleci.com/pipelines/gh/siewsphone/module3-taskflow-api/10) | `21f4efe` |

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

The scan is reproducible rather than a one-off, and both checks now run on every
push to `main`:

| What | How |
|---|---|
| Reproduce locally | `mvn verify -Psecurity` (add `-DnvdApiKey=$NVD_API_KEY` to skip the feed rate limit) |
| `secrets` CI job | `gitleaks` over the **full git history**, `--redact` so a finding never lands in the build log; fails the pipeline on a hit |
| `dependency-scan` CI job | the same Maven profile against the live NVD feed; fails at CVSS ≥ 7 and uploads the HTML report as an artifact |

The secrets job exists because of a specific trap: `.gitignore` does not untrack a
file that is already in the index. A credential committed once keeps shipping to
the remote even after the file is deleted and ignored — so the check has to read
history, not the working tree.

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

`/summary` is the one endpoint that is not self-contained: it sends the task
titles to a third-party model (`https://openrouter.ai/api/v1`, configured in
`application.properties`) to produce the summary. Fine for placeholder tasks;
do not put anything real in this API. Task state itself is in memory, so it
survives only as long as the container does.

---

## Running without Docker

```bash
mvn spring-boot:run
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

## Errors and failures

The brief asks for what went wrong, in plain terms.

**1. `docker build` / `docker run` were not executed on the authoring machine.**
It has the Docker CLI but no daemon, so `docker build` cannot run there at all.
Both stages were verified separately instead — Maven produced
`target/taskflow-api-1.1.0.jar`, and that JAR was started on port 8080 and passed
the 38-check endpoint sweep — and the real `docker build` + `docker push` runs in
CI on every push to `main`. The local Docker command is the one step that was not
run as written.

**2. The first CI deploy job failed with `could not find the CLI binary`.**
The job called `npx @railway/cli`, whose npm package downloads its real binary in
a `postinstall` script that CI's script policy skips. Railway's own installer
surfaced a second failure — it prompts
`Install railway to ~/.railway/bin? [y/N]` and exits 1 on a non-interactive
stdin. Fixed with `curl -fsSL cli.new | bash -s -- --yes`; the pipeline has been
green since.

**3. Three CI jobs reported success while doing nothing, because `halt` exits 0.**
The worst failure of the exercise, and it was silent. The dependency scan, the Snyk
gate and the deploy job each checked for a credential, printed instructions and called
`circleci-agent step halt` when it was missing. A halted step exits **0**, so all three
showed green. Consequences: the dependency scan had never actually scanned; the Snyk
gate had never actually gated (it only began working after `SNYK_TOKEN` was added - and
then immediately found 9 vulnerabilities); and the deploy had never actually deployed
for several pipelines, because the project variable was spelled `RAILWAY_SERVIEC_ID`
so the check for `RAILWAY_SERVICE_ID` never matched. The live service was running a
build from before the dependency upgrades the whole time, while the pipeline said
otherwise. Detected by comparing the live app against the pipeline: `/api/tasks` still
served data that only survives when no redeploy happens, and the live response had none
of the security headers the current commit adds. Fixed by making a missing deploy
credential exit non-zero rather than halt, and by adding a post-deploy step that polls
the live URL for a header that exists only in the current commit - so the job proves
the container was replaced instead of trusting the redeploy command's exit code.
Lesson: a failure that exits 0 is worse than a red build, because nobody looks at it.

**4. A bodyless `PUT /api/tasks/{id}` returned 500 with a leaked method signature.**
Found by the ZAP baseline scan, not by review. The catch-all handler returned
`"Something went wrong: " + ex.getMessage()`, so a PUT with no body answered 500 with
the controller's full signature on the wire:

```
"message":"Something went wrong: Required request body is missing: public
org.springframework.http.ResponseEntity<sg.edu.ntu.taskflowapi.model.Task>
sg.edu.ntu.taskflowapi.controller.TaskController.updateTask(java.lang.Long,
sg.edu.ntu.taskflowapi.model.Task)"
```

Package names, class names, method and parameter types — a public response describing
the server's internals, and the wrong status on top of it: a malformed request is a
client error. Fixed in `GlobalExceptionHandler`: `HttpMessageNotReadableException` maps
to **400** with fixed text, unknown routes to 404, and the catch-all logs the exception
server-side and returns `"Internal server error"`. Nothing derived from an exception
reaches a client any more.

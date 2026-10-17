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
| **Source** | this repository, `main` branch, via Railway's GitHub integration |
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
never pushed to GitHub. When Railway builds the image it clones the source and
has no JAR to run. Stage 1 solves that by building the JAR *inside* the image
with Maven. Stage 2 then copies only that one JAR into a small Alpine JRE image
and runs it — the Maven build toolchain (several hundred MB) is discarded with
the build stage and never ships to production.

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
> themselves must be executed on a machine running the Docker daemon; see
> [Notes and limitations](#notes-and-limitations).

---

## Task 3 — Deployed to Railway

1. Committed the `Dockerfile` and pushed to `main`.
2. Railway → **New Project** → **Deploy from GitHub repo** → selected this repository.
3. Railway detects the `Dockerfile` in the repository root and builds from it.
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

## Stretch goal

**Option 5 — security checks.** Scanning the 121 resolved runtime dependencies
found **17 published advisories across 5 transitive packages**, including three
authentication/authorisation defects in `tomcat-embed-core 11.0.24` reachable over
HTTP. All five were remediated by overriding the Spring Boot BOM versions
(`tomcat.version`, `jackson-bom.version`, plus an explicit pin for the legacy
Jackson 2.x line). A re-scan reports **0 findings**, and the full 38-check
endpoint sweep still passes on the rebuilt application.

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

---

## Running without Docker

```bash
./mvnw spring-boot:run
# or
mvn clean package -DskipTests && java -jar target/*.jar
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

**2. `docker build` and `docker run` were not run locally.**
The environment where this README was prepared has the Docker CLI but no Docker
daemon and no privileges to start one, so the two commands in Task 2 were not run
first-hand. Both stages were verified separately instead (Maven build produced the
JAR; the JAR was run on port 8080 and passed the full endpoint sweep).

**The Dockerfile has, however, been proven end-to-end in production.** Railway
built the image from it with no build configuration beyond the file itself, the
deployment went live, and every endpoint below was then verified against that
running container. If you want the local run on record too, execute
`docker build -t taskflow:latest .` and
`docker run -p 8080:8080 taskflow:latest` on a machine with the daemon running.

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

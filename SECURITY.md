# Security checks

Stretch goal **Option 5** — dependency vulnerability scanning and secrets hygiene
for the TaskFlow API.

## What was scanned

The dependency set actually shipped at runtime, taken from the build rather than
from the `pom.xml` by eye:

```bash
mvn dependency:list -DincludeScope=runtime -DoutputFile=deps.txt
```

**121 distinct runtime dependencies.** Scanning the resolved set matters because
the vulnerable jars here are all *transitive* — none of them is declared in
`pom.xml`, so reading the file would have missed every one of them.

## Tooling, and one honest detour

The lesson specifies **OWASP Dependency-Check**. That plugin was run first:

```bash
mvn org.owasp:dependency-check-maven:check
```

It fails in this environment:

```
UpdateException: Error updating the NVD Data
  caused by NvdApiException: Invalid API Key, length of 0 too short to provided a masked partial key
NoDataException: No documents exist
```

The NVD feed now requires an API key; without one there is no data to match
against and the check cannot complete. **No API key was available**, so rather
than skip the stretch goal, the same dependency set was scanned against
**OSV.dev** (`api.osv.dev/v1/querybatch`), which is key-free and aggregates the
same upstream advisories (GitHub Security Advisories, CVE records). The two tools
answer the same question against overlapping data; OSV is simply reachable
without credentials.

One methodology note, because it changed the numbers: an early pass reported a
Netty finding. That was a **parsing bug in the scan script**, not a real issue —
Netty publishes classifier-qualified artifacts (`...:jar:linux-x86_64:4.2.17.Final`)
and the script read `linux-x86_64` as the version string. With the parser fixed,
the resolved Netty version (4.2.17.Final) is above every fixed version for that
advisory. The corrected scan is what is reported below.

## Findings — before the fix

**17 advisories across 5 packages**, every one of them transitive:

| Package | Resolved | Advisories | Fixed in |
|---|---|---|---|
| `org.apache.tomcat.embed:tomcat-embed-core` | 11.0.24 | 3 | 11.0.25 |
| `com.fasterxml.jackson.core:jackson-databind` | 2.21.5 | 5 | 2.21.7 |
| `com.fasterxml.jackson.core:jackson-core` | 2.21.5 | 2 | 2.21.7 |
| `tools.jackson.core:jackson-databind` | 3.1.5 | 5 | 3.1.7 |
| `tools.jackson.core:jackson-core` | 3.1.5 | 2 | 3.1.7 |

The three Tomcat advisories are the ones worth caring about — they are
authentication and authorisation defects reachable over HTTP:

- `CVE-2026-65905` — DIGEST authenticator authentication bypass by capture-replay.
  Vector `AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H` — high impact on confidentiality,
  integrity **and** availability, no privileges or user interaction required.
- `CVE-2026-65182` — improper access control / incorrect authorisation (`C:H/I:H/A:N`).
- `CVE-2026-68525` — incorrect authorisation in the FORM authentication process
  (`C:H/I:H/A:N`).

These are not theoretical for a service that is publicly reachable on
`*.up.railway.app`, even though this application defines no authentication of its
own: a container that ships a vulnerable servlet container is exposed through it.

The Jackson advisories are mostly denial-of-service (`AV:N/AC:L/.../A:H` —
quadratic backtracking, unbounded `StringBuilder` growth, unbounded number
parsing) plus one incomplete denylist in the polymorphic-type validator. Lower
severity, but `/api/tasks` accepts JSON from the internet, so unbounded parsing is
on a live request path.

## What was fixed

Both problem lines are managed by Spring Boot's BOM, so they were overridden by
setting the **property names Spring Boot's own `dependencyManagement` reads** —
which upgrades every artifact in the line together:

```xml
<properties>
    <tomcat.version>11.0.25</tomcat.version>
    <jackson-bom.version>3.1.7</jackson-bom.version>
</properties>
```

`jackson-bom.version` controls Jackson **3.x** (`tools.jackson`). The legacy
Jackson **2.x** line (`com.fasterxml`) is not governed by that property and
arrives through a different path, so those two jars are pinned explicitly in
`<dependencyManagement>` at `2.21.7`.

**Why properties rather than pinning individual jars:** pinning only
`tomcat-embed-core` would leave `tomcat-embed-el`, `-websocket` and `-jasper` at
11.0.24 and create a version skew inside one component. The property moves the
whole set.

**Why not bump the Spring Boot parent?** 4.1.1 is the newest stable release
(4.2.0 is still at milestone), and it is the release that pins the vulnerable
versions. Overriding the transitives is the only option short of moving to a
milestone.

## Findings — after the fix

Resolved versions: `tomcat-embed-core 11.0.25`, `tools.jackson.* 3.1.7`,
`com.fasterxml.jackson.* 2.21.7`.

```
AFTER: scanning 121 runtime dependencies
AFTER total findings: 0
  CLEAN - no known vulnerabilities reported for the resolved dependency set
```

**17 advisories → 0.**

## Regression check

Version bumps are only safe if the application still works, so the change was
verified rather than assumed. The full black-box sweep was re-run against the
rebuilt application:

```
ALL 38 CHECKS PASSED
```

That sweep covers every endpoint plus the edge cases — server-assigned ids,
`completed: null` normalised to `false`, a missing task returning 404 with the
shared error shape and no stack trace, `/summary` not being swallowed by the
`/{id}` template, and a retired id never being reissued.

## Secrets hygiene

The repository was searched for committed credentials:

- **working tree** — no matches for API keys, tokens, passwords or private keys.
  The only hit is `spring.ai.openai.api-key=${OPENAI_API_KEY}` in
  `application.properties`, which is a placeholder, not a value.
- **git history** — all commits scanned for `sk-…`, `ghp_…` and literal
  `password=` / `api_key=` assignments: **no matches**.
- `.gitignore` covers `.env` and `*.env`; the live key is set as a Railway
  service variable, so it is never in the image or the repository.

Also removed in this pass: 14 `*:Zone.Identifier` files that had been committed.
These are Windows/NTFS metadata sidecars, not credentials, but they should not be
in a repository; the pattern is now in `.gitignore`.

## Known remaining issues

**1. The image itself has not been scanned with a container scanner.**
The lesson offers this as optional. It cannot be done in the environment used
here — there is no Docker daemon — and an image scan needs the built image. Scan
`taskflow:latest` with Trivy or Docker Scout once the image builds locally:
`docker scout cves taskflow:latest`. Base-image findings would be expected on
`eclipse-temurin:21-jre-alpine` regardless of the application's own dependencies.

**2. OWASP Dependency-Check has not produced a report.**
Only because it requires an NVD API key (free, from
<https://nvd.nist.gov/developers/request-an-api-key>). With a key exported as
`NVD_API_KEY` the original lesson command works unchanged, and it is worth running
so the submission uses the tool the module names.

**3. These are patch-level upgrades, not a full audit.**
OSV reports *known* advisories. A clean result means nothing matching a published
advisory was found in the 121 resolved jars — it is not proof the code is
vulnerability-free, and the application deliberately has no authentication or
database in its Module 3 scope.

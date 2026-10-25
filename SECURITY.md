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

## Tooling

This stretch goal is set by Lessons 4.13 and 4.14, which cover four checks, and
the pipeline runs all four:

| Tool | Type | Role | Lesson |
|---|---|---|---|
| **Snyk** (`snyk_scan`) | SCA | **Gate** — HIGH/CRITICAL blocks `publish` | 4.13 |
| **Snyk Code** (`snyk_code`) | SAST | **Gate** — scans `src/` for code-level defects | 4.13 |
| **OSV-Scanner** | SCA | **Watchdog** — weekly re-scan of unchanged code | 4.14 |
| **OWASP ZAP** baseline | DAST | **Watchdog** — inspects the deployed app's responses | 4.14 |
| **Trivy** (`container-scan`) | Container | **Watchdog** — scans the image that actually ships | 4.13/4.14 |
| **OWASP Dependency-Check** (`dependency-scan`) | SCA | **Watchdog** — the tool named in the brief | brief |

The distinction matters and the lessons make it explicit: **gates** stop a release,
**watchdogs** report and keep watching. Snyk is the gate because it runs on every
push and blocks `publish`; OSV-Scanner and ZAP run on a schedule as well, because
a CVE can be published against a dependency you have not touched since the last
scan, and ZAP can only see how the app behaves while it is running.

**On OWASP Dependency-Check specifically.** The brief names it; Lessons 4.13 and
4.14 do not teach it. Rather than choose, both are wired up: the taught stack runs
on every push, and Dependency-Check runs as its own job behind the `security` Maven
profile. It needs an NVD API key — without one the feed sync is rate-limited to the
point of uselessness, which is what the original attempt hit:

```
UpdateException: Error updating the NVD Data
  caused by NvdApiException: Invalid API Key, length of 0 too short to provided a masked partial key
```

With `NVD_API_KEY` set as a project environment variable the job syncs the feed,
caches it (it is ~250 MB), and fails the job on any finding at CVSS 7 or above. It
is a watchdog, not a gate: `publish` does not require it, so a new CVE against an
unchanged dependency shows up as a red job without silently blocking a release the
Snyk gate already approved.

The findings recorded in this document were produced with **OSV.dev**, the data
source behind the OSV-Scanner that Lesson 4.14 teaches: key-free, and it aggregates
the same upstream advisories (GitHub Security Advisories and CVE records).
Dependency-Check's own report is uploaded as a build artifact each time the job runs.

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

## The pipeline

**CircleCI — the gate** (Lesson 4.13). `snyk_scan` and `snyk_code` start alongside
`build`, and `publish` requires *both* of them plus `test`. A HIGH or CRITICAL
finding therefore stops the image from reaching Docker Hub, and Railway keeps
serving the previous version. Both jobs halt with instructions if `SNYK_TOKEN` is
not configured, so a missing credential reads as "not configured" rather than as
a vulnerability.

**GitHub Actions — the watchdogs** (Lesson 4.14), both pinned by full commit SHA
because Lesson 4.13's case study is a scanner whose version tags were moved onto
malicious code:

- `.github/workflows/osv-scanner.yml` — Google's reusable OSV-Scanner workflow,
  on push to `main`, every Monday 09:30 SGT, and on demand.
- `.github/workflows/zap-baseline.yml` — ZAP baseline against `vars.APP_URL`
  (the deployed Railway URL), every Monday 10:00 SGT and on demand. Baseline, not
  full scan: it sends normal requests only, which is what makes it safe against
  production. `fail_action: false` because it is a watchdog, not a gate.

**CircleCI — the container scan.** `container-scan` runs Trivy over the published
image after `publish`, so the scan covers the base OS packages and the JRE as well
as the application's own dependencies. Trivy reads the image from the registry, so
no Docker daemon is needed. The binary is pinned to version 0.76.1 **and verified
against the release's published SHA-256** before it runs — a version tag can be
moved, a checksum cannot. Findings are written to `trivy-report.txt`, uploaded as
an artifact and summarised in the job output; the job does not block a release the
dependency gate has already approved.

**CircleCI — dependency scan.** It ran and it did its job: the first completed run
(job #58, commit `c13e469`) **failed the build** with

```
[ERROR] See the dependency-check report for more details.
[ERROR] Failed to execute goal org.owasp:dependency-check-maven:12.1.3:check
```

That is `failBuildOnCVSS=7` doing what it is configured to do — Dependency-Check
independently found the same Netty and Tomcat advisories Snyk had, on the POM as it
stood before the upgrade. Two tools, two advisory feeds, same conclusion. The job
takes ~45 minutes on a cold NVD cache (the feed is ~250 MB, synced into one step, so
it needs `no_output_timeout: 45m`); the cache makes subsequent runs short. The
report is uploaded as `dependency-check-report.html` / `.json` either way, because
`store_artifacts` runs even when the scan fails.

**CircleCI — dependency scan (configuration).** `dependency-scan` runs OWASP Dependency-Check via
`mvn verify -Psecurity`, caching both `~/.m2` and the NVD data directory. It
halts with instructions when `NVD_API_KEY` is absent, for the same reason the Snyk
jobs halt: an unconfigured credential should look unconfigured, not broken. The HTML
and JSON reports are stored as artifacts.

## Reproducing a scan locally

```bash
# SCA + SAST, the same checks the gate runs (needs SNYK_TOKEN + the Snyk CLI)
snyk test --severity-threshold=high
snyk code test --severity-threshold=high

# container scan of the image that ships (no Docker daemon needed)
trivy image --severity HIGH,CRITICAL siewsphone/taskflow-api:1.1.0

# the tool named in the brief (needs a free NVD API key)
NVD_API_KEY=... mvn verify -Psecurity -DskipTests

# the dependency set behind the findings, straight from the build
mvn dependency:list -DincludeScope=runtime -DoutputFile=deps.txt
```

## What the Snyk gate found on its first real run

Once `SNYK_TOKEN` was configured, `snyk_scan` stopped halting and scanned for real:
**123 dependencies, 9 issues, 9 vulnerable paths** — and the gate did its job, which
is why `publish` did not run:

| Package | Version found | Severity | Count |
|---|---|---|---|
| `io.netty:netty-codec-http` | 4.2.17.Final | HIGH | 1 |
| `io.netty:netty-codec-classes-quic` | 4.2.17.Final | HIGH | 1 |
| `io.netty:netty-codec-http3` | 4.2.17.Final | HIGH | 5 |
| `org.apache.tomcat.embed:tomcat-embed-websocket` | 11.0.25 | HIGH + **CRITICAL** | 2 |

Every one is **transitive**: none of those packages appears in `pom.xml`, so a diff
review would never have caught them. That is precisely the argument for a dependency
gate rather than a careful reading of the POM.

Remediation, all via the property names Spring Boot's own `dependencyManagement`
reads, so each line's artifacts move together instead of one jar being pinned while
its siblings stay behind:

- `spring-boot-starter-parent` 4.1.1 → **4.1.2**
- `tomcat.version` 11.0.25 → **11.0.27** (fixes the CRITICAL authentication bypass)
- `netty.version` → **4.2.19.Final** (new property; upstream from 4.2.17.Final)
- `spring-ai.version` 2.0.0 → **2.0.2** (Snyk's flagged starter)

No `--severity-threshold` was raised and no finding was ignored to make the gate
pass. The override moves the versions; the next pipeline run re-scans and decides.

## Container scan: detection, then fix

The container scan is only worth having if it can be shown to catch things, so it
was made to catch this one twice — once on a deliberately unfixed image, once on the
fixed one. Two adjacent pipeline runs, same image tag, one line of difference.

**Run 1 — capture.** With the `apk upgrade` line removed, `container-scan` (#81,
commit `bfc2936`) reported:

```
target: taskflow-api:latest (alpine 3.24.3)  alpine  1 vulnerability
        app/app.jar                          jar     0
Total: 1 (HIGH: 1, CRITICAL: 0)
brotli-libs  CVE-2026-111497  HIGH  fixed  1.2.0-r1 -> 1.2.1-r0
             brotli: Out-of-Bounds Write in Google Brotli Decoder
```

Read that split carefully, because it is the whole argument for scanning the image:
**`app/app.jar` is 0.** Everything this application declares is clean. The finding
lives in the alpine base layer that the JRE sits on — invisible to Snyk, to
OSV-Scanner and to Dependency-Check, all of which read `pom.xml`.

**Run 2 — fix.** With `RUN apk upgrade --no-cache` restored (`beff5d1`),
`container-scan` (#90) reported:

```
target: taskflow-api:latest (alpine 3.24.3)  alpine  0
        app/app.jar                          jar     0
CRITICAL: 0
HIGH:     0
```

Detection and remediation, both evidenced by a run rather than asserted in prose.

## Known remaining issues

**1. The Snyk gate reports what Snyk reports.** `SNYK_TOKEN` is configured as a
project environment variable, so `snyk_scan` and `snyk_code` run the real CLI rather
than halting. Note what that means in practice: the gate can only block findings
the Snyk rule set knows about, with `severity-threshold: high`. A MEDIUM finding
passes. That is the lesson's configuration, kept deliberately.

**2. These are patch-level upgrades, not a full audit.** OSV and Dependency-Check
report *known* advisories. A clean result means nothing matching a published
advisory was found in the resolved jars — it is not proof the code is
vulnerability-free.

**3. The application has no authentication and no database** in its Module 3 scope,
so every endpoint is public by design. That is out of scope for this module but it
is the largest single risk in the running service, and ZAP's baseline scan has
nothing to test in that direction because there is no login surface to probe.

**4. Base-image findings will recur.** The `container-scan` job found one HIGH in
`brotli-libs` inside the JRE image. `apk upgrade` handles it today, but a
digest-pinned base image will keep surfacing new CVEs as they are published — which
is exactly why that job runs on every publish rather than once.

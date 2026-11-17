# Deployable images: the dependencies in a layer of their own, and names without `-be`

**Status:** proposal, 2026-10-01. Nothing is built. Revised the same day after a review:
one image with a reproducible dependency layer replaces the dependency image of the
first draft (§2.2); decided 2026-10-03. **Step 1 done 2026-10-03:** merged
with no run in flight, published as 4.0.48 (image step 56 s, dependency layer
`5b5eb1ec…`, the same bytes as on this workstation); both stagings pinned to 4.0.48
(devops-niord, merged 02:32Z) and checked there the same day: both run 4.0.48 from
`/app/app.jar`, and a chart-import batch job was resolved from
`niord-base-4.0.48.jar` on each. The following publish, 4.0.49 (a frontend-only change),
built the same dependency layer `5b5eb1ec…`; its push log says `Layer already exists`
for the second and third image, and the first image's upload is the classic image
store's one upload per publish (§2.2). What remains is to read in Harbor that 4.0.48
and 4.0.49 name the same blob (open check 1 in §6). 4.0.45 stays unpublished (see §4).
**Step 2 done 2026-10-03:** published as 4.0.50 to `niord/niord-app-en`,
`niord/niord-app-dk` and `niord/niord-app-gl`; the dependency layer was uploaded once
into the first new repository and mounted into the other two. 4.0.49 is the last version
under the old names. **Step 3 (2026-10-03):** devops-shared's merge request merged (the runbook);
devops-niord's merge request moves every environment file to the new names with `M = 4.0.50`;
both stagings ran `niord/niord-app-<cc>:4.0.50` two minutes after the merge (checked
by the owner). **Step 4** waits: a later version than 4.0.50 published and a week on both stagings
(earliest 2026-10-10).
**Scope:** the three deployable images that `publish.yml` pushes to Harbor (`niord-app`,
`niord-dk`, `niord-gl`), the local image build, the chart's default image name, and the
places in the two devops repositories that name the images. The public site's images
(`fe/ui-apps/info/Dockerfile`) are not touched. The repository split
(`be/docs/cicd/design.md` §2.1) is not part of it either; §7 says what this plan does
so that the repository split needs no rework of the images.
**Repos:** niord (the images, the workflow, the chart), devops-niord (environment
values), devops-shared (one runbook paragraph).

---

## 1. Summary

**Goal.** A publish should add about 10 MB to Harbor, not about 490 MB, so that Harbor
no longer needs a release cleanup every few days, and the jars a publish hands between
its jobs should take a third of the Actions storage they take today. No Harbor quota
is known; the limit that is known is the organisation's Actions storage (`publish.yml`,
the `tag` job).

Two changes, in four steps:

1. **Put the third-party jars in a layer of their own that comes out as the same bytes
   on every build.** The image stays one image, built with plain `docker build`.
   Harbor stores a layer once, so a publish stores only our own code and the web app.
2. **Rename the images to `niord/niord-app-<country>`.** The images carry the
   administration web app since 2026-09-26, so the `-be` suffix is wrong, and one
   pattern for the three deployables reads better than a bare `niord-dk`:
   `niord/niord-app-en` (the generic English deployable), `niord/niord-app-dk`,
   `niord/niord-app-gl` (decided 2026-10-03).

| # | Repo | What | Visible effect |
|---|---|---|---|
| 1 | niord | the dependency layer: the `Dockerfile`, a split script, a publish script, `build-image.sh`, `publish.yml` | none; images keep their names, publishes get small |
| 2 | niord | rename to `niord/niord-app-<country>`: the publish script, the chart default, the scripts and the docs | the next publish pushes to new Harbor repositories |
| 3 | devops-niord, devops-shared | every environment file names the new repositories and pins the first renamed version | stagings run the renamed image |
| 4 | Harbor by hand; niord | retract the versions older than the rename, delete the three old repositories and the older charts; the retraction list loses the old names | none |

Steps 1 and 2 are one pull request each, step 3 is one merge request in each devops
repository, step 4 is a session in the Harbor UI and one small pull request.

The order is fixed. Step 1 goes first: it moves the image name out of the workflow
file into a script that is checked out with the chart, so the rename of step 2 always
applies to a whole version. Step 3 follows step 2 directly. Step 4 waits (see there).

---

## 2. Background

### 2.1 What an image holds today

`be/k8s/docker/Dockerfile` copies one executable jar and the web app onto
`eclipse-temurin:25-jre`. Measured on main, 2026-10-01, by building the reactor twice
from the same source as 4.0.901 and 4.0.902:

| Part of the jar | Size | Changes |
|---|---|---|
| 294 third-party jars | 173.6 MB | when a dependency version changes |
| `niord-base-<version>.jar` | 3.1 MB | every publish (the file name carries the version) |
| the module's own classes and resources | 16 KB to 0.9 MB | every publish |

- The 294 jars are byte-identical in `niord-app`, `niord-dk` and `niord-gl`, and
  byte-identical between the two builds.
- The dependency set changed in 3 of the 26 intervals between publish tags (4.0.9,
  4.0.23, 4.0.30).
- The jar is one layer, 161.8 MB compressed, and its digest changes on every build.
  So each publish stores it three times: about 0.49 GB, 14.1 GB for the 29 versions
  Harbor held on 2026-09-30. Two versions are kept since the cleanup of that day
  (4.0.38 and 4.0.42): about 1.0 GB.

### 2.2 How the layer is shared, and the schemes that were measured

A registry stores a layer once if its digest is the same. Each scheme was run as two
publishes in a row, each in a fresh Docker daemon, against a throwaway registry. The
"adds" column is MiB of registry disk; 462 MiB is the 485 MB (3 × 161.8 MB) of §2.1.
The times come from two sets of runs and compare only roughly.

| Scheme | The second publish adds | Build + push, local |
|---|---|---|
| today: the jar in one layer | 462 | 31 s |
| one image, two `COPY` layers, `/app/lib` made at build time | 160 | 20 s |
| one image, buildx container builder with rewritten timestamps | 10 | 39 s |
| two images: a dependency image under the app image (the first draft) | 10 | 8 to 12 s |
| **one image, a dependency tree with fixed times copied to `/`** | **10** | **13 s** |

The web app was a placeholder in these runs; the real bundles add about 5 MB a publish.
With `niord-base` in a layer of its own (§3.2) the last row adds 5, not 10.

**Why row 2 fails and the last row works.** A plain `docker build` writes the build
time into every directory it creates. In row 2 the `app` directory comes from
`RUN mkdir` and `app/lib` from the `COPY` destination, so the layer gets a new digest
on every run, even with identical jars. In the last row the directories come from the
build context: a tree `app/lib/*.jar` and `niord-home/`, every entry set to one fixed
time and its mode normalised, copied to `/`. Nothing is created at build time, and the
layer is the same bytes on every build. Measured:

- the same layer digest in fresh daemons of Docker 28.0.4 (classic image store, as on
  the runner today) and 29.8.2 (containerd image store), on arm64 and on an emulated
  amd64 daemon, and on another base image;
- two `--no-cache` builds on this workstation (OrbStack, Docker 29.4.0) two seconds
  apart: the same layer;
- a publish on a changed base image stores the base's own layers and nothing of the
  dependency layer.

It needs no container builder, so the Harbor robot password stays with `docker login`,
which `publish.yml` does on purpose.

**What it rests on, and what guards it.** The saving rests on the layer coming out
equal on every run.

- A layer that depends on the build time would cost 158 MB on every publish. The
  publish script builds the dependency stage twice without cache and compares the two
  (§3.5, about 4 s); a difference fails the job. The old layout fails this check.
- A new builder that produces a different but stable layer costs one layer once. It
  did not happen between the Docker versions and image stores above.
- The `Dockerfile` carries one rule: nothing above the dependency `COPY` may create
  `/app` or `/niord-home`. The same check catches a breach.
- With the classic image store the layer is uploaded once per publish although Harbor
  stores nothing new. The total time was the same as for two images.

**Why not two images.** The first draft built the jars into a dependency image,
`niord/niord-deps:<key>`, and each app image `FROM` it. It stores the same 10 MB, and
it shares by tag, so it does not depend on equal bytes. It needs, in exchange: a
second Harbor repository with unversioned, overwritable tags that every release is
built on; a key whose base-image part depends on the daemon's image store; a test for
"the tag is not there" that reads error texts which differ by image store and by
registry; a check that the image behind the tag holds the right jars; a rule for two
runs that push the same tag; a cleanup by hand; and 158 MB for every refresh of the
base image, which happens about twice a month (the `linux/amd64` image behind
`eclipse-temurin:25-jre` changed 16 times between 2026-02-06 and 2026-09-25). One
image needs none of that. If the layer ever turns out not to be stable on the real
runner, two images remain the fallback (§6).

### 2.3 Why rename

The `-be` suffix dates from when a `-fe` image was planned beside each backend image
(`be/docs/cicd/design.md` §2). Since 2026-09-26 the image carries the country's build
of the administration app and the backend serves it. The image is the deployable, not
its backend half. The new names follow one pattern, `niord/niord-app-<country>`, with
`en` for the generic English deployable (the Maven module `niord-app`), so the three
read as what they are: the same application, built for a country. Done before the
repository split, the rename is made in one workflow and not in three (§7.1).

---

## 3. Target design

### 3.1 The image

Repositories and tags stay as they are until step 2: one image per deployable, tagged
`4.0.<run>`. Its layers, bottom to top:

| Layer | Holds | New digest |
|---|---|---|
| temurin | the Java runtime | when `eclipse-temurin:25-jre` is refreshed |
| dependencies | `/app/lib/` with the 294 third-party jars, `/niord-home` | when a dependency version changes |
| `niord-base` | `/app/lib/niord-base-<version>.jar` | every publish; one layer shared by every image built on this base version, the country images (from their own repositories) included |
| the module | `/app/app.jar` | every publish, per image |
| web | `/app/web/` | every publish, per image |

### 3.2 The Dockerfile (sketch; the exact text is settled in step 1)

```dockerfile
# be/k8s/docker/Dockerfile — three named build contexts: deptree, app, web
ARG BASE_IMAGE=eclipse-temurin:25-jre
FROM ${BASE_IMAGE} AS deps
# The dependency layer. The tree comes from the build context with every entry at one
# fixed time, so the layer is the same bytes on every build. Nothing above this line
# may create /app or /niord-home.
COPY --chown=1000:1000 --from=deptree . /

FROM deps
WORKDIR /app
COPY --chown=1000:1000 --from=app lib/ /app/lib/
COPY --chown=1000:1000 --from=app app.jar /app/app.jar
COPY --chown=1000:1000 --from=web . /app/web/
ENV NIORD_WEB_APP_DIR=/app/web
USER 1000:1000
EXPOSE 8888
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

- The entry point, the user, the port, the paths and their owners are the ones the
  chart uses today, so the chart's templates do not change. `/` stays `root`, and
  `/app`, `/app/lib` and `/niord-home` are owned by 1000, as today.
- There is no `RUN` line. A `RUN` layer is built again on every fresh runner and gets
  a new digest each time.
- `niord-base` is copied before `app.jar`, in a layer of its own. The file is the same
  in every image built on one base version, so they share the layer and Harbor stores it
  once, not once per image.
- The file has no `# syntax=` line. Today's first line makes every build fetch the
  Dockerfile frontend from Docker Hub, also when it is cached, and nothing here needs
  a newer frontend than the one Docker carries.
- The inputs come in as named build contexts, so nothing is copied into
  `be/k8s/docker/` any more, and the entries for `app.jar` and `web/` in its
  `.dockerignore` and in `be/k8s/.gitignore` go.

### 3.3 Splitting the jar

Spring Boot's own tool does it, with no change to any `pom.xml`:

```bash
java -Djarmode=tools -jar niord-dk-4.0.N.jar extract --destination out
# out/niord-dk-4.0.N.jar   the thin jar: our classes, and a manifest Class-Path naming lib/*.jar
# out/lib/                 295 jars: 294 third-party + niord-base-4.0.N.jar
```

A new script, `be/k8s/scripts/split-jar.sh <fat jar> <dist dir> <module>`, wraps it
and writes the layout of §3.5 under `<dist dir>`:

- removes `<dist dir>/<module>` first and runs the extract into a fresh temporary
  directory, never with `--force`: the extract refuses a directory that is not empty,
  and with `--force` it would keep the jars of an earlier split,
- moves the thin jar to `<module>/app/app.jar` and `niord-base-*.jar` to
  `<module>/app/lib/`,
- writes the hash of the third-party jar list (the sorted lines `<sha256>  <file name>`)
  to `<module>/deps.sha256`; the list is sorted under `LC_ALL=C`, so the hash is the same
  on macOS and on Linux,
- moves the third-party jars to `deps/<hash>/`, or drops them when another module has
  already produced the same set,
- fails unless exactly one `niord-base-*.jar` was moved.

`split-jar.sh --hash <dir>` prints the hash of a directory of jars; it is the one
definition of the hash, and `publish-images.sh` uses it for its checks.

The last guard matters: `niord-base` carries the version in its file name, and a copy
left among the dependencies would give every publish a new dependency layer.

Spring Boot's layer index (`extract --layers`) was considered and not used. In today's
reactor build its default gives the same split, because `niord-base` is a module of
the same Maven session. After the repository split a country build takes `niord-base`
from GitHub Packages, and the default then puts it with the dependencies. The move by
file name gives the right split in both.

### 3.4 What changes at run time

The app starts as `java -jar /app/app.jar` in both layouts, but the jar is now a thin
one with a plain class path instead of a jar of jars read through Spring Boot's
loader. Checked so far: the unpacked `niord-app` was started against a database
address that does not exist; Spring and Tomcat came up and the start stopped at
Liquibase's connection attempt, as intended. That shows the class path resolves. It
does not show the application works; step 1 does that on the local cluster.

### 3.5 The publish workflow

**`build` job.** After the reactor build, and before the step that deploys
`niord-base` to GitHub Packages, it runs `split-jar.sh` for each module. A split that
fails then publishes nothing. After the deploy it uploads, still as the artifact
`deployable-jars` (the `tag` job deletes it by that name), with the upload path
`be/dist/` instead of `be/dist/*.jar`:

```
dist/<module>/app/app.jar
dist/<module>/app/lib/niord-base-4.0.N.jar
dist/<module>/deps.sha256
dist/deps/<jar list hash>/*.jar        one directory per distinct set; one today
```

About 185 MB instead of 532 MB.

**`harbor` job.** The image step moves out of the workflow file into a new script,
`be/k8s/scripts/publish-images.sh <module>...`. The job's sparse checkout already
holds `be/k8s`. The script gets everything it needs from outside: the modules as
arguments, and `DIST_DIR`, `WEB_DIR`, `IMAGE_PREFIX` (the registry and project),
`VERSION`, `SHA`, `SOURCE_URL` and `PUSH` in the environment. It holds no module list and no path into
the monorepo, and finds the `Dockerfile` relative to its own location (§7.1). The
login and the logout stay in the workflow. For each distinct jar set it:

1. **Makes the dependency tree**: `app/lib/` with the jars of `dist/deps/<hash>/` and
   an empty `niord-home/`, modes normalised (`chmod -R u=rwX,go=rX`), every entry set
   to one fixed time (`TZ=UTC touch -h -t 197001010000.01`: portable to macOS, and not
   the zero that Java also reports for a missing file). This is done here and not in
   the `build` job, because an Actions artifact keeps neither modes nor empty
   directories.
2. **Checks that the layer is reproducible**: builds the `deps` stage twice with
   `--no-cache`, one second apart, and compares the layers of the two results. Both
   must be non-empty and equal, or the job fails before anything is pushed.

It also checks, before anything else, that `dist/deps/<hash>/` hashes to its name, so
a damaged artifact stops the job before a build. Then, for each module, it:

3. **Builds** the image with plain `docker build`, the three named contexts, the
   labels of today, and `--provenance=false` (§3.8).
4. **Checks the content**: `/app/app.jar` in the image is the module's thin jar from
   `dist/`, `/app/lib` holds exactly one `niord-base-*.jar`, and the other jars there
   hash to `deps.sha256`. The image sets `LC_ALL=en_US.UTF-8`, so the list is sorted
   on the runner under `LC_ALL=C`, as `split-jar.sh` did. The check compares the image
   with the inputs the script was given; it does not judge the inputs.
5. **Pushes**, unless `PUSH=0`, and writes the image and its digest to the summary.

The summary gains one line per jar set: the diff ID of the dependency layer. Two
publishes with the same dependencies show the same value.

The script runs in the `harbor` job with the robot's Docker session, as the inline
step does today. The Secrets header of `publish.yml` says so, and a change to the two
scripts is reviewed like a change to the workflow file.

### 3.6 The local build

`build-image.sh` keeps its options and the image it produces for the chart. After the
Maven build and the web build it calls `split-jar.sh` and then `publish-images.sh`
with `PUSH=0` and its local image name, so the local image is built by the code that
builds the published one. The web bundle and the split go into one temporary
directory that the script removes; nothing is copied into `be/k8s/docker/` any more. It needs `java` for the extract also when it reuses an
existing jar, so the `JAVA_HOME` lookup moves out of the Maven branch. It builds
`FROM eclipse-temurin:25-jre` by tag.

### 3.7 Names after the rename

| Maven module | Today | After step 2 |
|---|---|---|
| `niord-app` | `niord/niord-app-be` | `niord/niord-app-en` |
| `niord-dk` | `niord/niord-dk-be` | `niord/niord-app-dk` |
| `niord-gl` | `niord/niord-gl-be` | `niord/niord-app-gl` |

The image name is no longer the module name with a suffix, so `publish-images.sh` and
`build-image.sh` carry the mapping (module `niord-app` is `en`; a module `niord-<cc>`
is `<cc>`), in one place.

The public site's images are built as `niord-<cc>-info` (`niord-dk-info`,
`niord-gl-info`) by each country's image pipeline; this repository's Info CI built them
until the country split's P7 and builds none since. The names sit beside the new ones
without a clash.

A chart and its image are published together under one version, so chart `4.0.N`
defaults to the image name that exists for `4.0.N`. Older charts keep pointing at the
`-be` repositories, which stay until step 4.

Every Harbor robot is scoped to the project, not to named repositories
(devops-shared `runbooks/bootstrap.md`, Robots), so the new repositories need no robot
change: CI can push them and the clusters can pull them. The dependency layer is not
uploaded again for the new repositories: Harbor mounts it from the project.

### 3.8 The runner image

All jobs of `publish.yml` run on `ubuntu-latest`. GitHub moves that label from
Ubuntu 24.04 (Docker 28.0.4, classic image store) to Ubuntu 26.04 (Docker 29, where a
fresh install uses the containerd image store) between 2026-10-19 and 2026-11-19
(actions/runner-images issue 14748). During those weeks a job lands on either.

- The dependency layer has the same digest on both stores (§2.2), so the saving does
  not depend on the move.
- On the containerd store a plain `docker build` pushes an index with an attestation
  manifest, where `publish.yml` promises Harbor and Renovate a single manifest. That
  is true of today's workflow as well. `--provenance=false` gives a single manifest on
  both stores and is accepted by Docker 28.
- The plan does not pin the job to `ubuntu-24.04`: that label is due to get Docker 29
  too, so a pin only moves the date. Instead step 1's proof 3 runs on both stores.

If step 1 is not on main by 2026-10-19, today's workflow gets `--provenance=false` in
a pull request of its own.

---

## 4. Steps

### Before each merge

- **Main's tip is published and tagged before step 1 merges.** When this was written
  (2026-10-01) the last Publish run (4.0.45) had failed on the
  organisation's storage quota and main's tip was unpublished. Met since: 4.0.46 and
  4.0.47 (2026-10-02) went through, `tag` job included, so the quota is lifted and
  4.0.47 is the jar-in-one-layer version to compare the first split version with and
  to fall back to. 4.0.45 is left as it is: a re-run would publish an older commit
  under that number, and after the merge GitHub would refuse it its tag.
- **Merge only when nothing is in flight.** This holds for the pull requests of steps
  1, 2 and 4 and for a revert of any of them. No Backend CI, Frontend CI or Info CI run
  (Info CI since the review of 2026-10-22) is in progress for a commit on main, and the last Publish run has finished its `tag` job.
  Two reasons. A publish started by `workflow_run` reads `publish.yml` from the tip of
  main but builds the commit CI tested, so a commit in flight across step 1's merge
  gets the new workflow with a tree that has no `split-jar.sh`. And these pull
  requests change workflow files, after which GitHub refuses the tag of a publish in
  flight (`publish.yml`, the `tag` job); it is then published but not tagged and needs
  the tag by hand.
- **Step 2 merges only after step 1's publish is tagged.**

### Step 1 — niord: the dependency layer

Image names stay `niord/<module>-be`.

**Files**

- `be/k8s/docker/Dockerfile` (§3.2), `be/k8s/docker/.dockerignore` and
  `be/k8s/.gitignore` (the entries for `app.jar` and `web/` go)
- `be/k8s/scripts/split-jar.sh` (new, §3.3), `be/k8s/scripts/publish-images.sh` (new,
  §3.5), `be/k8s/scripts/build-image.sh` (§3.6; its exit trap no longer has an
  `app.jar` to remove)
- `.github/workflows/publish.yml`:
  - the `build` job: the split before the deploy step, the upload step's path
    (`be/dist/`),
  - the `harbor` job: the download step's path (the directory the script is given) and
    the image step (now a call of `publish-images.sh`),
  - the header, the Secrets header (§3.5), and the comments that name the jars and
    their size (the `build` job's banner, the staging comment, the `tag` job's banner
    with "~0.5 GB a run")
- docs:
  - `be/k8s/README.md`: a new section on the image and its layers, the file tree, and
    Prerequisites (JDK 25 is needed also when a jar is reused),
  - `README.md` (the `publish.yml` row),
  - `be/docs/cicd/README.md` (the CI row and the container image row),
  - `be/docs/cicd/implementation-plan.md` (the Dockerfile item; the country workflow
    item, which starts from `split-jar.sh` and `publish-images.sh`),
  - `be/docs/cicd/design.md`: §2.1, the Dockerfile row (the new files; a country
    pipeline calls `split-jar.sh` and `publish-images.sh` from a checkout of `niord`'s
    `be/k8s`, §7.2), and the status line gains a dated revision

**Proof before the merge**

1. `./build-image.sh --rebuild` for `niord-dk`, then the chart on OrbStack. Checks, in
   the running app:
   - it starts, and Liquibase reports no duplicate change log;
   - the first log line after the banner names the built version and the thin jar
     (`Starting NiordDkApplication v<version> ... (/app/app.jar ...)`). The app's own
     version endpoint answers `${timestamp}` before and after
     (`be/docs/potential-errors.md`, WEB12);
   - the administration app loads at `/` and a login works;
   - a message list loads and one message opens;
   - a PDF report renders with its map image (FreeMarker templates, the portrayal
     definition and the static CSS all come from `niord-base` on the class path);
   - a batch job runs: one chart is imported from the administration app, and the log
     shows `JBERET000030: Resolved job jar:file:/app/lib/niord-base-<version>.jar!/META-INF/batch-jobs/chart-import.xml`.
2. `./build-image.sh --module niord-gl` right after: it reports the same dependency
   layer as the `niord-dk` build.
3. `publish-images.sh` itself against a throwaway registry, two publishes in fresh
   Docker daemons, run once on Docker 28 with the classic image store and once on
   Docker 29 with the containerd image store. In all four the dependency layer has the
   same digest, the second publish of each pair adds less than 15 MB, and every pushed
   tag is a single manifest.
4. The same script with a `Dockerfile` that makes `/app` before the dependency `COPY`:
   the run fails at the reproducibility check and builds nothing. And with a jar
   removed from `dist/deps/<hash>/`: it fails at the input check, before any build.

No CI job builds this image; Backend CI runs the tests on class directories. The
script is proved by 3 and 4. Two things are proved only by the first publishes: the
workflow around the script (the artifact layout), and that the layer comes out equal
on the real runner and in Harbor.

**If the first publish fails.** In the `build` job, at the split: nothing is
published. In the `harbor` job: `niord-base` 4.0.N is in GitHub Packages, the images
of the modules before the failing one may be in Harbor, and no chart or tag exists.

- For a failure outside the code (Harbor or Docker Hub unreachable, a rate limit), use
  "Re-run failed jobs" within a day. It runs the same files and finds the artifacts.
- For a defect, merge a fix and retract 4.0.N, deleting in Harbor what the retraction
  summary lists. The fix must touch a file under `be/` or `fe/`, even when the defect
  is in `publish.yml` alone: only a Backend CI, Frontend CI or Info CI run starts a
  publish (Info CI since the review of 2026-10-22), and as written neither of
  the first two was filtered on `publish.yml`. Since 2026-10-08 a change to
  `publish.yml` runs Backend CI and is published by its own merge, so the fix no
  longer needs a file under `be/` or `fe/`.

**Proof after the merge**

- The publish run is green, and its summary names the dependency layer.
- Both stagings run the version: one devops-niord merge request bumps the pins of
  `envs/staging/dk` and `envs/staging/gl`, names unchanged. On each, one PDF report
  and one batch job are checked.
- The following publish shows the same dependency layer in its summary. Read through
  the registry API (the `mirror-push` robot), the images of both versions share that
  layer in Harbor, and the second version added less than 15 MB.

**Rollback.** Before step 2: revert the pull request, when nothing is in flight. The
next publish is a jar-in-one-layer image again. After step 2: see "Rollback order".

### Step 2 — niord: the images become `niord/niord-app-<country>`

**Files**

- `be/k8s/scripts/publish-images.sh`: the module-to-name mapping of §3.7;
  `.github/workflows/publish.yml`: the header
- `.github/workflows/retract-version.yml`: the header and the summary list gain the
  new repositories and keep the `-be` ones. A version published before the rename has
  its images in the `-be` repositories until step 4, and the summary already says
  "where present". Step 4 takes the old names out.
- `be/k8s/helm/niord/values.yaml`: `app.image.repository` and the comment above it
- `be/k8s/scripts/build-image.sh`: `IMAGE_REPO` (the same mapping) and its header
- `be/k8s/scripts/secom-renewal-rehearsal.sh`: `APP_IMAGE`
- docs: `be/k8s/README.md`, `be/docs/cicd/README.md`, `README.md` (the `publish.yml`
  row), and
  - `be/docs/cicd/design.md`: the repository table of §2 (`niord-dk-be`,
    `niord-dk-fe` become `niord-app-dk` and, for the public site, `niord-dk-info`);
    the `niord-app` row keeps its text "decided 2026-09-22" and gains "renamed <date>,
    `niord/niord-app-en`"; the status line gains a dated revision,
  - `be/docs/cicd/implementation-plan.md`: the open items take the new names; the
    decided item keeps its date and gains the same note; the record of what was pushed
    as 4.0.1 stays as written

**Proof.** `helm template` renders `niord/niord-app-dk:<version>`; `build-image.sh`
builds that name and the local chart install finds it. After the merge: the publish run
pushes three new repositories, and the dependency layer is not stored again.

`M` is the first version after the merge whose Publish run is green. If that publish
fails, the story of step 1 applies, in the new repositories.

**Rollback.** See "Rollback order".

### Step 3 — devops-niord and devops-shared: the environments follow

One merge request in devops-niord, made as soon as step 2 has published version `M`:

- `app.image.repository` in `envs/staging/dk/values.yaml`,
  `envs/staging/gl/values.yaml`, `envs/_template/values.yaml` (with the comment above
  it), `reference/pending/envs/prod/dk/values.yaml` and
  `reference/pending/envs/prod/gl/values.yaml`
- `chart.version: M` in the `config.yaml` beside each of those five files
- `envs/_template/README.md`
- `tests/test_promote.py` (the fixture names), `.gitlab-ci.yml` (one comment)
- `docs/design.md`, `docs/implementation-plan.md`: copied from the base product's repository at tag `vM`
  and compared, not edited in place; `README.md`: the "last synced" date

Every file that gets the new name gets the pin `M` with it, not only the stagings. The
template pins 4.0.42 and the parked production environments pin 4.0.38 today, and the
new repositories begin at `M`: `niord-app-dk:4.0.42` and `niord-app-dk:4.0.38` will
never exist. With the name alone, a test environment copied from the template, or a
production environment moved out of `reference/pending`, could not pull its app image.
The parked pins are otherwise written by the promote workflow. This once they move by
hand, and the environments stay parked. Nothing catches a missed file: `promote.py`
compares the repository names of staging and prod only when their pins differ, and it
does not read the parked files.

The repository name and the pin must change together. A chart `M` with the old name in
the values asks for `niord-dk-be:M`, which does not exist. Renovate does not run
against devops-niord (its `renovate.json5` says so in its header, and the history has
no bot commit), so nothing bumps a pin between step 2 and this merge request, and
nobody does it by hand. The lint pipeline would not notice a pin without the name: it
renders the chart and does not ask Harbor for the image. So, before the merge: check
that `niord/niord-app-dk:M` and `niord/niord-app-gl:M` are in Harbor.

One merge request in devops-shared: `runbooks/bootstrap.md`, the paragraph on the
`github-push` robot, names the three images.

**Proof.** Both stagings run `niord/niord-app-<cc>:M`; `kubectl describe pod` shows the
image; the web app answers. For a few minutes after the merge a pod may show
`ImagePullBackOff` on `niord/niord-app-<cc>:<old pin>`: Argo CD has the new values before
the ApplicationSet has moved the pin. It heals by itself; wait for the Application's
target revision to read `M` before judging.

**Rollback.** If the new pod never started (an image pull error), revert the merge
request: no migration ran, and the old pin and the old name come back together. The
`-be` image of that version is in Harbor until step 4. If `M` started and then
misbehaves, roll forward, as `runbooks/rollback.md` says for staging.

### Step 4 — Harbor by hand, and niord: the old repositories go

Not at once. The conditions:

- a later version than `M` is published, so that one version can be retracted while
  another is pinned;
- both stagings have run a version from the new repositories for a week;
- no file and no open merge request in devops-niord pins a version older than `M`;
- no tag immutability rule covers the `-be` repositories (open check 2).

Then:

1. Run the Retract version workflow for every version older than `M` that is still
   published. It removes the tag and the `niord-parent` and `niord-base` versions, and
   its summary lists the Harbor artefacts. This comes before item 3, which takes the
   old names out of that list.
2. In the Harbor UI (project admin; the robots cannot delete): delete what the
   summaries list, the three repositories `niord/niord-app-be`, `niord/niord-dk-be`
   and `niord/niord-gl-be`, and in `charts/niord` every chart version older than `M`.
   Those charts default to the `-be` image names and would point at nothing. The space
   is freed at the host's next Harbor garbage collection.
3. One pull request in this repository: `retract-version.yml` drops the `-be` names from its
   header and its summary list.

After this step a rollback to a version older than `M` is no longer possible.

### Rollback order

A step's rollback is a plain revert only until the next step merges.

- **Step 3 alone:** as written there. Possible until step 4.
- **Step 2 after step 3:** revert step 2 in this repository; when that publish exists as
  version `P`, one devops-niord merge request puts the `-be` names back together with
  the pin `P`. After step 4: do not revert, roll forward.
- **Step 1 after step 2:** not `git revert`, which conflicts and would bring `-be`
  back into the workflow under a chart that defaults to the new name. A new pull
  request restores the single-jar `Dockerfile` and image step and keeps the names of
  step 2.

---

## 5. Expected result

| | Today | After step 1 |
|---|---|---|
| New in Harbor per publish, dependencies unchanged | about 490 MB | about 10 MB |
| New in Harbor per publish, dependencies changed | about 490 MB | about 170 MB |
| New in Harbor when the base image is refreshed | the temurin layers | the temurin layers |
| 29 versions (the 2026-09-30 count) | 14.1 GB | about 1 GB |
| The two versions kept today | about 1.0 GB | about 0.2 to 0.35 GB |
| A node pulling a new version | about 160 MB per image | about 5 MB per image |
| The `deployable-jars` artifact | 532 MB, 11 to 50 s to fetch | about 185 MB |
| The image step of the `harbor` job | 63 to 87 s (runs 4.0.38 and 4.0.42) | 56 s (run 4.0.48, including the reproducibility check) |

Per month Harbor grows by about 10 MB × publishes + 158 MB × dependency changes. The
projection for 29 versions assumes four dependency layers (the first, and one for each
of the three changes in §2.1). The two kept versions share a dependency layer or not,
hence the range.

---

## 6. Risks and open checks

**Risks**

- **The class path layout** (§3.4). The integration tests do not run the packaged
  image, so the local cluster run in step 1 is the only proof before staging.
- **The layer does not come out equal on the real runner or in Harbor.** It did in
  every daemon tried, but a GitHub runner and Harbor were not among them. A
  time-dependent layer fails the job (§3.5). A layer that is stable in one run and
  different in the next would pass the check and store 158 MB per publish: a third of
  today, with no failure. The summary line and step 1's proof after the merge show
  which it is. The fallback is the dependency image of the first draft, built from the
  same tree so that its layer does not depend on the base image.
- **A merge while a publish is in flight** loses a tag or breaks a build (§4, "Before
  each merge").
- **The first publish is the first run of the changed workflow.** The script is proved
  before the merge, the workflow around it is not. Step 1 says what a failure leaves
  behind and how to get out of it.
- **The runner image moves under the plan** (§3.8).

**Open checks**

1. The first two publishes of step 1 in Harbor: the same dependency layer, and less
   than 15 MB added by the second. This is step 1's proof after the merge; it cannot
   be read earlier, because only main may push to Harbor.
2. Does the Harbor project have a tag immutability or retention rule that matches
   `niord/**`? An immutable tag cannot be deleted, so such a rule would refuse step 4.
   devops-shared `runbooks/bootstrap.md` (the CI push robot) records that none is set.
   Needs a project admin to confirm, before step 4.
3. When does the host run Harbor's garbage collection? Deleting frees nothing until it
   has run, and it needs a system administrator.

---

## 7. The repository split

The repository split is not part of this plan and has no date.
`be/docs/cicd/design.md` §2.1 and Phase 1 of `be/docs/cicd/implementation-plan.md`
describe it: this repository becomes `niord` (the library, `niord-app`, the chart),
`niord-dk` and `niord-gl`. Each country repository then builds its module against
`niord-base` from GitHub Packages and pushes its own image from its own pipeline. This
section says what the plan does now so that the repository split needs no rework of
the images, and what it has to settle itself.

### 7.1 What this plan does for it

- **Sharing needs no coordination.** A country pipeline that splits the same jars with
  the same scripts builds the same dependency layer, and Harbor stores it once. There
  is no tag to agree on, no key, and no shared image that one pipeline could replace
  under another. A pipeline that pulls another temurin still shares the layer.
- **The scripts know no module list and no monorepo path.** `split-jar.sh` takes a jar
  and an output directory. `publish-images.sh` takes the modules as arguments and its
  directories from the environment. A country pipeline calls the same two scripts with
  its one module.
- **Everything the image needs is under `be/k8s/`**: the `Dockerfile` and the scripts.
  That is the folder `niord` keeps (design §2.1), and a sparse checkout of it is all a
  pipeline needs, as the `harbor` job's is today.
- **The `niord-base` guard goes by file name**, not by the build's own version and not
  by Spring Boot's layer index (§3.3). After the repository split a country build
  holds `niord-base-4.0.<base run>.jar` under a version of its own, and the jar still
  goes to its own layer.
- **The rename comes first.** The country pipelines then start with
  `niord/niord-app-dk` and `niord/niord-app-gl`, and the rename is made in one
  workflow and not in three.

### 7.2 What the repository split has to settle

1. **Call the files, do not copy them.** Design §2.1 lets a country repository "call
   them or copy them". The layer's bytes come from the tree that `publish-images.sh`
   makes and from the `COPY` line of the `Dockerfile`, so a copy that drifts stores a
   158 MB layer of its own, and no step fails. A country pipeline should check out
   `be/k8s` from `niord` at the tag of the base version it pins. A version can be
   published without its tag (`publish.yml`, the `tag` job); the checkout then fails
   until the tag is pushed by hand, which is the wanted behaviour and not a reason to
   fall back to main. `build-image.sh` is the monorepo's local build and gets a
   country variant then. *Settled 2026-10-06: the four files travel as the
   package `org.niord:niord-image-tools:4.0.<run>` (zip) in `NiordOrg/niord`'s
   registry, published by the build job beside `niord-base`, and the country pipeline
   unpacks it at the base version it pins; since the code/data/config plan's C5b the zip
   also carries the backend override check (`check-overrides.sh`, `CheckOverrides.java`).
   A country repository cannot check the
   private product repository out without a credential of it; a package needs none,
   and a package exists only for a published version, so the tag question falls away.*
2. **The same jars need the same dependency management, and that is a correctness
   question first.** The three sets are identical today because the three poms inherit
   `niord-parent` and each carries the same deployment dependencies and the same
   `artemis.version` override; that override is per module, not in `niord-parent`. A
   country pom resolves the same set only if it inherits `org.niord:niord-parent` at
   the base version it pins. A parent built on `spring-boot-starter-parent` gives
   other versions of Jackson and FreeMarker than `niord-base` was tested with,
   including the Jackson releases that `be/pom.xml` overrides for a security advisory.
   The design's "instead of inheriting the parent" (§2.1) should be decided again with
   this in mind. A country's `deps.sha256` compared with the one `niord` published for
   that base version makes a difference visible. *Settled 2026-10-06 (design §2.1): the
   country poms inherit `niord-parent` at the base version they pin.*
3. **The `niord-base` layer is shared by the three images only while one reactor
   builds them.** After the repository split each country stores it per publish:
   3 MB.
4. **The web bundle.** Until Phase 8 `niord`'s CI builds every country's
   administration app; afterwards the country repository does (implementation plan,
   Phase 1 and 8). Open since 2026-09-26: how a bundle built in `niord` reaches the
   country pipeline that builds the image. `publish-images.sh` takes the bundle as a
   directory either way. *Settled 2026-10-06 (design §2.1) and built the same day:
   `publish.yml` deploys the three bundles to GitHub Packages beside
   `niord-base` as `org.niord.web:niord-web-admin`, a zip per country under its
   classifier, and the country pipeline unpacks its bundle at the base version it
   pins; the directory becomes `WEB_DIR`. Since the country split's P5–P7 each
   country builds its own administration bundle from `niord-fe-src` (DK since P5,
   2026-10-22; GL since P6, 2026-10-23), and `publish.yml` deploys the `en` bundle
   only: `niord-web-admin` carries only the `en` classifier since P7 (4.0.97).*
5. **Robots.** devops-shared `runbooks/bootstrap.md` plans one push robot per
   repository. Nothing to add: no Harbor repository is shared between the pipelines.

---

## 8. Not in this plan

- Skipping the upload of the third-party jars between the jobs. The `build` job holds
  no Harbor credential and cannot ask what Harbor has; the artifact is deleted minutes
  later, so the gain is seconds.
- An AOT cache or class data sharing for a faster start. The split layout is what both
  need, so they become possible, nothing more.
- Publishing the public site's images to Harbor.
- The repository split itself (§7).
- Labels inherited from the base image. The app image carries temurin's and Ubuntu's
  title and description labels today and keeps them.

---

## 9. Record

The plan enters the repository with a docs-only pull request before step 1, which
starts no CI and no publish, and the `docs/` row of the root `CLAUDE.md` names it.
After each step the status line gets the date, the version and the number of the pull
or merge request, and §5 gets the measured values.

---

## 10. References

- `be/k8s/docker/Dockerfile`, `be/k8s/scripts/build-image.sh`, `.github/workflows/publish.yml`,
  `.github/workflows/retract-version.yml`, `be/k8s/helm/niord/values.yaml`
- `be/docs/cicd/design.md` §2 (repositories and images), §2.1 (the repository split),
  §3 (versioning), §5 (pipelines); `be/docs/cicd/implementation-plan.md`, Phase 1
- Spring Boot reference, "Efficient deployments: unpacking the executable jar"
- actions/runner-images issue 14748 (`ubuntu-latest` becomes Ubuntu 26.04)
- devops-niord: `envs/`, `scripts/promote.py`, `renovate.json5`, `runbooks/rollback.md`;
  devops-shared: `runbooks/bootstrap.md` (Robots)

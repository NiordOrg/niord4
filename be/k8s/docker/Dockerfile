# -----------------------------------------------------------------------------
#  Runtime image for a Niord deployable (niord-app here; a country's deployable in its own
#  repository, which gets this file through the niord-image-tools package).
#
#  The executable jar is unpacked before the build (scripts/split-jar.sh) and comes in
#  as three named build contexts, so that the layers that never change are the same
#  bytes on every build and the registry stores them once (docs/image-split-plan.md):
#
#    deptree   the third-party jars as a directory tree, app/lib/*.jar plus an empty
#              niord-home/, every entry at one fixed time and with normalised modes
#              (scripts/publish-images.sh makes it) — the dependency layer, ~158 MB,
#              new only when a dependency version changes
#    app       app.jar, the thin jar with the module's own classes and a manifest
#              Class-Path naming lib/*.jar, and lib/niord-base-<version>.jar — two small
#              layers, new on every publish
#    web       the deployable's build of the administration web app (fe/ui-apps/admin:
#              the generic build for niord-app; a country's deployable its own build),
#              served by the backend at the root of its
#              origin (WebAppConfig, niord.web-app.dir). An empty directory gives an
#              image that serves the API only.
#
#  Build it through scripts/publish-images.sh (CI, and scripts/build-image.sh locally),
#  which makes the tree, checks that the dependency layer is reproducible and checks
#  the built image's content. The build context itself (this directory) carries nothing
#  the image needs; its .dockerignore keeps everything out.
#
#  No `# syntax=` line: it would make every build fetch the Dockerfile frontend from
#  Docker Hub, and nothing here needs a newer one than Docker's own.
# -----------------------------------------------------------------------------
ARG BASE_IMAGE=eclipse-temurin:25-jre
FROM ${BASE_IMAGE} AS deps

# The dependency layer. The directories /app, /app/lib and /niord-home come from the
# tree, with its fixed time, and are not created here: a plain `docker build` stamps
# every directory it creates with the build time, which would give this layer a new
# digest on every run. For the same reason there is no RUN line anywhere in this file.
# Nothing above this line may create /app or /niord-home. The chart's pod
# securityContext pins uid/gid 1000 (which exists as `ubuntu` in the base image), so
# the app's directories are owned by 1000.
COPY --chown=1000:1000 --from=deptree . /

FROM deps
WORKDIR /app
# niord-base first and alone: the file is the same for every image built on this base version,
# the country images included, so they share this layer. Then the module's own jar.
COPY --chown=1000:1000 --from=app lib/ /app/lib/
COPY --chown=1000:1000 --from=app app.jar /app/app.jar
# The backend serves the web app only if /app/web holds its index.html, so the variable
# is safe to set in every image, the API-only ones included.
COPY --chown=1000:1000 --from=web . /app/web/
ENV NIORD_WEB_APP_DIR=/app/web
# The JVM runs in UTC: the database stores times without a zone, written and read in the
# JVM's zone, and the data is in UTC (the owner's ruling of 2026-10-25; be/README.md, its
# time-zone paragraph). The chart sets it on the pod too. ENV is image metadata, so the dependency layer keeps its digest.
ENV TZ=UTC

USER 1000:1000
EXPOSE 8888

# JVM flags are supplied via JAVA_TOOL_OPTIONS, Spring config via
# SPRING_CONFIG_ADDITIONAL_LOCATION + env vars (set by the Helm chart).
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

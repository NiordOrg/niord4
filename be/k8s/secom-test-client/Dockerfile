# syntax=docker/dockerfile:1
# Runtime image for the SECOM test client (see k8s/scripts/secom-test-client.sh, which
# builds the shaded jar on the host first - same fast-build pattern as ../docker/Dockerfile).
FROM eclipse-temurin:25-jre

RUN mkdir -p /app /data && chown -R 1000:1000 /app /data

WORKDIR /app
COPY --chown=1000:1000 target/secom-test-client.jar /app/app.jar

USER 1000:1000
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

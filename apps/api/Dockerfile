FROM bellsoft/liberica-openjdk-debian:25@sha256:213512c11989ebdcf464c475603f6381af2a447338a2a04381c14b6e786d8c94 AS build

WORKDIR /workspace

COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY apps/api/build.gradle.kts ./apps/api/build.gradle.kts

RUN ./gradlew :apps:api:dependencies --no-daemon

COPY apps/api/src ./apps/api/src

RUN ./gradlew :apps:api:bootJar --no-daemon

FROM gcr.io/distroless/java25-debian13:nonroot@sha256:fce4a1d66284e8866c46113d9bdc286c46fb8c3c3f0a098f877034349e88debe

WORKDIR /app

COPY --from=build --chown=65532:65532 /workspace/apps/api/build/libs/tieat-api.jar ./tieat-api.jar

USER 65532:65532
EXPOSE 8080

ENTRYPOINT ["/usr/bin/java", "-jar", "/app/tieat-api.jar"]

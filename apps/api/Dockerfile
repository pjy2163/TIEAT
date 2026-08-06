FROM bellsoft/liberica-openjdk-debian:25 AS build

WORKDIR /workspace

COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY apps/api/build.gradle.kts ./apps/api/build.gradle.kts

RUN ./gradlew :apps:api:dependencies --no-daemon

COPY apps/api/src ./apps/api/src

RUN ./gradlew :apps:api:bootJar --no-daemon

FROM bellsoft/liberica-openjre-debian:25

WORKDIR /app

RUN useradd --system --uid 10001 tieat

COPY --from=build --chown=tieat:tieat /workspace/apps/api/build/libs/tieat-api.jar ./tieat-api.jar

USER tieat
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/tieat-api.jar"]

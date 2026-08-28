FROM bellsoft/liberica-openjdk-debian:25@sha256:213512c11989ebdcf464c475603f6381af2a447338a2a04381c14b6e786d8c94 AS build

WORKDIR /workspace

COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY apps/api/build.gradle.kts ./apps/api/build.gradle.kts

RUN ./gradlew :apps:api:dependencies --no-daemon

COPY apps/api/src ./apps/api/src

RUN ./gradlew :apps:api:bootJar --no-daemon

FROM bellsoft/liberica-openjre-debian:25@sha256:3e00b4d7db5abbd993709b316d1c23a2055c9a79b94f3e7532f361e6da6f3a7f

WORKDIR /app

RUN useradd --system --uid 10001 tieat

COPY --from=build --chown=tieat:tieat /workspace/apps/api/build/libs/tieat-api.jar ./tieat-api.jar

USER tieat
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/tieat-api.jar"]

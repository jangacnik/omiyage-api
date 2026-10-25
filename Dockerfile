FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

COPY gradlew ./
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./
RUN ./gradlew --no-daemon dependencies

COPY src src
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --create-home --shell /usr/sbin/nologin omiyage
WORKDIR /app
COPY --from=build --chown=omiyage:omiyage /app/build/libs/*.jar app.jar
USER 10001
ENV JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=60

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]

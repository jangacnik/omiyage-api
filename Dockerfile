FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

COPY gradlew ./
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./
RUN ./gradlew --no-daemon dependencies

COPY src src
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre
RUN useradd --system --create-home --shell /usr/sbin/nologin omiyage
WORKDIR /app
COPY --from=build --chown=omiyage:omiyage /app/build/libs/*.jar app.jar
USER omiyage

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]

# Stage 1: Build the Ktor application
FROM gradle:8.5-jdk17 AS build
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src

# Fix line endings and set executable permissions for Linux
USER root
RUN apt-get update && apt-get install -y dos2unix
RUN dos2unix gradlew
RUN chmod +x gradlew

USER gradle
RUN ./gradlew installDist --no-daemon

# Stage 2: Run using official Eclipse Temurin JRE
FROM eclipse-temurin:17-jre
EXPOSE 8080
COPY --from=build /home/gradle/src/app/build/install/app /app
WORKDIR /app
CMD ["./bin/app"]
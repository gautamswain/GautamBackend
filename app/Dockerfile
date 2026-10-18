# Stage 1: Build the application using Gradle
FROM gradle:8.5-jdk17 AS build
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
RUN ./gradlew installDist --no-daemon

# Stage 2: Create a lightweight runtime image
FROM openjdk:17-slim
EXPOSE 8080
COPY --from=build /home/gradle/src/app/build/install/app /app
WORKDIR /app
CMD ["./bin/app"]
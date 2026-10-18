# Stage 1: Build the Ktor application from root context
FROM gradle:8.5-jdk17 AS build
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
RUN gradle :app:installDist --no-daemon

# Stage 2: Run using Eclipse Temurin JRE
FROM eclipse-temurin:17-jre
EXPOSE 8080
COPY --from=build /home/gradle/src/app/build/install/app /app
WORKDIR /app
CMD ["./bin/app"]
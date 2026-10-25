# Stage 1: Build the JAR with Maven
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests
# Stage 2: Run the JAR in a slim runtime image
FROM eclipse-temurin:21-jre-alpine
# Fixes the one finding the container scan reported in this base image:
# brotli-libs 1.2.0-r1 carries CVE-2026-111497 (HIGH, out-of-bounds write in the
# Brotli decoder), fixed upstream in 1.2.1-r0. Upgrading the alpine packages at
# build time picks that up without waiting for a new upstream image tag.
# Confirmed by the container-scan job: 1 HIGH before, 0 HIGH after.
RUN apk upgrade --no-cache
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]

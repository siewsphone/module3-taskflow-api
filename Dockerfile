# Stage 1: Build the JAR with Maven
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests
# Stage 2: Run the JAR in a slim runtime image
FROM eclipse-temurin:21-jre-alpine
# Trivy flagged brotli-libs 1.2.0-r1 (CVE-2026-111497, HIGH, fixed in 1.2.1-r0) in
# this base image. Upgrading the alpine packages at build time is what picks up the
# fix without waiting for a new upstream tag; the scan is re-run in CI afterwards.
RUN apk upgrade --no-cache
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]

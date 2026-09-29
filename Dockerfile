# syntax=docker/dockerfile:1
#
# Multi-stage build for the Spring Boot application.
#
# Build:
#   docker build -t moac-reconciliation-service .
# Run:
#   docker run -i --rm -p 7074:7074 moac-reconciliation-service
#
# JVM options can be passed via JAVA_OPTS, e.g.:
#   docker run -e JAVA_OPTS="-Xmx512m" -p 7074:7074 moac-reconciliation-service

# ---- Build stage --------------------------------------------------------
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

# Resolve dependencies first so this layer is cached across source-only changes
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src src
RUN ./mvnw -B -q clean package -DskipTests

# ---- Runtime stage -------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /workspace/target/moac-reconciliation-service-*.jar /app/app.jar
RUN chown app:app /app/app.jar
USER app

EXPOSE 7074
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

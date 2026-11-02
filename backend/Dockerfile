# Spring Boot backend image (Java 17). Build context is backend/:
#   docker build -t budget-api backend
# Stage 1 builds the JAR with Maven (tests need PostgreSQL, so they run in CI/locally, not here).
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

# Stage 2: JRE only.
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --create-home --uid 10001 app
COPY --from=build /build/target/budget-backend-1.0.0.jar /app/app.jar
USER app

# Cloud Run sets PORT (application.yml: server.port=${PORT:...}). Heap sized from the container memory limit.
ENV PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

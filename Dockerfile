# Multi-stage build for DashboardSummary Java 17 application
# Build stage
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Copy Gradle wrapper and build files first to leverage Docker layer caching
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle build.gradle ./

# Ensure the Gradle wrapper is executable
RUN chmod +x gradlew

# Copy source code
COPY src ./src

# Build a self-contained application distribution (bin + libs)
RUN ./gradlew --no-daemon clean installDist

# Runtime stage
FROM eclipse-temurin:21-jre
WORKDIR /app

# Copy the application distribution produced by the build stage
COPY --from=build /app/build/install/DashboardSummary /app

# Optional: place for additional JVM options
ENV JAVA_OPTS=""

# Default command runs the application. Environment variables are read by the app at runtime.
ENTRYPOINT ["./bin/DashboardSummary"]

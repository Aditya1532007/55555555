# Build stage: Java 21 + Maven
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

# Runtime stage: Java 21
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 seekora
COPY --from=build /app/target/*.jar app.jar
USER seekora

# Production profile: PostgreSQL, secure cookies. It fails fast if the database variables are missing,
# rather than silently starting on a throw-away local database.
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 10000
# Render injects PORT (default 10000); bind to all interfaces so its proxy can reach the app.
ENTRYPOINT ["sh", "-c", "exec java -XX:MaxRAMPercentage=75 -Dserver.address=0.0.0.0 -Dserver.port=${PORT:-10000} -jar app.jar"]

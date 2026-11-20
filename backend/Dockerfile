# ---------- Stage 1: build ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Cache dependencies in their own layer (only re-downloaded when pom.xml changes)
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B package -DskipTests

# ---------- Stage 2: runtime ----------
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Run as an unprivileged user
RUN addgroup -S chat && adduser -S chat -G chat \
    && mkdir -p /app/uploads && chown -R chat:chat /app

COPY --from=build /build/target/chat-backend-*.jar app.jar
RUN chown chat:chat app.jar
USER chat

EXPOSE 8080
ENV UPLOAD_DIR=/app/uploads

# Container-aware heap sizing; busybox wget powers the Docker healthcheck
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]

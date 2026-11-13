# syntax=docker/dockerfile:1
# One Dockerfile for every service: docker build --build-arg MODULE=order-service .

FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
ARG MODULE
# Build files first so the dependency layer is cached while only sources change.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY common-events/pom.xml common-events/
COPY service-support/pom.xml service-support/
COPY api-gateway/pom.xml api-gateway/
COPY auth-service/pom.xml auth-service/
COPY order-service/pom.xml order-service/
COPY inventory-service/pom.xml inventory-service/
COPY payment-service/pom.xml payment-service/
COPY notification-service/pom.xml notification-service/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -pl ${MODULE} -am dependency:go-offline -DskipTests || true
COPY . .
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -pl ${MODULE} -am package -DskipTests \
    && cp ${MODULE}/target/${MODULE}-*.jar /build/app.jar

FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 1001 app && useradd --system --uid 1001 --gid 1001 app
WORKDIR /app
COPY --from=build --chown=app:app /build/app.jar app.jar
USER app
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70"
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=12 \
    CMD curl -fsS http://localhost:${SERVER_PORT:-8080}/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

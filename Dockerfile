# One multi-stage build for both runnable services. docker compose picks the stage with `target:`.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY config-core config-core
COPY config-client config-client
COPY load-simulator load-simulator
COPY config-server config-server
COPY demo-booking-service demo-booking-service
COPY benchmarks benchmarks
RUN chmod +x mvnw && ./mvnw -B -q -DskipTests package -pl config-server,demo-booking-service -am

FROM eclipse-temurin:25-jre AS runtime-base
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home /app app && mkdir -p /app/data && chown -R app /app
WORKDIR /app
USER app

FROM runtime-base AS config-server
COPY --from=build /src/config-server/target/config-server-1.0.0.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]

FROM runtime-base AS demo-booking-service
COPY --from=build /src/demo-booking-service/target/demo-booking-service-1.0.0.jar /app/app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]

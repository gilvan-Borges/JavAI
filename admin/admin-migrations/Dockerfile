# Contêiner de uma vez só: bootstrap dos papéis e Flyway do banco do CRUD, depois sai (spec 02).
FROM eclipse-temurin:25-jre
LABEL org.opencontainers.image.source="https://github.com/gilvan-Borges/JavAI"

RUN groupadd --system --gid 10001 javai \
    && useradd --system --uid 10001 --gid javai --no-create-home --shell /usr/sbin/nologin javai

WORKDIR /app
COPY target/admin-migrations-*-exec.jar /app/migrate.jar

USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+UseCompactObjectHeaders -Djava.io.tmpdir=/tmp"

ENTRYPOINT ["java", "-jar", "/app/migrate.jar"]

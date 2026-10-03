# Imagem de runtime do admin-api. O jar vem do Maven no CI: nada é compilado na VPS (spec 01).
FROM eclipse-temurin:25-jre
LABEL org.opencontainers.image.source="https://github.com/gilvan-Borges/JavAI"

RUN groupadd --system --gid 10001 javai \
    && useradd --system --uid 10001 --gid javai --no-create-home --shell /usr/sbin/nologin javai \
    # O volume do snapshot herda este dono na primeira montagem; o Caddy monta o mesmo volume só para leitura.
    && mkdir -p /srv/conteudo/v1/snapshot \
    && chown -R javai:javai /srv/conteudo

WORKDIR /app
COPY target/admin-api.jar /app/app.jar

USER 10001:10001

# A memória nativa (threads, metaspace) fica fora do heap: 60% do limite do contêiner para o heap deixa folga.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+UseCompactObjectHeaders -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp"

EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

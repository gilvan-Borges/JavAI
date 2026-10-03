# Imagem de runtime do ai-indexer. O jar vem do Maven no CI: nada é compilado na VPS (spec 01).
FROM eclipse-temurin:25-jre
LABEL org.opencontainers.image.source="https://github.com/gilvan-Borges/JavAI"

RUN groupadd --system --gid 10001 javai \
    && useradd --system --uid 10001 --gid javai --no-create-home --shell /usr/sbin/nologin javai \
    # O modelo de embeddings (118 MB) é baixado uma vez, conferido por SHA-256 e guardado neste volume.
    && mkdir -p /models \
    && chown javai:javai /models

WORKDIR /app
COPY target/ai-indexer.jar /app/app.jar

USER 10001:10001

# O ONNX Runtime e o tokenizador da DJL extraem as bibliotecas nativas do jar para o diretório temporário e as
# carregam de lá: por isso o /tmp do contêiner precisa ser montado com exec (docker-compose.yml). A DJL também guarda
# um cache em DJL_CACHE_DIR, e o usuário do contêiner não tem diretório pessoal.
ENV DJL_CACHE_DIR=/tmp/.djl.ai
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+UseCompactObjectHeaders -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp"

EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

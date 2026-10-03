# Imagem de runtime do ai-api. O jar vem do Maven no CI: nada é compilado na VPS (spec 01).
FROM eclipse-temurin:25-jre
LABEL org.opencontainers.image.source="https://github.com/gilvan-Borges/JavAI"

RUN groupadd --system --gid 10001 javai \
    && useradd --system --uid 10001 --gid javai --no-create-home --shell /usr/sbin/nologin javai

WORKDIR /app
COPY target/ai-api.jar /app/app.jar

USER 10001:10001

# O ONNX Runtime e o tokenizador da DJL extraem as bibliotecas nativas do jar para o diretório temporário e as
# carregam de lá: por isso o /tmp do contêiner precisa ser montado com exec (docker-compose.yml). A DJL também guarda
# um cache em DJL_CACHE_DIR, e o usuário do contêiner não tem diretório pessoal.
# --add-modules jdk.incubator.vector liga o cosseno por SIMD (a busca cai para o escalar se faltar).
ENV DJL_CACHE_DIR=/tmp/.djl.ai
ENV JAVA_TOOL_OPTIONS="--add-modules=jdk.incubator.vector -XX:MaxRAMPercentage=60 -XX:+UseCompactObjectHeaders -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp"

EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

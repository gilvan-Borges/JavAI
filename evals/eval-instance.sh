#!/usr/bin/env bash
# Sobe (ou derruba) uma instância de AVALIAÇÃO do ai-api na VPS, para a bateria rodar contra o pipeline real: o
# índice real, o Jev real e o OpenRouter real. A produção não tem atalho para a bateria (specs/05), então a bateria
# nunca fala com ela; fala com esta instância, que:
#   - escuta só em 127.0.0.1 (a bateria chega por túnel SSH);
#   - usa a chave de TESTE do Turnstile (sempre aprova), com o hostname example.com;
#   - tem limites altos e o próprio par de modelos, para comparar modelos sem mexer na produção;
#   - lê o índice com o mesmo papel ai_reader e roda endurecida (somente leitura, sem capabilities).
#
# Opcionais (variáveis de ambiente): EVAL_REASONING=low|minimal|... e EVAL_MAX_TOKENS=800, para modelos de raciocínio.
# Uso (na VPS, como root):  eval-instance.sh up <porta> <modelos-rapidos> <modelos-fortes> [teto-preco-saida]
#                           eval-instance.sh down <porta>
set -euo pipefail

action="${1:?up|down}"
port="${2:?porta}"
name="javai-eval-${port}"

if [[ "$action" == down ]]; then
  docker rm -f "$name" >/dev/null 2>&1 || true
  echo "instância $name removida"
  exit 0
fi

fast="${3:?modelos rápidos, separados por vírgula}"
strong="${4:?modelos fortes, separados por vírgula}"
cd /opt/javai/infra
set -a
# shellcheck disable=SC1091
. ./.env
set +a

docker rm -f "$name" >/dev/null 2>&1 || true
# Rede com saída para a internet como principal (é a que permite publicar a porta); a do banco entra em seguida.
docker run -d --name "$name" \
  --network javai_ai-egress \
  -p "127.0.0.1:${port}:8080" \
  --read-only --tmpfs /tmp:exec,mode=1777,size=128m \
  --cap-drop ALL --security-opt no-new-privileges:true -m 512m \
  -v javai_ai-models:/models:ro \
  -e AI_DB_URL='jdbc:postgresql://postgres-ai:5432/ai?logServerErrorDetail=false&ApplicationName=ai-eval' \
  -e AI_READER_PASSWORD="$AI_READER_PASSWORD" \
  -e AIAPI_MODEL_DIR=/models \
  -e AIAPI_CHAT_OPENROUTER_KEY="$OPENROUTER_API_KEY" \
  -e AIAPI_CHAT_TURNSTILE_SECRET=1x0000000000000000000000000000000AA \
  -e AIAPI_CHAT_TURNSTILE_HOSTNAMES=example.com \
  -e AIAPI_CHAT_HISTORY_SECRET="$(openssl rand -hex 24)" \
  -e AIAPI_CHAT_MODELS_FAST="$fast" \
  -e AIAPI_CHAT_MODELS_STRONG="$strong" \
  -e AIAPI_CHAT_MAX_PRICE_COMPLETION="${5:-${JAVAI_MAX_PRICE_COMPLETION:-1}}" \
  -e AIAPI_CHAT_DAILY_BUDGET_USD=5 \
  ${EVAL_REASONING:+-e AIAPI_CHAT_REASONING_EFFORT=$EVAL_REASONING} ${EVAL_MAX_TOKENS:+-e AIAPI_CHAT_MAX_TOKENS=$EVAL_MAX_TOKENS} \
  -e AIAPI_CHAT_PER_MINUTE=1000 -e AIAPI_CHAT_PER_DAY=100000 \
  "ghcr.io/gilvan-borges/javai-ai-api:${JAVAI_TAG}" >/dev/null
docker network connect javai_ai-data "$name"

for _ in $(seq 1 40); do
  if curl -fsS "http://127.0.0.1:${port}/health" >/dev/null 2>&1; then
    echo "instância $name pronta em 127.0.0.1:${port} (rápidos: $fast | fortes: $strong)"
    exit 0
  fi
  sleep 3
done
echo "a instância $name não ficou pronta" >&2
docker logs --tail 30 "$name" >&2 || true
exit 1

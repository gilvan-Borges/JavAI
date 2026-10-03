-- Operação da IA (spec 02). Nada aqui identifica um visitante: não existe tabela de perguntas.

-- Contadores agregados por dia e modelo. O ai-api grava a partir da Fase 4.
CREATE TABLE ops.usage_daily (
    day            date          NOT NULL,
    model          text          NOT NULL,
    requests       integer       NOT NULL DEFAULT 0 CHECK (requests >= 0),
    refusals       integer       NOT NULL DEFAULT 0 CHECK (refusals >= 0),
    retracts       integer       NOT NULL DEFAULT 0 CHECK (retracts >= 0),
    tokens_in      bigint        NOT NULL DEFAULT 0 CHECK (tokens_in >= 0),
    tokens_out     bigint        NOT NULL DEFAULT 0 CHECK (tokens_out >= 0),
    cost_usd       numeric(12,6) NOT NULL DEFAULT 0 CHECK (cost_usd >= 0),
    latency_ms_p50 integer       CHECK (latency_ms_p50 >= 0),
    latency_ms_p90 integer       CHECK (latency_ms_p90 >= 0),
    mcp_calls      integer       NOT NULL DEFAULT 0 CHECK (mcp_calls >= 0),
    PRIMARY KEY (day, model)
);

-- Execuções do indexador. O relatório guarda contagens e motivos (repositório pulado por tamanho, arquivo com
-- segredo), nunca valores de conteúdo nem segredos.
CREATE TABLE ops.index_job (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id  bigint      REFERENCES index.version (id) ON DELETE SET NULL,
    trigger     text        NOT NULL CHECK (trigger IN ('snapshot', 'agendado', 'manual')),
    status      text        NOT NULL DEFAULT 'running' CHECK (status IN ('running', 'succeeded', 'failed', 'skipped')),
    started_at  timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    report      jsonb       NOT NULL DEFAULT '{}'
);

CREATE INDEX index_job_por_data ON ops.index_job (started_at);

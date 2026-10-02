-- Operação (spec 02). Nada aqui identifica um visitante: não existe tabela de perguntas.

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

-- Toda escrita feita no admin. Só aceita novas linhas (as permissões em V5 não dão UPDATE nem DELETE).
CREATE TABLE ops.audit_log (
    id        bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    at        timestamptz NOT NULL DEFAULT now(),
    actor     text        NOT NULL CHECK (char_length(actor) BETWEEN 3 AND 320),
    action    text        NOT NULL CHECK (action IN (
                              'create', 'update', 'delete', 'status', 'publish', 'activate', 'repo')),
    entity    text        NOT NULL CHECK (char_length(entity) BETWEEN 1 AND 60),
    entity_id text        CHECK (char_length(entity_id) BETWEEN 1 AND 200),
    diff      jsonb
);

CREATE INDEX audit_log_por_data ON ops.audit_log (at);

-- Jobs de publicação. O relatório guarda contagens e motivos, nunca valores de conteúdo ou segredos.
CREATE TABLE ops.index_job (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    index_version_id bigint      REFERENCES ai.index_version (id) ON DELETE SET NULL,
    trigger          text        NOT NULL CHECK (trigger IN ('manual', 'agendado')),
    started_at       timestamptz NOT NULL DEFAULT now(),
    finished_at      timestamptz,
    status           text        NOT NULL DEFAULT 'running' CHECK (status IN ('running', 'succeeded', 'failed')),
    actor            text        CHECK (char_length(actor) BETWEEN 3 AND 320),
    report           jsonb       NOT NULL DEFAULT '{}'
);

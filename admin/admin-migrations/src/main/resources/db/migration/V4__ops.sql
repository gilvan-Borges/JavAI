-- Operação do CRUD (spec 02).

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

-- Jobs de publicação. O relatório guarda contagens e motivos, nunca valores de conteúdo.
CREATE TABLE ops.publish_job (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id  bigint      REFERENCES publish.version (id) ON DELETE SET NULL,
    trigger     text        NOT NULL CHECK (trigger IN ('manual', 'agendado')),
    status      text        NOT NULL DEFAULT 'running' CHECK (status IN ('running', 'succeeded', 'failed')),
    actor       text        CHECK (char_length(actor) BETWEEN 3 AND 320),
    started_at  timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    report      jsonb       NOT NULL DEFAULT '{}'
);

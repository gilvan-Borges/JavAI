-- Índice gerado pela publicação (spec 02). O indexador (javai_admin) escreve; a IA (javai_ai) só lê
-- a versão ativa, filtrada por RLS.

CREATE TABLE ai.index_version (
    id              bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    status          text        NOT NULL DEFAULT 'building'
                                CHECK (status IN ('building', 'active', 'retired', 'failed')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    activated_at    timestamptz,
    embedding_model text,
    stats           jsonb       NOT NULL DEFAULT '{}'
);

-- No máximo uma versão ativa.
CREATE UNIQUE INDEX index_version_uma_ativa ON ai.index_version ((true)) WHERE status = 'active';

CREATE TABLE ai.chunk (
    id               bigint  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    index_version_id bigint  NOT NULL REFERENCES ai.index_version (id) ON DELETE CASCADE,
    source_kind      text    NOT NULL CHECK (source_kind IN (
                                 'profile', 'experience', 'project', 'faq', 'skill',
                                 'repo_meta', 'repo_readme', 'repo_file')),
    source_ref       text    NOT NULL,
    lang             text    NOT NULL CHECK (lang IN ('pt', 'en')),
    title            text    NOT NULL,
    body             text    NOT NULL,
    url              text    NOT NULL CHECK (url ~ '^https://'),
    embedding        real[]  CHECK (embedding IS NULL OR cardinality(embedding) = 384),
    body_hash        text    NOT NULL,
    published        boolean NOT NULL,
    visibility       text    NOT NULL CHECK (visibility IN ('public', 'private'))
);

CREATE INDEX chunk_por_versao ON ai.chunk (index_version_id);

CREATE TABLE ai.skill_evidence (
    id               bigint  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    index_version_id bigint  NOT NULL REFERENCES ai.index_version (id) ON DELETE CASCADE,
    skill            text    NOT NULL,
    repo_full_name   text    NOT NULL,
    path             text    NOT NULL,
    line             integer CHECK (line >= 1),
    detector         text    NOT NULL,
    url              text    NOT NULL CHECK (url ~ '^https://')
);

CREATE INDEX skill_evidence_por_versao ON ai.skill_evidence (index_version_id);

CREATE TABLE ai.content_snapshot (
    index_version_id bigint NOT NULL REFERENCES ai.index_version (id) ON DELETE CASCADE,
    lang             text   NOT NULL CHECK (lang IN ('pt', 'en')),
    body             jsonb  NOT NULL,
    etag             text   NOT NULL,
    PRIMARY KEY (index_version_id, lang)
);

-- Ativa uma versão numa transação só: aposenta a anterior, guarda as 3 últimas aposentadas para
-- rollback, apaga falhas antigas e avisa o ai-api com NOTIFY.
CREATE FUNCTION ai.activate_index_version(p_id bigint) RETURNS void
    LANGUAGE plpgsql
    SET search_path = pg_catalog
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM ai.index_version WHERE id = p_id AND status IN ('building', 'retired')) THEN
        RAISE EXCEPTION 'A versão % do índice não existe ou não pode ser ativada.', p_id
            USING ERRCODE = 'P0001';
    END IF;

    UPDATE ai.index_version SET status = 'retired' WHERE status = 'active';
    UPDATE ai.index_version SET status = 'active', activated_at = now() WHERE id = p_id;

    DELETE FROM ai.index_version
    WHERE id IN (SELECT id FROM ai.index_version
                 WHERE status = 'retired'
                 ORDER BY activated_at DESC NULLS LAST, id DESC
                 OFFSET 3);

    DELETE FROM ai.index_version WHERE status = 'failed' AND created_at < now() - interval '7 days';

    PERFORM pg_notify('javai_index', p_id::text);
END;
$$;

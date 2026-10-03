-- O índice que a IA lê (spec 02). O ai-indexer escreve uma versão inteira e a ativa; o ai-api só lê a versão ativa.

CREATE TABLE index.version (
    id              bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    status          text        NOT NULL DEFAULT 'building'
                                CHECK (status IN ('building', 'active', 'retired', 'failed')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    activated_at    timestamptz,
    embedding_model text,
    -- ETag do snapshot do CRUD que gerou esta versão: se não mudou, o indexador não refaz nada.
    snapshot_etag   text,
    stats           jsonb       NOT NULL DEFAULT '{}'
);

-- No máximo uma versão ativa.
CREATE UNIQUE INDEX version_uma_ativa ON index.version ((true)) WHERE status = 'active';

CREATE TABLE index.chunk (
    id          bigint  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    version_id  bigint  NOT NULL REFERENCES index.version (id) ON DELETE CASCADE,
    source_kind text    NOT NULL CHECK (source_kind IN (
                            'profile', 'experience', 'project', 'faq', 'skill',
                            'repo_meta', 'repo_readme', 'repo_file')),
    source_ref  text    NOT NULL CHECK (char_length(source_ref) <= 400),
    lang        text    NOT NULL CHECK (lang IN ('pt', 'en')),
    title       text    NOT NULL CHECK (char_length(title) BETWEEN 1 AND 400),
    body        text    NOT NULL CHECK (char_length(body) BETWEEN 1 AND 6000),
    url         text    NOT NULL CHECK (url ~ '^https://' AND char_length(url) <= 600),
    -- Nulo quando o modelo de embeddings não estava disponível: a busca cai para só BM25.
    embedding   real[]  CHECK (embedding IS NULL OR cardinality(embedding) = 384),
    body_hash   text    NOT NULL
);

CREATE INDEX chunk_por_versao ON index.chunk (version_id);

CREATE TABLE index.skill_evidence (
    id             bigint  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    version_id     bigint  NOT NULL REFERENCES index.version (id) ON DELETE CASCADE,
    skill          text    NOT NULL CHECK (char_length(skill) <= 80),
    repo_full_name text    NOT NULL CHECK (repo_full_name ~ '^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$'),
    path           text    NOT NULL CHECK (char_length(path) <= 500),
    line           integer CHECK (line >= 1),
    detector       text    NOT NULL CHECK (char_length(detector) <= 120),
    url            text    NOT NULL CHECK (url ~ '^https://' AND char_length(url) <= 700)
);

CREATE INDEX skill_evidence_por_versao ON index.skill_evidence (version_id);
CREATE INDEX skill_evidence_por_habilidade ON index.skill_evidence (version_id, skill);

-- Ativa uma versão numa transação só: aposenta a anterior, guarda as 3 últimas aposentadas para rollback, apaga
-- falhas antigas e avisa o ai-api com NOTIFY para ele recarregar o índice em memória.
CREATE FUNCTION index.activate_version(p_id bigint) RETURNS void
    LANGUAGE plpgsql
    SET search_path = pg_catalog
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM index.version WHERE id = p_id AND status IN ('building', 'retired')) THEN
        RAISE EXCEPTION 'A versão % do índice não existe ou não pode ser ativada.', p_id
            USING ERRCODE = 'P0001';
    END IF;

    UPDATE index.version SET status = 'retired' WHERE status = 'active';
    UPDATE index.version SET status = 'active', activated_at = now() WHERE id = p_id;

    DELETE FROM index.version
    WHERE id IN (SELECT id FROM index.version
                 WHERE status = 'retired'
                 ORDER BY activated_at DESC NULLS LAST, id DESC
                 OFFSET 3);

    DELETE FROM index.version WHERE status = 'failed' AND created_at < now() - interval '7 days';

    PERFORM pg_notify('ai_index', p_id::text);
END;
$$;

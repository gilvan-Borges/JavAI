-- Publicações (spec 02). Cada publicação é uma versão com um snapshot por idioma. A versão ativa é a que
-- está exportada como arquivo estático para o portfólio.

CREATE TABLE publish.version (
    id           bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    status       text        NOT NULL DEFAULT 'building'
                             CHECK (status IN ('building', 'active', 'retired', 'failed')),
    created_at   timestamptz NOT NULL DEFAULT now(),
    activated_at timestamptz
);

-- No máximo uma versão ativa.
CREATE UNIQUE INDEX version_uma_ativa ON publish.version ((true)) WHERE status = 'active';

CREATE TABLE publish.snapshot (
    version_id bigint NOT NULL REFERENCES publish.version (id) ON DELETE CASCADE,
    lang       text   NOT NULL CHECK (lang IN ('pt', 'en')),
    body       jsonb  NOT NULL,
    etag       text   NOT NULL,
    PRIMARY KEY (version_id, lang)
);

-- Ativa uma versão numa transação só: aposenta a anterior, guarda as 3 últimas aposentadas para rollback
-- e apaga falhas antigas.
CREATE FUNCTION publish.activate_version(p_id bigint) RETURNS void
    LANGUAGE plpgsql
    SET search_path = pg_catalog
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM publish.version WHERE id = p_id AND status IN ('building', 'retired')) THEN
        RAISE EXCEPTION 'A versão % não existe ou não pode ser ativada.', p_id
            USING ERRCODE = 'P0001';
    END IF;

    UPDATE publish.version SET status = 'retired' WHERE status = 'active';
    UPDATE publish.version SET status = 'active', activated_at = now() WHERE id = p_id;

    DELETE FROM publish.version
    WHERE id IN (SELECT id FROM publish.version
                 WHERE status = 'retired'
                 ORDER BY activated_at DESC NULLS LAST, id DESC
                 OFFSET 3);

    DELETE FROM publish.version WHERE status = 'failed' AND created_at < now() - interval '7 days';
END;
$$;

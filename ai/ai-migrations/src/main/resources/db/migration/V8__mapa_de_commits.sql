-- O mapa de commits (spec 09): o histórico recente de cada repositório PÚBLICO e que não é fork, para o portfólio
-- desenhar a "galáxia de commits". Cada linha é de uma versão do índice, como o resto do índice. Não há autor, e-mail
-- nem diff: só o hash curto, a data e a primeira linha da mensagem (que o indexador troca por um aviso quando a
-- mensagem parece segredo).

CREATE TABLE index.repo_commit (
    id             bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    version_id     bigint      NOT NULL REFERENCES index.version (id) ON DELETE CASCADE,
    repo_full_name text        NOT NULL CHECK (repo_full_name ~ '^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$'),
    sha            text        NOT NULL CHECK (sha ~ '^[0-9a-f]{7,40}$'),
    committed_at   timestamptz NOT NULL,
    subject        text        NOT NULL CHECK (char_length(subject) <= 200),
    url            text        NOT NULL CHECK (url ~ '^https://' AND char_length(url) <= 700)
);

CREATE INDEX repo_commit_por_versao ON index.repo_commit (version_id);
CREATE INDEX repo_commit_por_repositorio ON index.repo_commit (version_id, repo_full_name, committed_at DESC);

-- O indexador grava (e apaga junto com a versão); a API só lê, e o RLS limita à versão ativa.
GRANT SELECT, INSERT, DELETE ON index.repo_commit TO ai_indexer;
GRANT SELECT ON index.repo_commit TO ai_reader;

ALTER TABLE index.repo_commit ENABLE ROW LEVEL SECURITY;
ALTER TABLE index.repo_commit FORCE ROW LEVEL SECURITY;

CREATE POLICY indexer_tudo ON index.repo_commit TO ai_indexer USING (true) WITH CHECK (true);
CREATE POLICY reader_le_versao_ativa ON index.repo_commit FOR SELECT TO ai_reader
    USING (version_id IN (SELECT id FROM index.version WHERE status = 'active'));

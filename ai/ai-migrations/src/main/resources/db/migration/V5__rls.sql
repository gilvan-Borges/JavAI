-- RLS do banco da IA (spec 02). FORCE faz o RLS valer até para o dono das tabelas.
-- Tabela com RLS e sem política para um papel = zero linhas para esse papel.

ALTER TABLE index.version        ENABLE ROW LEVEL SECURITY;
ALTER TABLE index.version        FORCE ROW LEVEL SECURITY;
ALTER TABLE index.chunk          ENABLE ROW LEVEL SECURITY;
ALTER TABLE index.chunk          FORCE ROW LEVEL SECURITY;
ALTER TABLE index.skill_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE index.skill_evidence FORCE ROW LEVEL SECURITY;
ALTER TABLE ops.usage_daily      ENABLE ROW LEVEL SECURITY;
ALTER TABLE ops.usage_daily      FORCE ROW LEVEL SECURITY;
ALTER TABLE ops.index_job        ENABLE ROW LEVEL SECURITY;
ALTER TABLE ops.index_job        FORCE ROW LEVEL SECURITY;

-- O indexador vê e escreve tudo do índice, inclusive versões em construção.
CREATE POLICY indexer_tudo ON index.version        TO ai_indexer USING (true) WITH CHECK (true);
CREATE POLICY indexer_tudo ON index.chunk          TO ai_indexer USING (true) WITH CHECK (true);
CREATE POLICY indexer_tudo ON index.skill_evidence TO ai_indexer USING (true) WITH CHECK (true);
CREATE POLICY indexer_tudo ON ops.index_job        TO ai_indexer USING (true) WITH CHECK (true);

-- A API só lê a versão ativa: nunca uma em construção, aposentada ou com falha.
CREATE POLICY reader_le_versao_ativa ON index.version FOR SELECT TO ai_reader
    USING (status = 'active');
CREATE POLICY reader_le_versao_ativa ON index.chunk FOR SELECT TO ai_reader
    USING (version_id IN (SELECT id FROM index.version WHERE status = 'active'));
CREATE POLICY reader_le_versao_ativa ON index.skill_evidence FOR SELECT TO ai_reader
    USING (version_id IN (SELECT id FROM index.version WHERE status = 'active'));

-- Os contadores são só da API.
CREATE POLICY reader_tudo ON ops.usage_daily TO ai_reader USING (true) WITH CHECK (true);

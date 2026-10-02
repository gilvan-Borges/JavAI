-- RLS (spec 02). FORCE faz o RLS valer até para o dono das tabelas.
-- Regra de ouro: tabela com RLS e sem política para um papel = zero linhas para esse papel. Por isso
-- content.* não tem política para javai_ai: mesmo um GRANT errado no futuro não mostra nada à IA.

-- content: só javai_admin.
ALTER TABLE content.profile       ENABLE ROW LEVEL SECURITY;
ALTER TABLE content.profile       FORCE ROW LEVEL SECURITY;
ALTER TABLE content.experience    ENABLE ROW LEVEL SECURITY;
ALTER TABLE content.experience    FORCE ROW LEVEL SECURITY;
ALTER TABLE content.project       ENABLE ROW LEVEL SECURITY;
ALTER TABLE content.project       FORCE ROW LEVEL SECURITY;
ALTER TABLE content.skill         ENABLE ROW LEVEL SECURITY;
ALTER TABLE content.skill         FORCE ROW LEVEL SECURITY;
ALTER TABLE content.education     ENABLE ROW LEVEL SECURITY;
ALTER TABLE content.education     FORCE ROW LEVEL SECURITY;
ALTER TABLE content.certification ENABLE ROW LEVEL SECURITY;
ALTER TABLE content.certification FORCE ROW LEVEL SECURITY;
ALTER TABLE content.faq           ENABLE ROW LEVEL SECURITY;
ALTER TABLE content.faq           FORCE ROW LEVEL SECURITY;
ALTER TABLE content.github_repo   ENABLE ROW LEVEL SECURITY;
ALTER TABLE content.github_repo   FORCE ROW LEVEL SECURITY;

CREATE POLICY admin_tudo ON content.profile       TO javai_admin USING (true) WITH CHECK (true);
CREATE POLICY admin_tudo ON content.experience    TO javai_admin USING (true) WITH CHECK (true);
CREATE POLICY admin_tudo ON content.project       TO javai_admin USING (true) WITH CHECK (true);
CREATE POLICY admin_tudo ON content.skill         TO javai_admin USING (true) WITH CHECK (true);
CREATE POLICY admin_tudo ON content.education     TO javai_admin USING (true) WITH CHECK (true);
CREATE POLICY admin_tudo ON content.certification TO javai_admin USING (true) WITH CHECK (true);
CREATE POLICY admin_tudo ON content.faq           TO javai_admin USING (true) WITH CHECK (true);
CREATE POLICY admin_tudo ON content.github_repo   TO javai_admin USING (true) WITH CHECK (true);

-- ai: o admin vê tudo; a IA vê só a versão ativa e, nos trechos, só o que é publicado e público.
ALTER TABLE ai.index_version    ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai.index_version    FORCE ROW LEVEL SECURITY;
ALTER TABLE ai.chunk            ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai.chunk            FORCE ROW LEVEL SECURITY;
ALTER TABLE ai.skill_evidence   ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai.skill_evidence   FORCE ROW LEVEL SECURITY;
ALTER TABLE ai.content_snapshot ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai.content_snapshot FORCE ROW LEVEL SECURITY;

CREATE POLICY admin_tudo ON ai.index_version    TO javai_admin USING (true) WITH CHECK (true);
CREATE POLICY admin_tudo ON ai.chunk            TO javai_admin USING (true) WITH CHECK (true);
CREATE POLICY admin_tudo ON ai.skill_evidence   TO javai_admin USING (true) WITH CHECK (true);
CREATE POLICY admin_tudo ON ai.content_snapshot TO javai_admin USING (true) WITH CHECK (true);

CREATE POLICY ia_le_versao_ativa ON ai.index_version FOR SELECT TO javai_ai
    USING (status = 'active');

-- Se um bug no indexador gravar um trecho privado ou de rascunho, a IA continua sem vê-lo.
CREATE POLICY ia_le_trecho_publico ON ai.chunk FOR SELECT TO javai_ai
    USING (published
           AND visibility = 'public'
           AND index_version_id IN (SELECT id FROM ai.index_version WHERE status = 'active'));

CREATE POLICY ia_le_versao_ativa ON ai.skill_evidence FOR SELECT TO javai_ai
    USING (index_version_id IN (SELECT id FROM ai.index_version WHERE status = 'active'));

CREATE POLICY ia_le_versao_ativa ON ai.content_snapshot FOR SELECT TO javai_ai
    USING (index_version_id IN (SELECT id FROM ai.index_version WHERE status = 'active'));

-- ops
ALTER TABLE ops.usage_daily ENABLE ROW LEVEL SECURITY;
ALTER TABLE ops.usage_daily FORCE ROW LEVEL SECURITY;
ALTER TABLE ops.audit_log   ENABLE ROW LEVEL SECURITY;
ALTER TABLE ops.audit_log   FORCE ROW LEVEL SECURITY;
ALTER TABLE ops.index_job   ENABLE ROW LEVEL SECURITY;
ALTER TABLE ops.index_job   FORCE ROW LEVEL SECURITY;

CREATE POLICY admin_le ON ops.usage_daily FOR SELECT TO javai_admin USING (true);
CREATE POLICY ia_tudo  ON ops.usage_daily TO javai_ai USING (true) WITH CHECK (true);

CREATE POLICY admin_le    ON ops.audit_log FOR SELECT TO javai_admin USING (true);
CREATE POLICY admin_insere ON ops.audit_log FOR INSERT TO javai_admin WITH CHECK (true);

CREATE POLICY admin_tudo ON ops.index_job TO javai_admin USING (true) WITH CHECK (true);

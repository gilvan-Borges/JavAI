-- RLS do banco do CRUD (spec 02). FORCE faz o RLS valer até para o dono das tabelas.
-- Regra de ouro: tabela com RLS e sem política para um papel = zero linhas para esse papel. Só admin_app tem
-- política. Se um dia um GRANT errado der acesso a outro papel, ele continua sem ver nada.

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
ALTER TABLE publish.version       ENABLE ROW LEVEL SECURITY;
ALTER TABLE publish.version       FORCE ROW LEVEL SECURITY;
ALTER TABLE publish.snapshot      ENABLE ROW LEVEL SECURITY;
ALTER TABLE publish.snapshot      FORCE ROW LEVEL SECURITY;
ALTER TABLE ops.audit_log         ENABLE ROW LEVEL SECURITY;
ALTER TABLE ops.audit_log         FORCE ROW LEVEL SECURITY;
ALTER TABLE ops.publish_job       ENABLE ROW LEVEL SECURITY;
ALTER TABLE ops.publish_job       FORCE ROW LEVEL SECURITY;

CREATE POLICY app_tudo ON content.profile       TO admin_app USING (true) WITH CHECK (true);
CREATE POLICY app_tudo ON content.experience    TO admin_app USING (true) WITH CHECK (true);
CREATE POLICY app_tudo ON content.project       TO admin_app USING (true) WITH CHECK (true);
CREATE POLICY app_tudo ON content.skill         TO admin_app USING (true) WITH CHECK (true);
CREATE POLICY app_tudo ON content.education     TO admin_app USING (true) WITH CHECK (true);
CREATE POLICY app_tudo ON content.certification TO admin_app USING (true) WITH CHECK (true);
CREATE POLICY app_tudo ON content.faq           TO admin_app USING (true) WITH CHECK (true);
CREATE POLICY app_tudo ON content.github_repo   TO admin_app USING (true) WITH CHECK (true);
CREATE POLICY app_tudo ON publish.version       TO admin_app USING (true) WITH CHECK (true);
CREATE POLICY app_tudo ON publish.snapshot      TO admin_app USING (true) WITH CHECK (true);
CREATE POLICY app_tudo ON ops.publish_job       TO admin_app USING (true) WITH CHECK (true);

-- A auditoria só cresce: leitura e inserção, mais nada.
CREATE POLICY app_le     ON ops.audit_log FOR SELECT TO admin_app USING (true);
CREATE POLICY app_insere ON ops.audit_log FOR INSERT TO admin_app WITH CHECK (true);

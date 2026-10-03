-- Fontes públicas configuráveis (spec 01): além do snapshot do CRUD e dos repositórios, o ai-indexer passa a ler textos do
-- site do dono, documentos (o currículo em PDF) e o perfil do GitHub. Cada um ganha o seu tipo de trecho.
ALTER TABLE index.chunk DROP CONSTRAINT chunk_source_kind_check;

ALTER TABLE index.chunk ADD CONSTRAINT chunk_source_kind_check CHECK (source_kind IN (
    'profile', 'experience', 'project', 'faq', 'skill',
    'repo_meta', 'repo_readme', 'repo_file',
    'site', 'document', 'github_profile'));

-- Conteúdo cadastrado pelo Gilvan (spec 02). Só o admin-api, com o papel admin_app, lê e escreve aqui.
-- Rascunho pode ter campos vazios. Para publicar, o banco exige os campos obrigatórios nos dois idiomas.
-- Texto vazio não existe: o app grava NULL, e os CHECKs de tamanho começam em 1.

CREATE TABLE content.profile (
    id              smallint    PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    full_name       text        NOT NULL CHECK (char_length(full_name) BETWEEN 1 AND 120),
    headline_pt     text        CHECK (char_length(headline_pt) BETWEEN 1 AND 200),
    headline_en     text        CHECK (char_length(headline_en) BETWEEN 1 AND 200),
    summary_pt      text        CHECK (char_length(summary_pt) BETWEEN 1 AND 4000),
    summary_en      text        CHECK (char_length(summary_en) BETWEEN 1 AND 4000),
    location        text        CHECK (char_length(location) BETWEEN 1 AND 120),
    links           jsonb       NOT NULL DEFAULT '[]'
                                CHECK (CASE WHEN jsonb_typeof(links) = 'array'
                                            THEN jsonb_array_length(links) <= 10 ELSE false END),
    availability_pt text        CHECK (char_length(availability_pt) BETWEEN 1 AND 300),
    availability_en text        CHECK (char_length(availability_en) BETWEEN 1 AND 300),
    private_notes   text        CHECK (char_length(private_notes) BETWEEN 1 AND 4000),
    status          text        NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'published')),
    visibility      text        NOT NULL DEFAULT 'public' CHECK (visibility IN ('public', 'private')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      text        NOT NULL CHECK (char_length(updated_by) BETWEEN 3 AND 320),
    CONSTRAINT profile_publicado_bilingue CHECK (
        status = 'draft'
        OR (headline_pt IS NOT NULL AND headline_en IS NOT NULL
            AND summary_pt IS NOT NULL AND summary_en IS NOT NULL)
    )
);

CREATE TABLE content.experience (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    company         text        NOT NULL CHECK (char_length(company) BETWEEN 1 AND 120),
    role_pt         text        CHECK (char_length(role_pt) BETWEEN 1 AND 120),
    role_en         text        CHECK (char_length(role_en) BETWEEN 1 AND 120),
    started_on      date        NOT NULL,
    ended_on        date,
    description_pt  text        CHECK (char_length(description_pt) BETWEEN 1 AND 4000),
    description_en  text        CHECK (char_length(description_en) BETWEEN 1 AND 4000),
    highlights_pt   text[]      NOT NULL DEFAULT '{}' CHECK (cardinality(highlights_pt) <= 10),
    highlights_en   text[]      NOT NULL DEFAULT '{}' CHECK (cardinality(highlights_en) <= 10),
    stack           text[]      NOT NULL DEFAULT '{}' CHECK (cardinality(stack) <= 30),
    sort_order      integer     NOT NULL DEFAULT 0,
    private_notes   text        CHECK (char_length(private_notes) BETWEEN 1 AND 4000),
    status          text        NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'published')),
    visibility      text        NOT NULL DEFAULT 'public' CHECK (visibility IN ('public', 'private')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      text        NOT NULL CHECK (char_length(updated_by) BETWEEN 3 AND 320),
    CONSTRAINT experience_periodo_valido CHECK (ended_on IS NULL OR ended_on >= started_on),
    CONSTRAINT experience_publicada_bilingue CHECK (
        status = 'draft'
        OR (role_pt IS NOT NULL AND role_en IS NOT NULL
            AND description_pt IS NOT NULL AND description_en IS NOT NULL)
    )
);

CREATE TABLE content.project (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    slug            text        NOT NULL UNIQUE
                                CHECK (char_length(slug) <= 80 AND slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    name            text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 120),
    summary_pt      text        CHECK (char_length(summary_pt) BETWEEN 1 AND 500),
    summary_en      text        CHECK (char_length(summary_en) BETWEEN 1 AND 500),
    problem_pt      text        CHECK (char_length(problem_pt) BETWEEN 1 AND 4000),
    problem_en      text        CHECK (char_length(problem_en) BETWEEN 1 AND 4000),
    solution_pt     text        CHECK (char_length(solution_pt) BETWEEN 1 AND 4000),
    solution_en     text        CHECK (char_length(solution_en) BETWEEN 1 AND 4000),
    results_pt      text        CHECK (char_length(results_pt) BETWEEN 1 AND 4000),
    results_en      text        CHECK (char_length(results_en) BETWEEN 1 AND 4000),
    stack           text[]      NOT NULL DEFAULT '{}' CHECK (cardinality(stack) <= 30),
    repo_full_name  text        CHECK (repo_full_name ~ '^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$'),
    url             text        CHECK (char_length(url) <= 300 AND url ~ '^https://'),
    featured        boolean     NOT NULL DEFAULT false,
    sort_order      integer     NOT NULL DEFAULT 0,
    private_notes   text        CHECK (char_length(private_notes) BETWEEN 1 AND 4000),
    status          text        NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'published')),
    visibility      text        NOT NULL DEFAULT 'public' CHECK (visibility IN ('public', 'private')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      text        NOT NULL CHECK (char_length(updated_by) BETWEEN 3 AND 320),
    CONSTRAINT project_publicado_bilingue CHECK (
        status = 'draft' OR (summary_pt IS NOT NULL AND summary_en IS NOT NULL)
    )
);

CREATE TABLE content.skill (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    name            text        NOT NULL UNIQUE CHECK (char_length(name) BETWEEN 1 AND 60),
    category        text        NOT NULL CHECK (category IN (
                                    'linguagem', 'framework', 'banco', 'mensageria', 'infra',
                                    'teste', 'front', 'arquitetura', 'outro')),
    declared        boolean     NOT NULL DEFAULT true,
    notes_pt        text        CHECK (char_length(notes_pt) BETWEEN 1 AND 500),
    notes_en        text        CHECK (char_length(notes_en) BETWEEN 1 AND 500),
    status          text        NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'published')),
    visibility      text        NOT NULL DEFAULT 'public' CHECK (visibility IN ('public', 'private')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      text        NOT NULL CHECK (char_length(updated_by) BETWEEN 3 AND 320)
);

CREATE TABLE content.education (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    institution     text        NOT NULL CHECK (char_length(institution) BETWEEN 1 AND 160),
    course_pt       text        CHECK (char_length(course_pt) BETWEEN 1 AND 160),
    course_en       text        CHECK (char_length(course_en) BETWEEN 1 AND 160),
    started_on      date        NOT NULL,
    ended_on        date,
    completion      text        NOT NULL CHECK (completion IN ('cursando', 'concluido', 'trancado')),
    status          text        NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'published')),
    visibility      text        NOT NULL DEFAULT 'public' CHECK (visibility IN ('public', 'private')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      text        NOT NULL CHECK (char_length(updated_by) BETWEEN 3 AND 320),
    CONSTRAINT education_periodo_valido CHECK (ended_on IS NULL OR ended_on >= started_on),
    CONSTRAINT education_publicada_bilingue CHECK (
        status = 'draft' OR (course_pt IS NOT NULL AND course_en IS NOT NULL)
    )
);

CREATE TABLE content.certification (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    name            text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 160),
    issuer          text        NOT NULL CHECK (char_length(issuer) BETWEEN 1 AND 120),
    issued_on       date        NOT NULL,
    url             text        CHECK (char_length(url) <= 300 AND url ~ '^https://'),
    status          text        NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'published')),
    visibility      text        NOT NULL DEFAULT 'public' CHECK (visibility IN ('public', 'private')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      text        NOT NULL CHECK (char_length(updated_by) BETWEEN 3 AND 320)
);

CREATE TABLE content.faq (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    question_pt     text        CHECK (char_length(question_pt) BETWEEN 1 AND 300),
    question_en     text        CHECK (char_length(question_en) BETWEEN 1 AND 300),
    answer_pt       text        CHECK (char_length(answer_pt) BETWEEN 1 AND 2000),
    answer_en       text        CHECK (char_length(answer_en) BETWEEN 1 AND 2000),
    sort_order      integer     NOT NULL DEFAULT 0,
    status          text        NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'published')),
    visibility      text        NOT NULL DEFAULT 'public' CHECK (visibility IN ('public', 'private')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      text        NOT NULL CHECK (char_length(updated_by) BETWEEN 3 AND 320),
    CONSTRAINT faq_publicada_bilingue CHECK (
        status = 'draft'
        OR (question_pt IS NOT NULL AND question_en IS NOT NULL
            AND answer_pt IS NOT NULL AND answer_en IS NOT NULL)
    )
);

-- Repositórios do GitHub que o Gilvan escolhe publicar no snapshot (Fase 2). Usa enabled no lugar de status e visibility.
CREATE TABLE content.github_repo (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    full_name       text        NOT NULL UNIQUE CHECK (full_name ~ '^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$'),
    enabled         boolean     NOT NULL DEFAULT false,
    kind            text        NOT NULL DEFAULT 'estudo'
                                CHECK (kind IN ('destaque', 'trabalho', 'estudo', 'cliente')),
    note_pt         text        CHECK (char_length(note_pt) BETWEEN 1 AND 500),
    note_en         text        CHECK (char_length(note_en) BETWEEN 1 AND 500),
    is_fork         boolean     NOT NULL,
    is_private      boolean     NOT NULL,
    default_branch  text        CHECK (char_length(default_branch) BETWEEN 1 AND 255),
    size_kb         integer     CHECK (size_kb >= 0),
    last_indexed_at timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      text        NOT NULL CHECK (char_length(updated_by) BETWEEN 3 AND 320),
    -- Fork e repositório privado nunca vão para o snapshot, nem por engano (spec 02).
    CONSTRAINT github_repo_fork_nunca_ligado CHECK (NOT (enabled AND is_fork)),
    CONSTRAINT github_repo_privado_nunca_ligado CHECK (NOT (enabled AND is_private))
);

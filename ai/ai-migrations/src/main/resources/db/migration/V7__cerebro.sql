-- O "cérebro" (spec 08): por onde a busca andou, em contadores por dia. Nada aqui identifica um visitante nem guarda o
-- que ele escreveu: a chave é a ORIGEM do trecho do índice (conteúdo público, por exemplo uma seção do README de um
-- repositório), nunca o texto da pergunta, e não existe horário, IP ou ligação entre as linhas de uma mesma conversa.

-- Quantas vezes cada trecho foi escolhido pela busca. A chave é a origem estável do trecho ("source_ref"), e não o id
-- do trecho, porque o id muda a cada versão do índice.
CREATE TABLE ops.knowledge_heat (
    day        date    NOT NULL,
    source_ref text    NOT NULL CHECK (length(source_ref) BETWEEN 1 AND 300),
    hits       integer NOT NULL DEFAULT 0 CHECK (hits >= 0),
    PRIMARY KEY (day, source_ref)
);

-- Quantas vezes dois trechos foram escolhidos na mesma resposta: são as "ramificações" (o que se pergunta junto). Só o
-- par e o total do dia ficam; não há como remontar uma conversa individual. source_a < source_b dá uma linha por par.
CREATE TABLE ops.knowledge_pair (
    day      date    NOT NULL,
    source_a text    NOT NULL CHECK (length(source_a) BETWEEN 1 AND 300),
    source_b text    NOT NULL CHECK (length(source_b) BETWEEN 1 AND 300),
    hits     integer NOT NULL DEFAULT 0 CHECK (hits >= 0),
    PRIMARY KEY (day, source_a, source_b),
    -- COLLATE "C": a ordem é por código de caractere, a mesma do Java, e não a do idioma do banco (que ignora a caixa).
    CHECK (source_a COLLATE "C" < source_b COLLATE "C")
);

-- Só a API (papel ai_reader) lê e grava; o indexador não tem acesso.
GRANT SELECT, INSERT, UPDATE ON ops.knowledge_heat, ops.knowledge_pair TO ai_reader;

ALTER TABLE ops.knowledge_heat ENABLE ROW LEVEL SECURITY;
ALTER TABLE ops.knowledge_heat FORCE ROW LEVEL SECURITY;
ALTER TABLE ops.knowledge_pair ENABLE ROW LEVEL SECURITY;
ALTER TABLE ops.knowledge_pair FORCE ROW LEVEL SECURITY;
CREATE POLICY reader_tudo ON ops.knowledge_heat TO ai_reader USING (true) WITH CHECK (true);
CREATE POLICY reader_tudo ON ops.knowledge_pair TO ai_reader USING (true) WITH CHECK (true);

package br.com.gilvanborges.javai.ai.migrations;

/** Os papéis do banco da IA (spec 02). O banco do CRUD tem os seus, e um lado não conhece os papéis do outro. */
public final class Roles {

    /** Dono de todos os objetos. Só o contêiner ai-migrate usa, para rodar o Flyway. */
    public static final String OWNER = "ai_owner";

    /** ai-indexer: escreve o índice e ativa versões. */
    public static final String INDEXER = "ai_indexer";

    /** ai-api: lê o índice ativo e grava contadores. Não escreve no índice. */
    public static final String READER = "ai_reader";

    private Roles() {
    }
}

package br.com.gilvanborges.javai.ai.core.chunk;

/** De onde veio um trecho. Os nomes batem com o CHECK de {@code index.chunk} no banco da IA (spec 02). */
public enum SourceKind {
    PROFILE("profile"),
    EXPERIENCE("experience"),
    PROJECT("project"),
    FAQ("faq"),
    SKILL("skill"),
    REPO_META("repo_meta"),
    REPO_README("repo_readme"),
    REPO_FILE("repo_file");

    private final String db;

    SourceKind(String db) {
        this.db = db;
    }

    public String db() {
        return db;
    }

    /**
     * Código e README de repositório não têm idioma "certo": uma pergunta em português precisa encontrar um código
     * em inglês. Por isso a busca não filtra esses por idioma.
     */
    public boolean isLanguageNeutral() {
        return this == REPO_README || this == REPO_FILE;
    }
}

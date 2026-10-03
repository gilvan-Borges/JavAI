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
    REPO_FILE("repo_file"),
    /** Texto de uma página do site do dono (por exemplo o {@code llms-full.txt} do portfólio). */
    SITE("site"),
    /** Um documento público, como o currículo em PDF. */
    DOCUMENT("document"),
    /** O perfil do GitHub: bio, README do perfil e a lista de repositórios públicos. */
    GITHUB_PROFILE("github_profile");

    private final String db;

    SourceKind(String db) {
        this.db = db;
    }

    public static SourceKind fromDb(String value) {
        for (SourceKind kind : values()) {
            if (kind.db.equals(value)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Tipo de trecho desconhecido: " + value);
    }

    public String db() {
        return db;
    }

    /**
     * Código e README de repositório não têm idioma "certo": uma pergunta em português precisa encontrar um código
     * em inglês. Por isso a busca não filtra esses por idioma. O mesmo vale para as fontes públicas (site, documento,
     * perfil do GitHub): o idioma delas é só uma estimativa, e uma pergunta em inglês pode precisar de um texto em português.
     */
    public boolean isLanguageNeutral() {
        return this == REPO_README || this == REPO_FILE || this == SITE || this == DOCUMENT || this == GITHUB_PROFILE;
    }
}

package br.com.gilvanborges.javai.admin.content;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Privado nunca sai do admin, mesmo publicado: não entra no snapshot nem no índice da IA. */
public enum Visibility {
    @JsonProperty("public") PUBLIC("public"),
    @JsonProperty("private") PRIVATE("private");

    private final String db;

    Visibility(String db) {
        this.db = db;
    }

    public String db() {
        return db;
    }

    public static Visibility fromDb(String value) {
        for (Visibility visibility : values()) {
            if (visibility.db.equals(value)) {
                return visibility;
            }
        }
        throw new IllegalStateException("Visibilidade desconhecida no banco.");
    }
}

package br.com.gilvanborges.javai.ai.api.chat;

/** As categorias que o Jev escolhe para uma pergunta (spec 01, "Roteador"). */
public enum Category {
    PERFIL("perfil"),
    CODIGO("codigo"),
    VAGA("vaga"),
    FORA_DO_ESCOPO("fora_do_escopo"),
    ATAQUE("ataque");

    private final String key;

    Category(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    static Category fromKey(String key) {
        for (Category c : values()) {
            if (c.key.equals(key)) {
                return c;
            }
        }
        return null;
    }
}

package br.com.gilvanborges.javai.admin.domain.content;

import java.util.Locale;
import java.util.Optional;

/** Idiomas em que o conteúdo é publicado. */
public enum Lang {
    PT,
    EN;

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<Lang> fromCode(String code) {
        for (Lang lang : values()) {
            if (lang.code().equals(code)) {
                return Optional.of(lang);
            }
        }
        return Optional.empty();
    }
}

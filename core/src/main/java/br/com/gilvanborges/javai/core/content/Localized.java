package br.com.gilvanborges.javai.core.content;

/** Um texto nos dois idiomas. Campos opcionais podem ter um ou os dois lados nulos. */
public record Localized(String pt, String en) {

    public static final Localized EMPTY = new Localized(null, null);

    public String in(Lang lang) {
        return switch (lang) {
            case PT -> pt;
            case EN -> en;
        };
    }
}

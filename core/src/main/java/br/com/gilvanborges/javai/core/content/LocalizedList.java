package br.com.gilvanborges.javai.core.content;

import java.util.List;

/** Uma lista de textos nos dois idiomas, como os destaques de uma experiência. */
public record LocalizedList(List<String> pt, List<String> en) {

    public LocalizedList {
        pt = List.copyOf(pt);
        en = List.copyOf(en);
    }

    public List<String> in(Lang lang) {
        return switch (lang) {
            case PT -> pt;
            case EN -> en;
        };
    }
}

package br.com.gilvanborges.javai.ai.core.text;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Normaliza texto para comparação: NFKC, minúsculas e sem acentos. O acento só some aqui, na comparação: o texto
 * original mostrado ao visitante nunca passa por esta classe.
 */
public final class TextNormalizer {

    private TextNormalizer() {
    }

    public static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        String decomposed = Normalizer.normalize(nfkc, Normalizer.Form.NFD);
        var sb = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (Character.getType(c) != Character.NON_SPACING_MARK) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}

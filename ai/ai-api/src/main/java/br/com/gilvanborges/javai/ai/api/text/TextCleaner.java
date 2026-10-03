package br.com.gilvanborges.javai.ai.api.text;

/** Limpeza de texto vindo de fora: NFKC, sem caracteres de controle ou de formatação (texto invisível) e espaços colapsados. */
public final class TextCleaner {

    private TextCleaner() {
    }

    /** Mantém acento e caixa. {@code keepNewlines} preserva as quebras de linha (descrição de vaga). */
    public static String clean(String text, boolean keepNewlines) {
        String nfkc = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC);
        var sb = new StringBuilder(nfkc.length());
        nfkc.codePoints().forEach(cp -> {
            if (cp == '\n' && keepNewlines) {
                sb.append('\n');
            } else if (Character.isWhitespace(cp)) {
                sb.append(' ');
            } else if (!Character.isISOControl(cp) && Character.getType(cp) != Character.FORMAT) {
                sb.appendCodePoint(cp);
            }
        });
        return sb.toString().replaceAll(" {2,}", " ").replaceAll("\n{3,}", "\n\n").strip();
    }
}

package br.com.gilvanborges.javai.ai.indexer.sources;

import java.util.regex.Pattern;

/**
 * Tira e-mail e telefone do texto das fontes públicas antes de indexar. O currículo em PDF costuma trazê-los no
 * cabeçalho, e a IA e o MCP são públicos: contato é só pelos canais do site, nunca repassado pela IA.
 */
final class ContactRedactor {

    private static final String OMITTED = "[contato omitido]";
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+");
    /** Telefone com separador (espaço ou hífen) entre os blocos, com ou sem +55 e DDD; anos, versões e CEP não casam. */
    private static final Pattern PHONE = Pattern.compile(
            "(?<![\\w/])(?:\\+?55[\\s-]?)?(?:\\(\\d{2}\\)|\\d{2})?[\\s-]?9?\\d{4}[\\s-]\\d{4}(?![\\w/])");

    private ContactRedactor() {
    }

    static String redact(String text) {
        return PHONE.matcher(EMAIL.matcher(text).replaceAll(OMITTED)).replaceAll(OMITTED);
    }
}

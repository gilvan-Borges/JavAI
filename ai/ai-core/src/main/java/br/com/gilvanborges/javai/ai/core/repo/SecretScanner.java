package br.com.gilvanborges.javai.ai.core.repo;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Rede de segurança contra segredo commitado por engano: um arquivo com qualquer padrão abaixo não entra no índice.
 * Devolve só o <em>nome da regra</em>, nunca o valor encontrado, para o relatório do indexador não virar um
 * segundo lugar onde o segredo vive. Não substitui o gitleaks; complementa.
 */
public final class SecretScanner {

    private record Rule(String name, Pattern pattern) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule("chave-privada", Pattern.compile("-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----")),
            new Rule("aws-access-key", Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b")),
            new Rule("github-token", Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{30,}\\b")),
            new Rule("chave-de-api-llm", Pattern.compile("\\bsk-(?:or-v1-|ant-|proj-)?[A-Za-z0-9_-]{24,}")),
            new Rule("slack-token", Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}")),
            new Rule("jwt", Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}")),
            new Rule("url-com-senha", Pattern.compile("[a-z][a-z0-9+.-]*://[^\\s/:@]+:[^\\s/@]{4,}@[^\\s/]+", Pattern.CASE_INSENSITIVE)),
            new Rule("senha-literal", Pattern.compile(
                    "(?i)(?:password|passwd|pwd|secret|api[_-]?key|access[_-]?token|auth[_-]?token)\\s*[:=]\\s*[\"'][^\"'\\s$%{}]{8,}[\"']")));

    private SecretScanner() {
    }

    /** O nome da primeira regra que casar, ou vazio se o texto estiver limpo. */
    public static Optional<String> firstFinding(String content) {
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(content).find()) {
                return Optional.of(rule.name());
            }
        }
        return Optional.empty();
    }
}

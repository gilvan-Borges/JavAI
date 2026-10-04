package br.com.gilvanborges.javai.ai.indexer.repo;

import br.com.gilvanborges.javai.ai.core.chunk.Chunk;
import br.com.gilvanborges.javai.ai.core.chunk.SourceKind;
import br.com.gilvanborges.javai.ai.core.chunk.TextSplitter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * O resumo de atividade de TODOS os repositórios num só documento, do commit mais recente para o mais antigo.
 *
 * <p>Por que existe: cada repositório já tem o seu resumo ({@code REPO_FACTS.md}), mas uma pergunta como "qual foi o
 * último commit dele?" não cita nenhum repositório, e a busca devolve só alguns resumos soltos. O modelo então escolhe o
 * mais recente <i>entre os poucos que viu</i> e erra. Uma pergunta que atravessa os repositórios precisa de um trecho que
 * já os compara, com a resposta na primeira frase.
 *
 * <p>Os dados vêm do próprio {@code REPO_FACTS.md} (as etiquetas abaixo são as mesmas que o {@link GitHubRepoSource} escreve),
 * então não há segunda leitura do GitHub.
 */
public final class RepoActivity {

    /** Nome do arquivo sintético de cada repositório (também usado por {@link GitHubRepoSource}). */
    public static final String FACTS_FILE = "REPO_FACTS.md";
    /** Etiquetas do resumo: a mesma linha é escrita pelo {@link GitHubRepoSource} e lida aqui. */
    static final String COMMIT_LABEL = "Último commit / Latest commit: ";
    static final String MESSAGE_LABEL = "Mensagem do último commit / Latest commit message: ";

    static final int MAX_ENTRIES = 12;
    static final int MAX_MESSAGE = 80;
    private static final int MAX_CHARS = 1200;

    private static final Pattern COMMIT_DATE = Pattern.compile("^" + Pattern.quote(COMMIT_LABEL) + "(\\d{4}-\\d{2}-\\d{2})",
            Pattern.MULTILINE);
    private static final Pattern MESSAGE = Pattern.compile("^" + Pattern.quote(MESSAGE_LABEL) + "(.*)$", Pattern.MULTILINE);

    private RepoActivity() {
    }

    /** O último commit de um repositório (branch principal). */
    public record Entry(String fullName, LocalDate lastCommit, String message) {
        String shortName() {
            return fullName.substring(fullName.indexOf('/') + 1);
        }

        String owner() {
            int slash = fullName.indexOf('/');
            return slash < 0 ? fullName : fullName.substring(0, slash);
        }
    }

    /** A entrada de um repositório lido, se ele trouxe o resumo; repositório pulado ou sem resumo não entra. */
    public static Optional<Entry> from(RepoSource.RepoRead repo) {
        if (repo.wasSkipped()) {
            return Optional.empty();
        }
        return repo.files().stream()
                .filter(f -> f.path().equals(FACTS_FILE))
                .findFirst()
                .flatMap(f -> parse(repo.fullName(), f.content()));
    }

    static Optional<Entry> parse(String fullName, String factsContent) {
        var date = COMMIT_DATE.matcher(factsContent);
        if (!date.find()) {
            return Optional.empty();
        }
        var message = MESSAGE.matcher(factsContent);
        return Optional.of(new Entry(fullName, LocalDate.parse(date.group(1)), message.find() ? clean(message.group(1)) : ""));
    }

    /** Trechos em português e em inglês; vazio quando não há nenhum repositório com data de commit. */
    public static List<Chunk> chunks(List<Entry> entries) {
        if (entries.isEmpty()) {
            return List.of();
        }
        var sorted = entries.stream()
                .sorted(Comparator.comparing(Entry::lastCommit).reversed().thenComparing(Entry::fullName))
                .limit(MAX_ENTRIES)
                .toList();
        Entry latest = sorted.get(0);
        String owner = latest.owner();
        String url = "https://github.com/" + owner + "?tab=repositories&sort=updated";
        String sourceRef = "github-activity:" + owner;

        var out = new ArrayList<Chunk>();
        for (String lang : List.of("pt", "en")) {
            boolean pt = lang.equals("pt");
            var body = new StringBuilder();
            if (pt) {
                body.append("O último commit de Gilvan Borges (o commit mais recente entre os repositórios dele) foi no repositório ")
                        .append(latest.shortName()).append(" em ").append(latest.lastCommit()).append(describe(latest, true))
                        .append(". Abaixo, o último commit de cada repositório público, do mais recente para o mais antigo ")
                        .append("(branch principal, datas em UTC):\n");
            } else {
                body.append("Gilvan Borges' latest commit (the most recent commit across his repositories) was in the repository ")
                        .append(latest.shortName()).append(" on ").append(latest.lastCommit()).append(describe(latest, false))
                        .append(". Below, the latest commit of each public repository, from newest to oldest ")
                        .append("(default branch, dates in UTC):\n");
            }
            for (Entry e : sorted) {
                body.append("- ").append(e.shortName()).append(": ").append(e.lastCommit());
                if (!e.message().isEmpty()) {
                    body.append(" · ").append(e.message());
                }
                body.append('\n');
            }
            String title = pt
                    ? "Atividade recente nos repositórios de Gilvan Borges: último commit de cada projeto"
                    : "Recent activity across Gilvan Borges' repositories: latest commit of each project";
            List<String> parts = TextSplitter.split(body.toString(), MAX_CHARS);
            for (int i = 0; i < parts.size(); i++) {
                String suffix = parts.size() > 1 ? " (" + (i + 1) + "/" + parts.size() + ")" : "";
                out.add(new Chunk(lang + ":" + sourceRef + "#" + i, SourceKind.GITHUB_PROFILE, sourceRef, lang, title + suffix,
                        parts.get(i), url));
            }
        }
        return List.copyOf(out);
    }

    private static String describe(Entry e, boolean pt) {
        if (e.message().isEmpty()) {
            return "";
        }
        return pt ? ", com a mensagem “" + e.message() + "”" : ", with the message “" + e.message() + "”";
    }

    private static String clean(String raw) {
        String text = raw.replaceAll("\\p{Cntrl}", " ").strip();
        return text.length() > MAX_MESSAGE ? text.substring(0, MAX_MESSAGE).strip() + "…" : text;
    }
}

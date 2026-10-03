package br.com.gilvanborges.javai.ai.core.chunk;

import br.com.gilvanborges.javai.ai.core.snapshot.SnapshotData;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Transforma o snapshot do CRUD em trechos pesquisáveis. Todo trecho ganha um título em terceira pessoa e o nome
 * de quem ele descreve: o conteúdo está em primeira pessoa ("hoje sou desenvolvedor na Vibetex") e as perguntas
 * chegam em terceira ("onde o Gilvan trabalha?"), então sem esse título a busca não aproxima os dois (spec 01).
 */
public final class SnapshotChunker {

    /** Tamanho máximo de um trecho. Textos maiores são divididos em partes numeradas. */
    static final int MAX_CHARS = 1200;

    private static final String SITE = "https://gilvanborges.com.br";

    private SnapshotChunker() {
    }

    public static List<Chunk> chunk(SnapshotData data) {
        boolean pt = data.lang().equals("pt");
        String who = data.profile().fullName();
        var out = new ArrayList<Chunk>();

        // Perfil
        add(out, SourceKind.PROFILE, "profile", data.lang(), pt ? "Perfil de " + who : "Profile of " + who,
                join(data.profile().headline(), data.profile().summary(), data.profile().location(),
                        data.profile().availability()), SITE);

        for (var e : data.experiences()) {
            String period = e.startedOn() + (e.endedOn() == null ? (pt ? " até hoje" : " to present") : " - " + e.endedOn());
            add(out, SourceKind.EXPERIENCE, "experience:" + e.company(), data.lang(),
                    pt ? "Experiência de " + who + " na " + e.company() : possessive(who) + " experience at " + e.company(),
                    join(e.role() + " (" + period + ")", e.description(), bullets(e.highlights()),
                            list(pt ? "Tecnologias" : "Stack", e.stack())),
                    SITE + "#experiencia");
        }

        for (var p : data.projects()) {
            String base = pt ? "Projeto " + p.name() + " de " + who : possessive(who) + " project " + p.name();
            String url = projectUrl(p);
            String stack = list(pt ? "Tecnologias" : "Stack", p.stack());
            add(out, SourceKind.PROJECT, "project:" + p.slug(), data.lang(), base,
                    join(p.summary(), p.problem(), stack), url);
            add(out, SourceKind.PROJECT, "project:" + p.slug() + ":solution", data.lang(),
                    base + (pt ? " — o que faz e como foi feito" : " — what it does and how it was built"),
                    p.solution(), url);
            add(out, SourceKind.PROJECT, "project:" + p.slug() + ":results", data.lang(),
                    base + (pt ? " — resultados e limitações" : " — results and limitations"), p.results(), url);
        }

        for (var s : data.skills()) {
            add(out, SourceKind.SKILL, "skill:" + s.name(), data.lang(),
                    pt ? "Habilidade de " + who + ": " + s.name() : possessive(who) + " skill: " + s.name(),
                    join(pt ? "Categoria: " + s.category() : "Category: " + s.category(), s.notes(),
                            s.declared() ? (pt ? "Habilidade declarada pelo próprio Gilvan." : "Skill declared by Gilvan himself.") : null),
                    SITE + "#skills");
        }

        for (var ed : data.education()) {
            add(out, SourceKind.PROFILE, "education:" + ed.institution() + ":" + ed.course(), data.lang(),
                    pt ? "Formação de " + who + " em " + ed.institution() : possessive(who) + " education at " + ed.institution(),
                    join(ed.course(), ed.startedOn() + (ed.endedOn() == null ? "" : " - " + ed.endedOn()), ed.completion()), SITE);
        }

        for (var f : data.faq()) {
            add(out, SourceKind.FAQ, "faq:" + f.question(), data.lang(),
                    (pt ? "Pergunta frequente sobre " : "Frequently asked question about ") + who + ": " + f.question(),
                    f.answer(), SITE);
        }

        for (var r : data.repositories()) {
            add(out, SourceKind.REPO_META, "repo:" + r.fullName(), data.lang(),
                    pt ? "Repositório " + r.fullName() + " de " + who : possessive(who) + " repository " + r.fullName(),
                    join(r.kind(), r.note()), "https://github.com/" + r.fullName());
        }
        return List.copyOf(out);
    }

    /** Possessivo em inglês: "Gilvan Borges'" (nome terminado em s leva só o apóstrofo) ou "Maria's". */
    private static String possessive(String name) {
        return name.endsWith("s") || name.endsWith("S") ? name + "'" : name + "'s";
    }

    /** O link da fonte: o do projeto, senão o repositório, senão a seção de projetos do portfólio. */
    private static String projectUrl(SnapshotData.Project p) {
        if (p.url() != null && p.url().startsWith("https://")) {
            return p.url();
        }
        if (p.repository() != null) {
            return "https://github.com/" + p.repository();
        }
        return SITE + "#projetos";
    }

    private static void add(List<Chunk> out, SourceKind kind, String ref, String lang, String title, String body, String url) {
        if (body == null || body.isBlank()) {
            return;
        }
        List<String> parts = TextSplitter.split(body, MAX_CHARS);
        for (int i = 0; i < parts.size(); i++) {
            String suffix = parts.size() > 1 ? " (" + (i + 1) + "/" + parts.size() + ")" : "";
            out.add(new Chunk(lang + ":" + ref + (parts.size() > 1 ? "#" + i : ""), kind, ref, lang, title + suffix, parts.get(i), url));
        }
    }

    private static String join(String... parts) {
        var sb = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                sb.append(part.strip());
            }
        }
        return sb.toString();
    }

    private static String bullets(List<String> items) {
        return items.isEmpty() ? null : items.stream().map(i -> "- " + i).collect(Collectors.joining("\n"));
    }

    private static String list(String label, List<String> items) {
        return items.isEmpty() ? null : label + ": " + String.join(", ", items);
    }
}

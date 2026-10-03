package br.com.gilvanborges.javai.ai.api.mcp;

import br.com.gilvanborges.javai.ai.api.AiApiProperties;
import br.com.gilvanborges.javai.ai.api.index.ActiveIndex;
import br.com.gilvanborges.javai.ai.api.index.IndexHolder;
import br.com.gilvanborges.javai.ai.api.index.QueryEmbedder;
import br.com.gilvanborges.javai.ai.core.chunk.Chunk;
import br.com.gilvanborges.javai.ai.core.chunk.SourceKind;
import br.com.gilvanborges.javai.ai.core.evidence.Evidence;
import br.com.gilvanborges.javai.ai.core.search.HybridSearcher;
import br.com.gilvanborges.javai.ai.core.text.TextNormalizer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

import static br.com.gilvanborges.javai.ai.api.mcp.Schemas.array;
import static br.com.gilvanborges.javai.ai.api.mcp.Schemas.bool;
import static br.com.gilvanborges.javai.ai.api.mcp.Schemas.integer;
import static br.com.gilvanborges.javai.ai.api.mcp.Schemas.object;
import static br.com.gilvanborges.javai.ai.api.mcp.Schemas.string;

/**
 * As ferramentas do MCP (spec 03). Todas só leem o índice ativo em memória: nenhuma escreve, nenhuma abre rede,
 * nenhuma monta SQL. Toda entrada é validada aqui de novo (tamanho, formato, idioma), porque o esquema do SDK é só
 * a primeira barreira. O que sai é texto legível mais {@code structuredContent}, com URLs que só o servidor escreve.
 */
@Component
public class McpTools {

    static final int SEARCH_LIMIT = 5;
    static final int EVIDENCE_LIMIT = 15;
    private static final int SKILL_EVIDENCE_SAMPLE = 3;
    private static final int SNIPPET = 700;
    private static final int README_EXCERPT = 1500;
    private static final Pattern FULL_NAME = Pattern.compile("^[A-Za-z0-9-]{1,39}/[A-Za-z0-9._-]{1,100}$");
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{0,79}$");

    private final IndexHolder holder;
    private final QueryEmbedder embedder;
    private final AiApiProperties properties;

    public McpTools(IndexHolder holder, QueryEmbedder embedder, AiApiProperties properties) {
        this.holder = holder;
        this.embedder = embedder;
        this.properties = properties;
    }

    public List<SyncToolSpecification> specifications() {
        Map<String, Object> lang = Map.of("lang", string("Language of the answer: \"en\" (default) or \"pt\".", List.of("en", "pt")));
        return List.of(
                tool("get_profile", "Get profile",
                        "The public professional profile of Gilvan Borges (headline, summary, location) and public links.",
                        object(lang, List.of()),
                        object(Map.of("lang", string("Language."), "title", string("Title."), "text", string("Profile text."),
                                "links", array(object(Map.of("label", string("Label."), "url", string("URL.")), List.of("label", "url")))),
                                List.of("lang", "text", "links")),
                        this::getProfile),
                tool("list_projects", "List projects",
                        "The public projects with slug, short summary and stack. Use get_project for the full story.",
                        object(lang, List.of()),
                        object(Map.of("lang", string("Language."), "count", integer("Number of projects."),
                                "projects", array(object(Map.of("slug", string("Slug."), "title", string("Title."),
                                        "summary", string("Summary."), "stack", array(string("Technology.")),
                                        "url", string("Source URL.")), List.of("slug", "title", "summary", "stack", "url")))),
                                List.of("lang", "count", "projects")),
                        this::listProjects),
                tool("get_project", "Get a project",
                        "The full description of one project: what it does, how it was built, results and limitations.",
                        object(merge(lang, Map.of("slug", string("The project slug, from list_projects."))), List.of("slug")),
                        object(Map.of("lang", string("Language."), "slug", string("Slug."), "title", string("Title."),
                                "text", string("Full description."), "url", string("Source URL.")),
                                List.of("lang", "slug", "title", "text", "url")),
                        this::getProject),
                tool("list_experience", "List experience",
                        "The professional experience, in the order of the CV.",
                        object(lang, List.of()),
                        object(Map.of("lang", string("Language."), "count", integer("Number of entries."),
                                "experiences", array(object(Map.of("company", string("Company."), "title", string("Title."),
                                        "text", string("Role, period, description, highlights and stack."), "url", string("Source URL.")),
                                        List.of("company", "title", "text", "url")))),
                                List.of("lang", "count", "experiences")),
                        this::listExperience),
                tool("get_skills", "Get skills",
                        "The skills Gilvan declares, each with how many places in his public code show it. Pass a skill name to filter.",
                        object(merge(lang, Map.of("skill", string("Optional skill name or part of it, 2 to 80 characters."))), List.of()),
                        object(Map.of("lang", string("Language."), "count", integer("Number of skills."),
                                "skills", array(object(Map.of("name", string("Skill."), "text", string("Category and notes."),
                                        "evidenceCount", integer("Places in public code."),
                                        "evidence", array(evidenceSchema())), List.of("name", "text", "evidenceCount", "evidence")))),
                                List.of("lang", "count", "skills")),
                        this::getSkills),
                tool("find_evidence", "Find evidence",
                        "Where the public code shows a technology, with links to the exact lines. found=false means no evidence was found, "
                                + "which is different from \"he does not know it\".",
                        object(Map.of("technology", string("Technology or skill, 2 to 80 characters, e.g. \"RabbitMQ\".")), List.of("technology")),
                        object(Map.of("technology", string("The technology asked."), "found", bool("Whether any evidence exists."),
                                "total", integer("Total matches."), "evidence", array(evidenceSchema())),
                                List.of("technology", "found", "total", "evidence")),
                        this::findEvidence),
                tool("list_repositories", "List repositories",
                        "The public repositories indexed, with kind and note.",
                        object(lang, List.of()),
                        object(Map.of("lang", string("Language."), "count", integer("Number of repositories."),
                                "repositories", array(object(Map.of("fullName", string("owner/name."), "text", string("Kind and note."),
                                        "url", string("Repository URL."), "indexedChunks", integer("Pieces of the repository indexed.")),
                                        List.of("fullName", "text", "url", "indexedChunks")))),
                                List.of("lang", "count", "repositories")),
                        this::listRepositories),
                tool("get_repository", "Get a repository",
                        "Summary of one indexed repository: kind, note, an excerpt of the README and the skills found in its code.",
                        object(merge(lang, Map.of("full_name", string("owner/name, from list_repositories."))), List.of("full_name")),
                        object(Map.of("lang", string("Language."), "fullName", string("owner/name."), "text", string("Kind and note."),
                                "readme", string("README excerpt."), "skills", array(string("Skill.")), "url", string("URL.")),
                                List.of("lang", "fullName", "text", "readme", "skills", "url")),
                        this::getRepository),
                tool("search", "Search",
                        "Hybrid search (keywords plus meaning) over everything indexed. Returns up to 5 passages with their source URL.",
                        object(merge(lang, Map.of("query", string("What to look for, 2 to 200 characters."))), List.of("query")),
                        object(Map.of("query", string("The query."), "lang", string("Language."), "mode", string("hybrid or bm25."),
                                "results", array(object(Map.of("rank", integer("Position."), "kind", string("Kind of source."),
                                        "title", string("Title."), "text", string("Passage."), "url", string("Source URL.")),
                                        List.of("rank", "kind", "title", "text", "url")))),
                                List.of("query", "lang", "mode", "results")),
                        this::search));
    }

    // ---------------------------------------------------------------- ferramentas

    private CallToolResult getProfile(Map<String, Object> args) {
        String lang = lang(args);
        return withIndex(index -> {
            List<Chunk> profile = index.withRef("profile", lang);
            if (profile.isEmpty()) {
                return Results.error("The profile is not available in this language.");
            }
            var links = new ArrayList<Map<String, Object>>();
            links.add(Map.of("label", "Portfolio", "url", properties.siteUrl()));
            links.add(Map.of("label", "GitHub", "url", "https://github.com/gilvan-Borges"));
            links.add(Map.of("label", "LinkedIn", "url", "https://www.linkedin.com/in/gilvanborges"));
            String text = joinBodies(profile);
            var out = new LinkedHashMap<String, Object>();
            out.put("lang", lang);
            out.put("title", profile.get(0).title());
            out.put("text", text);
            out.put("links", links);
            return Results.ok(profile.get(0).title() + "\n\n" + text + "\n\n" + links.stream()
                    .map(l -> l.get("label") + ": " + l.get("url")).reduce((a, b) -> a + "\n" + b).orElse(""), out);
        });
    }

    private CallToolResult listProjects(Map<String, Object> args) {
        String lang = lang(args);
        return withIndex(index -> {
            var projects = new ArrayList<Map<String, Object>>();
            for (Chunk c : index.ofKind(SourceKind.PROJECT, lang)) {
                if (c.sourceRef().indexOf(':', "project:".length()) >= 0) {
                    continue; // as partes ":solution" e ":results" só aparecem em get_project
                }
                var p = new LinkedHashMap<String, Object>();
                p.put("slug", c.sourceRef().substring("project:".length()));
                p.put("title", c.title());
                p.put("summary", clip(withoutStackLine(c.body()), 400));
                p.put("stack", stackOf(c.body()));
                p.put("url", c.url());
                projects.add(p);
            }
            var out = new LinkedHashMap<String, Object>();
            out.put("lang", lang);
            out.put("count", projects.size());
            out.put("projects", projects);
            StringBuilder text = new StringBuilder(projects.size() + " projects:\n");
            for (var p : projects) {
                text.append("- ").append(p.get("slug")).append(": ").append(p.get("summary")).append(" [")
                        .append(String.join(", ", castList(p.get("stack")))).append("]\n");
            }
            return Results.ok(text.toString().strip(), out);
        });
    }

    private CallToolResult getProject(Map<String, Object> args) {
        String lang = lang(args);
        String slug = text(args, "slug");
        return withIndex(index -> {
            var known = index.ofKind(SourceKind.PROJECT, lang).stream()
                    .filter(c -> c.sourceRef().indexOf(':', "project:".length()) < 0)
                    .map(c -> c.sourceRef().substring("project:".length())).toList();
            if (slug == null || !SLUG.matcher(slug).matches() || !known.contains(slug)) {
                return Results.error("Unknown project. Valid slugs: " + String.join(", ", known) + ".");
            }
            var parts = new ArrayList<Chunk>();
            for (String ref : List.of("project:" + slug, "project:" + slug + ":solution", "project:" + slug + ":results")) {
                parts.addAll(index.withRef(ref, lang));
            }
            var out = new LinkedHashMap<String, Object>();
            out.put("lang", lang);
            out.put("slug", slug);
            out.put("title", parts.get(0).title());
            String body = parts.stream().map(c -> c.title() + "\n" + c.body()).reduce((a, b) -> a + "\n\n" + b).orElse("");
            out.put("text", body);
            out.put("url", parts.get(0).url());
            return Results.ok(body, out);
        });
    }

    private CallToolResult listExperience(Map<String, Object> args) {
        String lang = lang(args);
        return withIndex(index -> {
            var items = new ArrayList<Map<String, Object>>();
            for (Chunk c : index.ofKind(SourceKind.EXPERIENCE, lang)) {
                var item = new LinkedHashMap<String, Object>();
                item.put("company", c.sourceRef().substring("experience:".length()));
                item.put("title", c.title());
                item.put("text", c.body());
                item.put("url", c.url());
                items.add(item);
            }
            var out = new LinkedHashMap<String, Object>();
            out.put("lang", lang);
            out.put("count", items.size());
            out.put("experiences", items);
            String text = items.stream().map(i -> i.get("title") + "\n" + i.get("text"))
                    .reduce((a, b) -> a + "\n\n" + b).orElse("No experience published.");
            return Results.ok(text, out);
        });
    }

    private CallToolResult getSkills(Map<String, Object> args) {
        String lang = lang(args);
        String filter = text(args, "skill");
        if (filter != null && (filter.length() < 2 || filter.length() > 80)) {
            return Results.error("skill must have 2 to 80 characters.");
        }
        return withIndex(index -> {
            var skills = new ArrayList<Map<String, Object>>();
            for (Chunk c : index.ofKind(SourceKind.SKILL, lang)) {
                String name = c.sourceRef().substring("skill:".length());
                if (filter != null && !fold(name).contains(fold(filter))) {
                    continue;
                }
                List<Evidence> proofs = index.evidence().stream().filter(e -> e.skill().equalsIgnoreCase(name)).toList();
                var skill = new LinkedHashMap<String, Object>();
                skill.put("name", name);
                skill.put("text", c.body());
                skill.put("evidenceCount", proofs.size());
                skill.put("evidence", proofs.stream().limit(SKILL_EVIDENCE_SAMPLE).map(McpTools::evidenceMap).toList());
                skills.add(skill);
            }
            var out = new LinkedHashMap<String, Object>();
            out.put("lang", lang);
            out.put("count", skills.size());
            out.put("skills", skills);
            StringBuilder text = new StringBuilder(skills.size() + " skills:\n");
            for (var s : skills) {
                text.append("- ").append(s.get("name")).append(" (").append(s.get("evidenceCount"))
                        .append(" places in public code)\n");
            }
            return Results.ok(text.toString().strip(), out);
        });
    }

    private CallToolResult findEvidence(Map<String, Object> args) {
        String technology = text(args, "technology");
        if (technology == null || technology.length() < 2 || technology.length() > 80) {
            return Results.error("technology must have 2 to 80 characters.");
        }
        return withIndex(index -> {
            String needle = fold(technology);
            List<Evidence> matches = index.evidence().stream()
                    .filter(e -> fold(e.skill()).contains(needle) || fold(e.detector()).contains(needle)).toList();
            var out = new LinkedHashMap<String, Object>();
            out.put("technology", technology);
            out.put("found", !matches.isEmpty());
            out.put("total", matches.size());
            out.put("evidence", matches.stream().limit(EVIDENCE_LIMIT).map(McpTools::evidenceMap).toList());
            if (matches.isEmpty()) {
                return Results.ok("No evidence of \"" + technology + "\" was found in the indexed public code. "
                        + "That does not mean he does not know it: private work and unindexed repositories are not covered.", out);
            }
            StringBuilder text = new StringBuilder(matches.size() + " matches for \"" + technology + "\"");
            if (matches.size() > EVIDENCE_LIMIT) {
                text.append(" (showing ").append(EVIDENCE_LIMIT).append(")");
            }
            text.append(":\n");
            matches.stream().limit(EVIDENCE_LIMIT).forEach(e -> text.append("- ").append(e.repository()).append(" ")
                    .append(e.path()).append(":").append(e.line()).append(" (").append(e.detector()).append(") ")
                    .append(e.url()).append("\n"));
            return Results.ok(text.toString().strip(), out);
        });
    }

    private CallToolResult listRepositories(Map<String, Object> args) {
        String lang = lang(args);
        return withIndex(index -> {
            var repos = new ArrayList<Map<String, Object>>();
            for (Chunk c : index.ofKind(SourceKind.REPO_META, lang)) {
                String fullName = c.sourceRef().substring("repo:".length());
                var repo = new LinkedHashMap<String, Object>();
                repo.put("fullName", fullName);
                repo.put("text", c.body());
                repo.put("url", c.url());
                repo.put("indexedChunks", indexedChunks(index, fullName));
                repos.add(repo);
            }
            var out = new LinkedHashMap<String, Object>();
            out.put("lang", lang);
            out.put("count", repos.size());
            out.put("repositories", repos);
            StringBuilder text = new StringBuilder(repos.size() + " repositories:\n");
            for (var r : repos) {
                text.append("- ").append(r.get("fullName")).append(": ").append(clip((String) r.get("text"), 200)).append("\n");
            }
            return Results.ok(text.toString().strip(), out);
        });
    }

    private CallToolResult getRepository(Map<String, Object> args) {
        String lang = lang(args);
        String fullName = text(args, "full_name");
        return withIndex(index -> {
            // Só repositórios que o dono ligou no CRUD e que o indexador leu: nada de pedir um repositório qualquer.
            Optional<Chunk> meta = fullName == null || !FULL_NAME.matcher(fullName).matches() ? Optional.empty()
                    : index.withRef("repo:" + fullName, lang).stream().findFirst();
            if (meta.isEmpty()) {
                return Results.error("Unknown repository. Use list_repositories for the valid full_name values.");
            }
            String readme = index.ofKind(SourceKind.REPO_README, lang).stream()
                    .filter(c -> fullName.equals(repoOf(c.sourceRef()))).map(Chunk::body)
                    .reduce((a, b) -> a + "\n\n" + b).orElse("");
            if (readme.isEmpty()) {
                readme = index.ofKind(SourceKind.REPO_README, lang.equals("en") ? "pt" : "en").stream()
                        .filter(c -> fullName.equals(repoOf(c.sourceRef()))).map(Chunk::body)
                        .reduce((a, b) -> a + "\n\n" + b).orElse("");
            }
            var skills = new LinkedHashSet<String>();
            index.evidence().stream().filter(e -> e.repository().equalsIgnoreCase(fullName)).forEach(e -> skills.add(e.skill()));
            var out = new LinkedHashMap<String, Object>();
            out.put("lang", lang);
            out.put("fullName", fullName);
            out.put("text", meta.get().body());
            out.put("readme", clip(readme, README_EXCERPT));
            out.put("skills", List.copyOf(skills));
            out.put("url", meta.get().url());
            return Results.ok(meta.get().title() + "\n" + meta.get().body() + "\n\nSkills found in the code: "
                    + (skills.isEmpty() ? "none" : String.join(", ", skills)) + "\n\nREADME excerpt:\n"
                    + clip(readme, README_EXCERPT), out);
        });
    }

    private CallToolResult search(Map<String, Object> args) {
        String lang = lang(args);
        String query = text(args, "query");
        if (query == null || query.length() < 2 || query.length() > 200) {
            return Results.error("query must have 2 to 200 characters.");
        }
        String clean = cleanQuery(query);
        if (clean.length() < 2) {
            return Results.error("query must have 2 to 200 characters.");
        }
        return withIndex(index -> {
            float[] vector = index.hasVectors() ? embedder.embed(clean, index.embeddingModel()).orElse(null) : null;
            List<HybridSearcher.Hit> hits = index.search(clean, vector, lang, SEARCH_LIMIT);
            var results = new ArrayList<Map<String, Object>>();
            StringBuilder text = new StringBuilder();
            int rank = 1;
            for (var hit : hits) {
                Chunk c = hit.chunk();
                var r = new LinkedHashMap<String, Object>();
                r.put("rank", rank);
                r.put("kind", c.kind().db());
                r.put("title", c.title());
                r.put("text", clip(c.body(), SNIPPET));
                r.put("url", c.url());
                results.add(r);
                text.append(rank).append(". ").append(c.title()).append("\n").append(clip(c.body(), SNIPPET))
                        .append("\n").append(c.url()).append("\n\n");
                rank++;
            }
            var out = new LinkedHashMap<String, Object>();
            out.put("query", clean);
            out.put("lang", lang);
            out.put("mode", vector != null ? "hybrid" : "bm25");
            out.put("results", results);
            return Results.ok(results.isEmpty() ? "No passage matched." : text.toString().strip(), out);
        });
    }

    // ---------------------------------------------------------------- apoio

    private CallToolResult withIndex(java.util.function.Function<ActiveIndex, CallToolResult> body) {
        Optional<ActiveIndex> index = holder.current();
        if (index.isEmpty()) {
            return Results.error("The index is not available yet. Try again in a minute.");
        }
        return body.apply(index.get());
    }

    private static SyncToolSpecification tool(String name, String title, String description, Map<String, Object> input,
                                              Map<String, Object> output, java.util.function.Function<Map<String, Object>, CallToolResult> handler) {
        var tool = McpSchema.Tool.builder()
                .name(name)
                .title(title)
                .description(description)
                .inputSchema(input)
                .outputSchema(output)
                .annotations(new McpSchema.ToolAnnotations(title, true, false, true, false, null))
                .build();
        BiFunction<io.modelcontextprotocol.common.McpTransportContext, CallToolRequest, CallToolResult> call =
                (ctx, request) -> {
                    try {
                        return handler.apply(request.arguments() == null ? Map.of() : request.arguments());
                    } catch (BadInput e) {
                        return Results.error(e.getMessage());
                    }
                };
        return new SyncToolSpecification(tool, call);
    }

    /** O idioma pedido: {@code en} por padrão. Qualquer outro valor é erro de entrada. */
    private static String lang(Map<String, Object> args) {
        Object value = args.get("lang");
        if (value == null) {
            return "en";
        }
        if (value instanceof String s && (s.equals("en") || s.equals("pt"))) {
            return s;
        }
        throw new BadInput("lang must be \"en\" or \"pt\".");
    }

    private static String text(Map<String, Object> args, String key) {
        return args.get(key) instanceof String s ? s.strip() : null;
    }

    static final class BadInput extends RuntimeException {
        BadInput(String message) {
            super(message, null, false, false);
        }
    }

    private static Map<String, Object> evidenceMap(Evidence e) {
        var m = new LinkedHashMap<String, Object>();
        m.put("skill", e.skill());
        m.put("repository", e.repository());
        m.put("path", e.path());
        m.put("line", e.line());
        m.put("detector", e.detector());
        m.put("url", e.url());
        return m;
    }

    private static Map<String, Object> evidenceSchema() {
        return object(Map.of("skill", string("Skill."), "repository", string("owner/name."), "path", string("File path."),
                "line", integer("Line."), "detector", string("Rule that found it."), "url", string("Link to the line, pinned to a commit.")),
                List.of("skill", "repository", "path", "line", "detector", "url"));
    }

    private static int indexedChunks(ActiveIndex index, String fullName) {
        int count = 0;
        for (SourceKind kind : List.of(SourceKind.REPO_README, SourceKind.REPO_FILE)) {
            for (String lang : List.of("pt", "en")) {
                count += (int) index.ofKind(kind, lang).stream().filter(c -> fullName.equals(repoOf(c.sourceRef()))).count();
            }
        }
        return count;
    }

    /** {@code repo_readme:owner/name:path:section} → {@code owner/name}. */
    private static String repoOf(String sourceRef) {
        String[] parts = sourceRef.split(":", 3);
        return parts.length >= 2 ? parts[1] : "";
    }

    private static String joinBodies(List<Chunk> chunks) {
        return chunks.stream().map(Chunk::body).reduce((a, b) -> a + "\n\n" + b).orElse("");
    }

    private static List<String> stackOf(String body) {
        for (String line : body.split("\n")) {
            for (String label : List.of("Tecnologias: ", "Stack: ")) {
                if (line.startsWith(label)) {
                    return List.of(line.substring(label.length()).split(",\\s*"));
                }
            }
        }
        return List.of();
    }

    private static String withoutStackLine(String body) {
        var kept = new ArrayList<String>();
        for (String line : body.split("\n")) {
            if (!line.startsWith("Tecnologias: ") && !line.startsWith("Stack: ") && !line.isBlank()) {
                kept.add(line);
            }
        }
        return String.join(" ", kept);
    }

    static String clip(String text, int max) {
        if (text == null || text.length() <= max) {
            return text == null ? "" : text;
        }
        return text.substring(0, max - 1).stripTrailing() + "…";
    }

    static String cleanQuery(String query) {
        return cleanText(query, false);
    }

    /**
     * NFKC, sem caracteres de controle ou de formatação (nada de texto invisível), com os espaços colapsados.
     * Mantém acento e caixa. {@code keepNewlines} preserva as quebras de linha (descrição de vaga).
     */
    static String cleanText(String text, boolean keepNewlines) {
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

    private static String fold(String text) {
        return TextNormalizer.normalize(text).toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private static List<String> castList(Object value) {
        return (List<String>) value;
    }

    private static Map<String, Object> merge(Map<String, ?> a, Map<String, ?> b) {
        var out = new LinkedHashMap<String, Object>(a);
        out.putAll(b);
        return out;
    }
}

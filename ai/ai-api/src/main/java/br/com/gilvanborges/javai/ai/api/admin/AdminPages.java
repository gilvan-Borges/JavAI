package br.com.gilvanborges.javai.ai.api.admin;

import br.com.gilvanborges.javai.ai.api.chat.ChatProperties;
import br.com.gilvanborges.javai.ai.api.chat.PromptBuilder;
import br.com.gilvanborges.javai.ai.api.chat.Router;
import br.com.gilvanborges.javai.ai.api.index.ActiveIndex;
import br.com.gilvanborges.javai.ai.api.index.IndexRepository;
import br.com.gilvanborges.javai.ai.api.usage.LatencyStats;
import br.com.gilvanborges.javai.ai.api.usage.UsageStore;
import br.com.gilvanborges.javai.ai.core.chunk.Chunk;
import br.com.gilvanborges.javai.ai.core.chunk.SourceKind;
import br.com.gilvanborges.javai.ai.core.evidence.Evidence;
import br.com.gilvanborges.javai.ai.core.repo.RepoFileFilter;
import br.com.gilvanborges.javai.ai.core.search.HybridSearcher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static br.com.gilvanborges.javai.ai.api.admin.Html.esc;

/**
 * As telas do admin, em HTML puro gerado no servidor: sem JavaScript, sem recurso externo, e com uma política de
 * conteúdo que proíbe até script inline. Todo dado passa por {@link Html#esc}.
 */
final class AdminPages {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm 'BRT'")
            .withZone(ZoneId.of("America/Sao_Paulo"));
    private static final Map<SourceKind, String> KIND_LABEL = new LinkedHashMap<>();

    static {
        KIND_LABEL.put(SourceKind.PROFILE, "Perfil e formação");
        KIND_LABEL.put(SourceKind.EXPERIENCE, "Experiências");
        KIND_LABEL.put(SourceKind.PROJECT, "Projetos");
        KIND_LABEL.put(SourceKind.FAQ, "Perguntas frequentes");
        KIND_LABEL.put(SourceKind.SKILL, "Habilidades");
        KIND_LABEL.put(SourceKind.REPO_META, "Repositórios (ficha)");
        KIND_LABEL.put(SourceKind.REPO_README, "README dos repositórios");
        KIND_LABEL.put(SourceKind.REPO_FILE, "Arquivos de código");
        KIND_LABEL.put(SourceKind.SITE, "Site (texto público)");
        KIND_LABEL.put(SourceKind.DOCUMENT, "Documentos e links (currículo, LinkedIn)");
        KIND_LABEL.put(SourceKind.GITHUB_PROFILE, "Perfil do GitHub");
    }

    private AdminPages() {
    }

    /** Tudo que a visão geral precisa saber. {@code index} e {@code meta} são nulos se ainda não houver índice. */
    record Overview(ActiveIndex index, IndexRepository.VersionMeta meta, ChatProperties chat, List<UsageStore.Day> usage,
                    BigDecimal spentToday, int blockedVisitors, Map<String, LatencyStats.Percentiles> latencies) {
    }

    // ------------------------------------------------------------------------------------------------ estrutura

    static String layout(String title, String active, String body) {
        return "<!doctype html><html lang=\"pt-BR\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta name=\"robots\" content=\"noindex,nofollow\"><title>" + esc(title) + " · JavAI admin</title><style>" + CSS + "</style></head><body>"
                + "<header><strong>JavAI · admin</strong><nav>"
                + link("/admin", "Visão geral", active.equals("home"))
                + link("/admin/trechos", "Trechos indexados", active.equals("trechos"))
                + link("/admin/fontes", "Fontes e reindexação", active.equals("fontes"))
                + link("/admin/busca", "Testar a busca", active.equals("busca"))
                + "</nav><form method=\"post\" action=\"/admin/logout\"><button>Sair</button></form></header><main>" + body + "</main></body></html>";
    }

    private static String link(String href, String label, boolean active) {
        return "<a href=\"" + href + "\"" + (active ? " class=\"on\"" : "") + ">" + esc(label) + "</a>";
    }

    static String loginPage(String error) {
        return "<!doctype html><html lang=\"pt-BR\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta name=\"robots\" content=\"noindex,nofollow\"><title>Entrar · JavAI admin</title><style>" + CSS + "</style></head>"
                + "<body class=\"login\"><form method=\"post\" action=\"/admin/login\" autocomplete=\"off\"><h1>JavAI · admin</h1>"
                + (error == null ? "" : "<p class=\"err\">" + esc(error) + "</p>")
                + "<label>Usuário<input name=\"username\" maxlength=\"100\" autocomplete=\"username\" required autofocus></label>"
                + "<label>Senha<input name=\"password\" type=\"password\" maxlength=\"200\" autocomplete=\"current-password\" required></label>"
                + "<button>Entrar</button></form></body></html>";
    }

    // ------------------------------------------------------------------------------------------------ visão geral

    static String overview(Overview o) {
        var b = new StringBuilder();
        b.append("<h1>O que a IA pode consultar</h1>");
        if (o.index() == null) {
            return layout("Visão geral", "home", b.append("<p class=\"warn\">Ainda não há índice ativo. O indexador monta o primeiro na próxima execução.</p>").toString());
        }
        ActiveIndex index = o.index();
        JsonNode stats = parse(o.meta() == null ? null : o.meta().stats());
        JsonNode etags = parse(o.meta() == null ? null : o.meta().snapshotEtag());

        long withVectors = index.hasVectors() ? index.chunkCount() : 0;
        b.append("<section class=\"cards\">")
                .append(card("Índice ativo", "versão " + index.versionId(), o.meta() == null || o.meta().activatedAt() == null ? "" : "ativado em " + WHEN.format(o.meta().activatedAt())))
                .append(card("Trechos", String.valueOf(index.chunkCount()), withVectors + " com vetor (busca por significado)"))
                .append(card("Evidências no código", String.valueOf(index.evidence().size()), distinctSkills(index) + " habilidades com prova"))
                .append(card("Embeddings", esc(index.embeddingModel() == null ? "nenhum" : index.embeddingModel()), "rodam dentro do servidor, sem enviar texto a ninguém"))
                .append("</section>");

        b.append("<h2>O que está cadastrado, por tipo e idioma</h2>").append(kindTable(index));
        b.append("<h2>Repositórios do GitHub que a IA leu</h2>").append(repoTable(index, etags, stats));
        b.append("<h2>Habilidades com evidência no código</h2>").append(evidenceTable(index));

        b.append("<h2>Como o RAG está sendo usado</h2>").append(ragSection(o, index));
        b.append("<h2>Treinamento (fine-tuning)</h2>").append("""
                <div class="note"><strong>Nenhum.</strong> O modelo que responde (<code>%s</code>, via OpenRouter) é de uso geral e nunca foi treinado com os seus
                dados, e o modelo de embeddings também não foi ajustado. Tudo o que a IA sabe sobre você vem do <strong>RAG</strong>: a cada pergunta ela
                busca trechos no índice acima e os manda junto no prompt, só para aquela resposta. Para mudar o que ela sabe, basta mudar o conteúdo
                (CRUD ou repositório) e esperar o indexador: não há retreino. Nenhuma pergunta nem resposta é gravada em banco ou log; só contadores
                agregados. Respostas recentes ficam algumas horas num cache em memória (só o hash da pergunta + a resposta), para a mesma
                pergunta não pagar o modelo duas vezes; ele morre com o processo e se invalida quando o índice ou o prompt mudam.</div>
                """.formatted(esc(String.join(", ", o.chat().modelsFast()))));
        b.append("<h2>O que vai para o modelo a cada pergunta</h2>").append(promptSection());
        b.append("<h2>Latência por etapa (desde o último reinício)</h2>").append(latencySection(o));
        b.append("<h2>Uso nos últimos 30 dias</h2>").append(usageSection(o));
        b.append("<h2>Limites e segurança agora</h2>").append(securitySection(o));
        b.append("<h2>O que a IA <em>não</em> acessa</h2>").append(boundariesSection());
        b.append("<h2>Última indexação</h2>").append(indexerSection(stats));
        return layout("Visão geral", "home", b.toString());
    }

    private static String card(String title, String value, String sub) {
        return "<div class=\"card\"><span>" + esc(title) + "</span><b>" + value + "</b><small>" + esc(sub) + "</small></div>";
    }

    private static long distinctSkills(ActiveIndex index) {
        return index.evidence().stream().map(e -> e.skill().toLowerCase()).distinct().count();
    }

    private static String kindTable(ActiveIndex index) {
        var b = new StringBuilder("<table><tr><th>Tipo</th><th>pt</th><th>en</th><th>Caracteres</th></tr>");
        for (var entry : KIND_LABEL.entrySet()) {
            long pt = index.ofKind(entry.getKey(), "pt").size();
            long en = index.ofKind(entry.getKey(), "en").size();
            long chars = index.chunks().stream().filter(c -> c.kind() == entry.getKey()).mapToLong(c -> c.body().length()).sum();
            b.append("<tr><td><a href=\"/admin/trechos?kind=").append(entry.getKey().db()).append("\">").append(esc(entry.getValue()))
                    .append("</a></td><td>").append(pt).append("</td><td>").append(en).append("</td><td>").append(chars).append("</td></tr>");
        }
        return b.append("</table>").toString();
    }

    private static String repoTable(ActiveIndex index, JsonNode etags, JsonNode stats) {
        Map<String, long[]> perRepo = new TreeMap<>(); // meta, readme, arquivos
        for (Chunk c : index.chunks()) {
            String repo = repoOf(c);
            if (repo == null) {
                continue;
            }
            long[] n = perRepo.computeIfAbsent(repo, r -> new long[3]);
            if (c.kind() == SourceKind.REPO_META) {
                n[0]++;
            } else if (c.kind() == SourceKind.REPO_README) {
                n[1]++;
            } else {
                n[2]++;
            }
        }
        var b = new StringBuilder();
        if (perRepo.isEmpty()) {
            return "<p class=\"warn\">Nenhum repositório foi lido.</p>";
        }
        b.append("<table><tr><th>Repositório</th><th>Commit lido</th><th>Ficha</th><th>README</th><th>Arquivos</th><th>Habilidades detectadas</th></tr>");
        for (var e : perRepo.entrySet()) {
            String commit = etags.path("repo:" + e.getKey()).asString("");
            long skills = index.evidence().stream().filter(ev -> ev.repository().equalsIgnoreCase(e.getKey())).map(Evidence::skill).distinct().count();
            b.append("<tr><td><a href=\"/admin/trechos?repo=").append(esc(e.getKey())).append("\">").append(esc(e.getKey())).append("</a></td><td><code>")
                    .append(esc(commit.length() > 7 ? commit.substring(0, 7) : commit)).append("</code></td><td>").append(e.getValue()[0]).append("</td><td>")
                    .append(e.getValue()[1]).append("</td><td>").append(e.getValue()[2]).append("</td><td>").append(skills).append("</td></tr>");
        }
        b.append("</table>");
        JsonNode skipped = stats.path("repositoriosPulados");
        if (skipped.isArray() && !skipped.isEmpty()) {
            b.append("<p class=\"warn\">Pulados na última indexação:</p><ul>");
            skipped.forEach(s -> b.append("<li>").append(esc(s.isString() ? s.asString() : s.toString())).append("</li>"));
            b.append("</ul>");
        }
        return b.toString();
    }

    private static String evidenceTable(ActiveIndex index) {
        Map<String, List<Evidence>> bySkill = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        index.evidence().forEach(e -> bySkill.computeIfAbsent(e.skill(), k -> new ArrayList<>()).add(e));
        if (bySkill.isEmpty()) {
            return "<p>Nenhuma evidência detectada.</p>";
        }
        var b = new StringBuilder("<table><tr><th>Habilidade</th><th>Ocorrências</th><th>Repositórios</th></tr>");
        bySkill.forEach((skill, list) -> b.append("<tr><td>").append(esc(skill)).append("</td><td>").append(list.size()).append("</td><td>")
                .append(esc(String.join(", ", list.stream().map(Evidence::repository).distinct().sorted().toList()))).append("</td></tr>"));
        return b.append("</table>").toString();
    }

    private static String ragSection(Overview o, ActiveIndex index) {
        ChatProperties c = o.chat();
        return "<ol class=\"steps\">"
                + "<li><b>Jev classifica</b> a pergunta (perfil, código, vaga, ataque, fora do escopo). Ataque com confiança ≥ " + pct(Router.attackThreshold())
                + " e fora do escopo com confiança ≥ " + pct(Router.offTopicThreshold()) + " são recusados <i>sem chamar o modelo</i>.</li>"
                + "<li><b>Busca híbrida</b> no índice acima: BM25 (palavras) + cosseno entre embeddings (significado)"
                + (index.hasVectors() ? "" : " <span class=\"warn\">(sem vetores nesta versão: só BM25)</span>")
                + ", combinados por RRF (k = 60). Os 5 melhores trechos seguem.</li>"
                + "<li>Perguntas de <b>perfil</b> ganham sempre o perfil e as experiências; as de <b>vaga</b> ganham também a visão geral dos projetos.</li>"
                + "<li>O <b>roteador</b> escolhe o nível (rápido ou forte). Hoje: rápido = <code>" + esc(String.join(", ", c.modelsFast()))
                + "</code>; forte = <code>" + esc(String.join(", ", c.modelsStrong())) + "</code>.</li>"
                + "<li>O modelo responde por streaming (até " + c.maxTokens() + " tokens), citando <code>[n]</code>; <b>nunca escreve URL</b>: o servidor monta os links.</li>"
                + "<li>A <b>guarda de saída</b> retrata a resposta se aparecer URL, HTML, imagem, o canário do prompt ou uma citação que não existe.</li>"
                + "</ol>";
    }

    private static String promptSection() {
        String system = PromptBuilder.build("‹pergunta do visitante›", "pt", List.of(), List.of(), "‹canário da versão do prompt›").system();
        return "<p>Cada pergunta vira duas mensagens para o modelo. A primeira é fixa e <b>não tem nenhum segredo</b> (versão <code>"
                + esc(PromptBuilder.VERSION) + "</code>):</p><pre>" + esc(system) + "</pre>"
                + "<p>A segunda leva, dentro de blocos delimitados que o modelo trata como dado, os trechos escolhidos pela busca "
                + "(<code>&lt;fonte id=\"n\"&gt;</code>), até 4 turnos anteriores assinados (<code>&lt;historico&gt;</code>) e a pergunta (<code>&lt;pergunta&gt;</code>). "
                + "Para ver quais trechos uma pergunta traria, use <a href=\"/admin/busca\">Testar a busca</a>.</p>";
    }

    private static final Map<String, String> STEP_LABEL = Map.of(
            "classify", "Classificação (Jev)", "search", "Busca híbrida", "model", "Modelo (streaming completo)", "total", "Resposta inteira");

    /** p50 e p90 por etapa do pipeline, das últimas 512 respostas reais (as de cache ficam de fora). */
    private static String latencySection(Overview o) {
        if (o.latencies() == null || o.latencies().isEmpty()) {
            return "<p class=\"dim\">Ainda não há respostas medidas desde o último reinício.</p>";
        }
        var b = new StringBuilder("<table><tr><th>Etapa</th><th>p50</th><th>p90</th><th>Medições</th></tr>");
        o.latencies().forEach((step, p) -> b.append("<tr><td>").append(esc(STEP_LABEL.getOrDefault(step, step)))
                .append("</td><td>").append(p.p50()).append(" ms</td><td>").append(p.p90()).append(" ms</td><td>")
                .append(p.count()).append("</td></tr>"));
        return b.append("</table>").toString();
    }

    private static String usageSection(Overview o) {
        long questions = 0;
        long answered = 0;
        long refusals = 0;
        long retracts = 0;
        long mcp = 0;
        long tokensIn = 0;
        long tokensOut = 0;
        BigDecimal cost = BigDecimal.ZERO;
        Map<String, Integer> byModel = new TreeMap<>();
        for (UsageStore.Day d : o.usage()) {
            mcp += d.mcpCalls();
            if (d.model().equals("mcp")) {
                continue;
            }
            questions += d.requests();
            refusals += d.refusals();
            retracts += d.retracts();
            cost = cost.add(d.costUsd());
            tokensIn += d.tokensIn();
            tokensOut += d.tokensOut();
            if (!d.model().equals("none")) {
                answered += d.requests();
                byModel.merge(d.model(), d.requests(), Integer::sum);
            }
        }
        BigDecimal avg = answered == 0 ? BigDecimal.ZERO : cost.divide(BigDecimal.valueOf(answered), 5, RoundingMode.HALF_UP);
        return "<table><tr><th>Perguntas</th><th>Respondidas</th><th>Recusadas</th><th>Retratadas pela guarda</th><th>Custo total</th><th>Custo médio</th><th>Tokens (entrada/saída)</th><th>Chamadas MCP</th></tr>"
                + "<tr><td>" + questions + "</td><td>" + answered + "</td><td>" + refusals + "</td><td>" + retracts + "</td><td>US$ " + cost.setScale(4, RoundingMode.HALF_UP)
                + "</td><td>US$ " + avg + "</td><td>" + tokensIn + " / " + tokensOut + "</td><td>" + mcp + "</td></tr></table>"
                + "<p>Respostas por modelo: " + (byModel.isEmpty() ? "nenhuma ainda" : esc(byModel.toString())) + ". Gasto de hoje: <b>US$ "
                + o.spentToday().setScale(4, RoundingMode.HALF_UP) + "</b> de US$ " + esc(o.chat().dailyBudgetUsd()) + " (teto diário).</p>"
                + "<p class=\"dim\">São só contadores agregados: o serviço não guarda perguntas, respostas nem IPs.</p>";
    }

    private static String securitySection(Overview o) {
        ChatProperties c = o.chat();
        return "<table><tr><th>Item</th><th>Valor</th></tr>"
                + row("Chat pronto para responder", c.ready() ? "sim" : "<span class=\"warn\">não</span> (falta chave, Turnstile ou modelo)")
                + row("Chave do OpenRouter configurada", c.openrouterKey().isBlank() ? "<span class=\"warn\">não</span>" : "sim (o valor nunca aparece aqui)")
                + row("Turnstile (verificação humana) configurado", c.turnstileSecret().isBlank() ? "<span class=\"warn\">não</span>" : "sim")
                + row("Perguntas por minuto, por IP", String.valueOf(c.perMinute()))
                + row("Limite diário por IP", c.perDay() <= 0 ? "sem limite (vale o teto de gasto)" : String.valueOf(c.perDay()))
                + row("Bloqueio por ataque", c.banHours() <= 0 ? "desligado" : c.banHours() + " h (confiança ≥ " + pct(c.banConfidence()) + " ou " + c.banStrikes() + " recusas por ataque)")
                + row("Visitantes bloqueados agora", String.valueOf(o.blockedVisitors()))
                + row("Provedor do modelo", "data_collection = deny" + (c.zdr() ? ", retenção zero" : "") + (c.maxPriceCompletion().signum() > 0 ? ", teto US$ " + esc(c.maxPriceCompletion()) + "/M tokens de saída" : ""))
                + "</table>";
    }

    private static String boundariesSection() {
        return "<ul>"
                + "<li>O <b>banco do CRUD</b> (rascunhos, notas privadas, dados não publicados): outro banco, outra rede e nenhuma credencial deste lado. A IA só vê o snapshot público.</li>"
                + "<li><b>Repositórios privados e forks</b>: nunca. Cada repositório é conferido de novo na API do GitHub antes do clone.</li>"
                + "<li>Dos repositórios públicos, só entram arquivos de lista permitida: <code>.md</code>, manifestos (<code>" + esc(String.join(", ", RepoFileFilter.manifestNames()))
                + "</code>), código (<code>" + esc(String.join(" ", RepoFileFilter.codeExtensions())) + "</code>), <code>docker-compose</code>, workflows do GitHub Actions e migrações SQL, de até "
                + RepoFileFilter.MAX_FILE_BYTES / 1024 + " KB.</li>"
                + "<li><b>Nunca</b>: <code>.env</code>, <code>application*.properties/yml</code>, chaves e certificados, lockfiles, binários e as pastas <code>"
                + esc(String.join(" ", RepoFileFilter.deniedDirectories())) + "</code>. Um scanner de segredos ainda descarta o arquivo que contenha uma senha, chave ou token.</li>"
                + "<li>O papel do banco da IA (<code>ai_reader</code>) só <b>lê</b> o índice ativo; não escreve nele, não ativa versão e não lê o histórico do indexador.</li>"
                + "</ul>";
    }

    private static String indexerSection(JsonNode stats) {
        if (stats.isMissingNode() || stats.isEmpty()) {
            return "<p>Sem relatório gravado.</p>";
        }
        var b = new StringBuilder("<table><tr><th>Item</th><th>Valor</th></tr>");
        stats.properties().forEach(e -> {
            JsonNode v = e.getValue();
            String text = v.isArray() ? v.size() + " item(ns)" : v.isObject() ? v.toString() : v.asString();
            b.append(row(e.getKey(), esc(text)));
        });
        b.append("</table>");
        JsonNode notes = stats.path("avisos");
        if (notes.isArray() && !notes.isEmpty()) {
            b.append("<details><summary>Avisos (").append(notes.size()).append(")</summary><ul>");
            int shown = 0;
            for (JsonNode n : notes) {
                if (shown++ >= 40) {
                    break;
                }
                b.append("<li>").append(esc(n.isString() ? n.asString() : n.toString())).append("</li>");
            }
            b.append("</ul></details>");
        }
        return b.toString();
    }

    private static String row(String key, String htmlValue) {
        return "<tr><td>" + esc(key) + "</td><td>" + htmlValue + "</td></tr>";
    }

    private static String pct(double value) {
        return Math.round(value * 100) + "%";
    }

    // ------------------------------------------------------------------------------------------------ trechos

    static final int PAGE = 25;

    static String chunks(ActiveIndex index, String kind, String lang, String repo, int page) {
        var b = new StringBuilder("<h1>Trechos indexados</h1>");
        if (index == null) {
            return layout("Trechos", "trechos", b.append("<p class=\"warn\">Ainda não há índice ativo.</p>").toString());
        }
        List<Chunk> filtered = index.chunks().stream()
                .filter(c -> kind.isEmpty() || c.kind().db().equals(kind))
                .filter(c -> lang.isEmpty() || c.lang().equals(lang))
                .filter(c -> repo.isEmpty() || repo.equalsIgnoreCase(repoOf(c)))
                .toList();
        int pages = Math.max(1, (filtered.size() + PAGE - 1) / PAGE);
        int current = Math.min(Math.max(page, 0), pages - 1);

        b.append("<form method=\"get\" action=\"/admin/trechos\" class=\"filters\"><select name=\"kind\"><option value=\"\">todos os tipos</option>");
        KIND_LABEL.forEach((k, label) -> b.append("<option value=\"").append(k.db()).append("\"").append(k.db().equals(kind) ? " selected" : "").append(">").append(esc(label)).append("</option>"));
        b.append("</select><select name=\"lang\"><option value=\"\">pt e en</option><option value=\"pt\"").append(lang.equals("pt") ? " selected" : "")
                .append(">pt</option><option value=\"en\"").append(lang.equals("en") ? " selected" : "").append(">en</option></select>")
                .append("<input name=\"repo\" placeholder=\"dono/repositório\" value=\"").append(esc(repo)).append("\" maxlength=\"140\"><button>Filtrar</button></form>");
        b.append("<p>").append(filtered.size()).append(" trecho(s). Página ").append(current + 1).append(" de ").append(pages).append(".</p>");

        for (Chunk c : filtered.subList(current * PAGE, Math.min(filtered.size(), (current + 1) * PAGE))) {
            b.append("<details class=\"chunk\"><summary><span class=\"tag\">").append(esc(c.kind().db())).append("</span> <span class=\"tag\">").append(esc(c.lang()))
                    .append("</span> ").append(esc(c.title())).append(" <small>(").append(c.body().length()).append(" caracteres)</small></summary>")
                    .append("<p class=\"dim\">fonte: <code>").append(esc(c.sourceRef())).append("</code> · <a href=\"").append(esc(c.url()))
                    .append("\" rel=\"noopener noreferrer nofollow\">").append(esc(c.url())).append("</a></p><pre>").append(esc(c.body())).append("</pre></details>");
        }
        b.append("<p class=\"pager\">");
        if (current > 0) {
            b.append("<a href=\"").append(pageUrl(kind, lang, repo, current - 1)).append("\">← anterior</a> ");
        }
        if (current < pages - 1) {
            b.append("<a href=\"").append(pageUrl(kind, lang, repo, current + 1)).append("\">próxima →</a>");
        }
        return layout("Trechos", "trechos", b.append("</p>").toString());
    }

    private static String pageUrl(String kind, String lang, String repo, int page) {
        return "/admin/trechos?kind=" + urlEnc(kind) + "&amp;lang=" + urlEnc(lang) + "&amp;repo=" + urlEnc(repo) + "&amp;page=" + page;
    }

    private static String urlEnc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------------------------------------ busca

    static String search(String query, String lang, List<HybridSearcher.Hit> hits, boolean usedVectors) {
        var b = new StringBuilder("<h1>Testar a busca</h1><p>Digite uma pergunta e veja <b>exatamente quais trechos</b> o RAG mandaria ao modelo (sem chamar nenhum modelo, sem custo).</p>");
        b.append("<form method=\"get\" action=\"/admin/busca\" class=\"filters\"><input name=\"q\" value=\"").append(esc(query)).append("\" maxlength=\"200\" placeholder=\"Ele já usou RabbitMQ?\" style=\"min-width:22rem\">")
                .append("<select name=\"lang\"><option value=\"pt\"").append(lang.equals("pt") ? " selected" : "").append(">pt</option><option value=\"en\"")
                .append(lang.equals("en") ? " selected" : "").append(">en</option></select><button>Buscar</button></form>");
        if (query.isEmpty()) {
            return layout("Busca", "busca", b.toString());
        }
        b.append("<p>Modo: <b>").append(usedVectors ? "híbrido (BM25 + embeddings, RRF)" : "só BM25 (o modelo de embeddings não está disponível)")
                .append("</b>. ").append(hits.size()).append(" resultado(s).</p>");
        int rank = 1;
        for (HybridSearcher.Hit hit : hits) {
            Chunk c = hit.chunk();
            b.append("<details class=\"chunk\" open><summary><b>").append(rank++).append(".</b> <span class=\"tag\">").append(esc(c.kind().db())).append("</span> ")
                    .append(esc(c.title())).append(" <small>pontuação RRF ").append(String.format(java.util.Locale.ROOT, "%.4f", hit.score())).append("</small></summary>")
                    .append("<p class=\"dim\"><code>").append(esc(c.sourceRef())).append("</code></p><pre>").append(esc(c.body().length() > 700 ? c.body().substring(0, 700) + "…" : c.body()))
                    .append("</pre></details>");
        }
        return layout("Busca", "busca", b.toString());
    }

    // ------------------------------------------------------------------------------------------------ apoio

    /** {@code dono/nome} de um trecho de repositório ({@code repo:dono/nome} ou {@code repo_readme:dono/nome:caminho...}). */
    static String repoOf(Chunk c) {
        if (c.kind() != SourceKind.REPO_META && c.kind() != SourceKind.REPO_README && c.kind() != SourceKind.REPO_FILE) {
            return null;
        }
        String[] parts = c.sourceRef().split(":", 3);
        return parts.length >= 2 ? parts[1] : null;
    }

    // ------------------------------------------------------------------------------------------------ fontes públicas

    /**
     * A página "Fontes": de onde a IA tira o que sabe (configurado por propriedades), o resultado da leitura de cada
     * fonte na última execução e o botão de reindexar tudo.
     *
     * @param status  resposta do indexador, ou {@code null} se ele não respondeu
     * @param notice  mensagem do último clique no botão, ou vazio
     */
    static String sources(JsonNode status, String notice, boolean canTrigger) {
        var b = new StringBuilder("<h1>Fontes públicas da IA</h1>");
        b.append("<p class=\"dim\">Tudo vem de endereços públicos configurados por propriedade (<code>INDEXER_SOURCES_*</code> no <code>.env</code>). ")
                .append("O texto entra no índice literalmente, com o link de origem: nada é reescrito por IA.</p>");
        if (!notice.isEmpty()) {
            b.append("<div class=\"note\">").append(esc(notice)).append("</div>");
        }
        if (status == null) {
            b.append("<p class=\"warn\">O indexador não respondeu (ou a porta interna está desligada). Sem ele não dá para ver as fontes nem reindexar por aqui.</p>");
            return layout("Fontes", "fontes", b.toString());
        }
        JsonNode cfg = status.path("fontes");
        b.append("<h2>Configuração</h2><table><tr><th>Propriedade</th><th>Valor</th></tr>");
        cfg.propertyNames().forEach(name -> {
            JsonNode v = cfg.path(name);
            String text = v.isArray() ? joinArray(v) : v.asString("");
            b.append("<tr><td><code>").append(esc(name)).append("</code></td><td>").append(text.isEmpty() ? "<span class=\"dim\">(vazio)</span>" : esc(text)).append("</td></tr>");
        });
        b.append("</table><p class=\"dim\">O LinkedIn não permite leitura automática: ele entra só como link. Para a IA conhecer o conteúdo, publique o texto no site ")
                .append("(<code>llms-full.txt</code>) ou um PDF do currículo e configure em <code>documents</code>.</p>");

        boolean running = status.path("rodando").asBoolean();
        if (running) {
            b.append("<p class=\"warn\">Há uma indexação em andamento. Atualize a página em alguns segundos.</p>");
        }
        if (canTrigger) {
            b.append("<h2>1. Apagar a base de conhecimento</h2>")
                    .append("<form method=\"post\" action=\"/admin/apagar\"><label><input type=\"checkbox\" name=\"confirmo\" value=\"sim\" required> ")
                    .append("Entendo que a IA fica sem nenhum dado e não responde sobre você até o passo 2.</label> <button class=\"danger\"")
                    .append(running ? " disabled" : "").append(">Apagar base de conhecimento</button></form>")
                    .append("<p class=\"dim\">Apaga TODAS as versões do índice (trechos, evidências e vetores). Enquanto não houver novo \"Buscar dados\", ")
                    .append("o indexador automático não reconstrói nada. O CRUD, o site e o GitHub não são tocados.</p>")
                    .append("<h2>2. Buscar dados</h2>")
                    .append("<form method=\"post\" action=\"/admin/reindex\"><button").append(running ? " disabled" : "")
                    .append(">Buscar dados</button></form>")
                    .append("<p class=\"dim\">Lê do zero o snapshot do CRUD, o site, os documentos, o GitHub e os repositórios, monta um índice novo e o ativa, ")
                    .append("apagando as versões antigas. Se a leitura falhar, o índice atual (se houver) continua valendo.</p>");
        }

        JsonNode jobs = status.path("jobs");
        b.append("<h2>Últimas execuções</h2>");
        if (!jobs.isArray() || jobs.isEmpty()) {
            return layout("Fontes", "fontes", b.append("<p class=\"dim\">Nenhuma execução registrada.</p>").toString());
        }
        b.append("<table><tr><th>Início</th><th>Gatilho</th><th>Estado</th><th>Versão</th><th>Resumo</th></tr>");
        for (JsonNode job : jobs) {
            JsonNode r = job.path("relatorio");
            String summary = r.has("motivo") ? r.path("motivo").asString("")
                    : r.path("trechosDoSnapshot").asInt() + " do snapshot, " + r.path("trechosDeFontes").asInt() + " das fontes, "
                    + r.path("trechosDeRepositorios").asInt() + " de repositórios";
            b.append("<tr><td>").append(esc(shortTime(job.path("inicio").asString("")))).append("</td><td>")
                    .append(esc(job.path("gatilho").asString(""))).append("</td><td>").append(esc(job.path("estado").asString("")))
                    .append("</td><td>").append(job.path("versao").isNull() ? "" : esc(job.path("versao").asString("")))
                    .append("</td><td>").append(esc(summary)).append("</td></tr>");
        }
        b.append("</table>");

        JsonNode last = null;
        for (JsonNode job : jobs) {
            if (job.path("relatorio").path("fontes").isArray()) {
                last = job.path("relatorio").path("fontes");
                break;
            }
        }
        if (last != null) {
            b.append("<h2>Resultado da leitura de cada fonte (última indexação)</h2><table><tr><th>Tipo</th><th>Fonte</th><th>Estado</th><th>Trechos</th><th>Aviso</th></tr>");
            for (JsonNode s : last) {
                b.append("<tr><td>").append(esc(s.path("tipo").asString(""))).append("</td><td>").append(esc(s.path("fonte").asString("")))
                        .append("</td><td>").append(esc(s.path("estado").asString(""))).append("</td><td>").append(s.path("trechos").asInt())
                        .append("</td><td>").append(esc(s.path("aviso").asString(""))).append("</td></tr>");
            }
            b.append("</table>");
        }
        return layout("Fontes", "fontes", b.toString());
    }

    /** "2026-10-03T13:45:10.123Z" vira "2026-10-03 13:45:10". */
    private static String shortTime(String iso) {
        int dot = iso.indexOf('.');
        return (dot < 0 ? iso : iso.substring(0, dot)).replace('T', ' ');
    }

    private static String joinArray(JsonNode array) {
        var parts = new ArrayList<String>();
        array.forEach(n -> parts.add(n.asString("")));
        return String.join(", ", parts);
    }

    private static JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return JSON.createObjectNode();
        }
        try {
            return JSON.readTree(json);
        } catch (RuntimeException e) {
            return JSON.createObjectNode();
        }
    }

    private static final String CSS = """
            :root{color-scheme:dark}*{box-sizing:border-box}
            body{margin:0;background:#0a111e;color:#c8d1e0;font:15px/1.55 system-ui,Segoe UI,sans-serif}
            header{display:flex;gap:1.25rem;align-items:center;padding:.75rem 1.25rem;background:#0f1828;border-bottom:1px solid #22304a;position:sticky;top:0}
            header strong{color:#d98a3d}header nav{display:flex;gap:1rem;flex:1}header form{margin:0}
            a{color:#d98a3d}header a{text-decoration:none;color:#a9b4c6}header a.on{color:#f2ede4;border-bottom:2px solid #d98a3d}
            main{max-width:72rem;margin:0 auto;padding:1.25rem}h1{font-size:1.5rem;color:#f2ede4;margin:.5rem 0 1rem}
            h2{font-size:1.1rem;color:#f2ede4;margin:2rem 0 .6rem;border-bottom:1px solid #22304a;padding-bottom:.3rem}
            table{border-collapse:collapse;width:100%;font-size:.9rem}th,td{padding:.4rem .6rem;border-bottom:1px solid #1b2840;text-align:left;vertical-align:top}th{color:#a9b4c6;font-weight:600}
            code,pre{font:13px/1.5 ui-monospace,Consolas,monospace}code{background:#142239;padding:.05rem .3rem;border-radius:4px}
            pre{background:#080e19;border:1px solid #22304a;border-radius:8px;padding:.8rem;overflow:auto;white-space:pre-wrap;word-break:break-word}
            .cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(14rem,1fr));gap:.8rem}.card{background:#0f1828;border:1px solid #22304a;border-radius:10px;padding:.8rem 1rem}
            .card span{display:block;color:#a9b4c6;font-size:.8rem}.card b{display:block;font-size:1.35rem;color:#f2ede4}.card small{color:#7d8aa3}
            .note{background:#142239;border-left:3px solid #7cc49a;padding:.8rem 1rem;border-radius:6px}.warn{color:#d98a3d}.err{color:#e5826b}.dim{color:#7d8aa3}
            .steps li{margin:.35rem 0}.tag{background:#142239;border:1px solid #22304a;border-radius:4px;padding:0 .35rem;font-size:.75rem;color:#7cc49a}
            details.chunk{background:#0f1828;border:1px solid #22304a;border-radius:8px;margin:.5rem 0;padding:.5rem .8rem}summary{cursor:pointer}
            .filters{display:flex;gap:.5rem;flex-wrap:wrap;margin:.5rem 0 1rem}input,select,button{background:#0f1828;color:#f2ede4;border:1px solid #22304a;border-radius:6px;padding:.4rem .6rem;font:inherit}
            button{cursor:pointer;background:#d98a3d;color:#0a111e;border:0;font-weight:600}header button{background:#142239;color:#a9b4c6;font-weight:400;border:1px solid #22304a}
            body.login{display:grid;place-items:center;min-height:100vh}.login form{background:#0f1828;border:1px solid #22304a;border-radius:12px;padding:1.5rem;width:min(22rem,92vw);display:grid;gap:.9rem}
            button.danger{background:#e5826b}label{color:#a9b4c6}
            .login h1{margin:0}.login label{display:grid;gap:.25rem;font-size:.85rem;color:#a9b4c6}.login input{width:100%}ul{padding-left:1.2rem}.pager{margin-top:1rem}
            """;
}

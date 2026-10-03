package br.com.gilvanborges.javai.ai.api.chat;

import br.com.gilvanborges.javai.ai.api.index.ActiveIndex;
import br.com.gilvanborges.javai.ai.api.index.IndexHolder;
import br.com.gilvanborges.javai.ai.api.index.QueryEmbedder;
import br.com.gilvanborges.javai.ai.api.usage.UsageRecorder;
import br.com.gilvanborges.javai.ai.api.web.RateLimiter;
import br.com.gilvanborges.javai.ai.core.chunk.Chunk;
import br.com.gilvanborges.javai.ai.core.search.HybridSearcher;
import br.com.gilvanborges.javai.ai.core.text.LanguageGuess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * O fluxo de uma pergunta (spec 01, "Fluxo de uma pergunta no chat"). Aqui ficam as camadas de defesa em ordem:
 * Turnstile, limites, histórico assinado, classificação, busca, prompt, guarda de saída. Nada daqui depende de HTTP:
 * os eventos saem por um {@link EventSink}, o que deixa a bateria de ataques rodar sem rede.
 */
public class ChatService {

    private static final Logger LOG = LoggerFactory.getLogger(ChatService.class);
    private static final Pattern CITATION = Pattern.compile("\\[(\\d+)]");
    private static final int SOURCES = 5;

    private final ChatProperties props;
    private final Turnstile turnstile;
    private final QuestionClassifier classifier;
    private final ChatModel model;
    private final HistorySigner signer;
    private final Keys keys;
    private final RateLimiter perMinute;
    private final DailyLimiter perDay;
    private final UsageRecorder usage;
    private final IndexHolder index;
    private final QueryEmbedder embedder;
    private final Clock clock;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public ChatService(ChatProperties props, Turnstile turnstile, QuestionClassifier classifier, ChatModel model,
                       HistorySigner signer, Keys keys, UsageRecorder usage, IndexHolder index, QueryEmbedder embedder, Clock clock) {
        this.props = props;
        this.turnstile = turnstile;
        this.classifier = classifier;
        this.model = model;
        this.signer = signer;
        this.keys = keys;
        this.perMinute = new RateLimiter(props.perMinute(), clock);
        this.perDay = new DailyLimiter(props.perDay(), clock);
        this.usage = usage;
        this.index = index;
        this.embedder = embedder;
        this.clock = clock;
    }

    /** As camadas que respondem com um status HTTP, antes de abrir o stream. Lança {@link ChatProblem}. */
    public void admit(ChatRequest request, String ip) {
        if (!props.ready() || index.current().isEmpty()) {
            throw new ChatProblem(503, "/problems/maintenance", "Chat unavailable",
                    "The chat is temporarily unavailable.", 60);
        }
        if (!turnstile.verify(request.turnstileToken(), ip)) {
            throw new ChatProblem(403, "/problems/turnstile", "Verification failed",
                    "The human verification token is missing, invalid, already used or from another site.", 0);
        }
        String who = keys.ipHash(ip, LocalDate.now(clock));
        long wait = perMinute.tryAcquire(who);
        if (wait > 0) {
            throw new ChatProblem(429, "/problems/rate-limited", "Too many requests", "Too many questions per minute.", wait);
        }
        if (!perDay.tryAcquire(who)) {
            throw new ChatProblem(429, "/problems/rate-limited", "Too many requests",
                    "Daily question limit reached.", secondsUntilMidnight());
        }
        if (usage.spentToday().compareTo(props.dailyBudgetUsd()) >= 0) {
            throw new ChatProblem(503, "/problems/budget-exhausted", "Daily budget reached",
                    "The daily budget is exhausted. Try again tomorrow.", secondsUntilMidnight());
        }
    }

    /** Roda a pergunta e manda os eventos para o {@code sink}. Bloqueia até o fim; chame numa thread virtual. */
    public void answer(ChatRequest request, EventSink sink) {
        long started = System.nanoTime();
        String lang = request.lang() != null ? request.lang() : LanguageGuess.of(request.question()).equals("pt") ? "pt" : "en";
        ActiveIndex active = index.current().orElse(null);
        if (active == null) {
            sink.send("error", Map.of("message", Messages.error(lang)));
            return;
        }
        List<ChatRequest.Turn> history = signer.verified(request.history());

        // Em paralelo: o Jev classifica e a busca roda. Nenhum depende do outro.
        CompletableFuture<Timed<Optional<Classification>>> classification = CompletableFuture
                .supplyAsync(() -> {
                    long t = System.nanoTime();
                    return new Timed<>(classifier.classify(request.question(), lang), elapsedMs(t));
                }, workers)
                .completeOnTimeout(new Timed<>(Optional.empty(), props.jevTimeoutMs()), props.jevTimeoutMs(), TimeUnit.MILLISECONDS)
                .exceptionally(e -> new Timed<>(Optional.empty(), 0));
        CompletableFuture<Timed<Found>> search = CompletableFuture.supplyAsync(() -> {
            long t = System.nanoTime();
            float[] vector = active.hasVectors() ? embedder.embed(request.question(), active.embeddingModel()).orElse(null) : null;
            return new Timed<>(new Found(active.search(request.question(), vector, lang, SOURCES), vector != null), elapsedMs(t));
        }, workers);

        Timed<Optional<Classification>> classified = classification.join();
        Router.Decision decision = Router.decide(classified.value());
        var classifyTrace = new LinkedHashMap<String, Object>();
        classifyTrace.put("step", "classify");
        classifyTrace.put("ok", classified.value().isPresent());
        classifyTrace.put("category", decision.category() == null ? null : decision.category().key());
        classifyTrace.put("confidence", decision.confidence());
        classifyTrace.put("ms", classified.ms());
        sink.send("trace", classifyTrace);

        if (decision.action() == Router.Action.REFUSE) {
            usage.refusal();
            sink.send("refusal", Map.of("reason", decision.refusalReason(), "text", Messages.refusal(lang, decision.refusalReason())));
            return;
        }

        Timed<Found> found = search.join();
        var searchTrace = new LinkedHashMap<String, Object>();
        searchTrace.put("step", "search");
        searchTrace.put("mode", found.value().vector() ? "hybrid" : "bm25");
        searchTrace.put("hits", found.value().hits().size());
        searchTrace.put("model", found.value().vector() ? active.embeddingModel() : null);
        searchTrace.put("ms", found.ms());
        sink.send("trace", searchTrace);

        List<Chunk> sources = found.value().hits().stream().map(HybridSearcher.Hit::chunk).toList();
        String canary = keys.canary(PromptBuilder.VERSION);
        PromptBuilder.Prompt prompt = PromptBuilder.build(request.question(), lang, sources, history, canary);
        List<String> models = decision.tier() == Router.Tier.FORTE ? props.modelsStrong() : props.modelsFast();
        String turnId = UUID.randomUUID().toString();
        sink.send("trace", Map.of("step", "route", "tier", decision.tier() == Router.Tier.FORTE ? "forte" : "rapido", "models", models));

        var meta = new LinkedHashMap<String, Object>();
        meta.put("turnId", turnId);
        meta.put("category", decision.category() == null ? "desconhecida" : decision.category().key());
        meta.put("confidence", decision.confidence());
        meta.put("tier", decision.tier() == Router.Tier.FORTE ? "forte" : "rapido");
        meta.put("model", models.get(0));
        if (!sink.send("meta", meta)) {
            return;
        }

        var guard = new OutputGuard(canary, sources.size());
        boolean[] gone = {false};
        ChatModel.Usage used;
        try {
            used = model.stream(prompt, models, delta -> {
                String safe = guard.feed(delta);
                if (safe == null) {
                    return false;
                }
                if (!safe.isEmpty() && !sink.send("delta", Map.of("text", safe))) {
                    gone[0] = true;
                    return false;
                }
                return true;
            });
        } catch (IOException e) {
            LOG.warn("Falha ao gerar a resposta ({}).", e.getClass().getSimpleName());
            sink.send("error", Map.of("message", Messages.error(lang)));
            return;
        }
        int latency = elapsedMs(started);
        if (gone[0]) {
            usage.answer(used.model(), used.tokensIn(), used.tokensOut(), used.costUsd(), latency, false);
            return;
        }

        String tail = guard.finish();
        if (tail == null) {
            usage.answer(used.model(), used.tokensIn(), used.tokensOut(), used.costUsd(), latency, true);
            sink.send("retract", Map.of("text", Messages.retract(lang)));
            return;
        }
        if (!tail.isEmpty()) {
            sink.send("delta", Map.of("text", tail));
        }
        String text = guard.fullText().strip();
        if (text.isEmpty()) {
            sink.send("error", Map.of("message", Messages.error(lang)));
            return;
        }

        sink.send("trace", Map.of("step", "guard", "ok", true, "checks", List.of("url", "html", "image", "canary", "citations")));
        sink.send("sources", citedSources(text, sources));
        ChatRequest.Turn turn = signer.sign(turnId, request.question(), text);
        var usageOut = new LinkedHashMap<String, Object>();
        usageOut.put("costUsd", used.costUsd());
        usageOut.put("latencyMs", latency);
        usageOut.put("tokensIn", used.tokensIn());
        usageOut.put("tokensOut", used.tokensOut());
        usageOut.put("model", used.model());
        usage.answer(used.model(), used.tokensIn(), used.tokensOut(), used.costUsd(), latency, false);
        sink.send("done", Map.of("turn", turn, "usage", usageOut));
    }

    /** As fontes que o texto cita com {@code [n]}, com o link escrito pelo servidor (o modelo nunca escreve URL). */
    static List<Map<String, Object>> citedSources(String text, List<Chunk> sources) {
        var cited = new TreeSet<Integer>();
        Matcher m = CITATION.matcher(text);
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            if (n >= 1 && n <= sources.size()) {
                cited.add(n);
            }
        }
        var out = new ArrayList<Map<String, Object>>();
        for (int n : cited) {
            Chunk c = sources.get(n - 1);
            var s = new LinkedHashMap<String, Object>();
            s.put("ref", n);
            s.put("title", c.title());
            s.put("url", c.url());
            out.add(s);
        }
        return out;
    }

    private record Timed<T>(T value, int ms) {
    }

    private record Found(List<HybridSearcher.Hit> hits, boolean vector) {
    }

    private long secondsUntilMidnight() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        return Math.max(1, Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(now.getZone())).getSeconds());
    }

    private static int elapsedMs(long startedNanos) {
        return (int) ((System.nanoTime() - startedNanos) / 1_000_000);
    }
}

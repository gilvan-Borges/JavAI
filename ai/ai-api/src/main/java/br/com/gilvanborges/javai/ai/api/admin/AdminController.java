package br.com.gilvanborges.javai.ai.api.admin;

import br.com.gilvanborges.javai.ai.api.chat.ChatProperties;
import br.com.gilvanborges.javai.ai.api.chat.ChatService;
import br.com.gilvanborges.javai.ai.api.chat.Keys;
import br.com.gilvanborges.javai.ai.api.index.ActiveIndex;
import br.com.gilvanborges.javai.ai.api.index.IndexHolder;
import br.com.gilvanborges.javai.ai.api.index.QueryEmbedder;
import br.com.gilvanborges.javai.ai.api.usage.UsageRecorder;
import br.com.gilvanborges.javai.ai.api.usage.UsageStore;
import br.com.gilvanborges.javai.ai.api.web.ClientIp;
import br.com.gilvanborges.javai.ai.core.search.HybridSearcher;
import br.com.gilvanborges.javai.ai.api.text.TextCleaner;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * As rotas do {@code /admin}. A autenticação e os cabeçalhos de segurança ficam no {@link AdminFilter}; aqui só há
 * leitura (e o login/logout). Nenhuma rota altera dado do índice, do CRUD ou da configuração.
 */
@RestController
public class AdminController {

    private static final MediaType HTML = new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8);

    private final AdminProperties props;
    private final AdminSession session;
    private final LoginThrottle throttle;
    private final Keys keys;
    private final IndexHolder index;
    private final QueryEmbedder embedder;
    private final ChatProperties chat;
    private final ChatService chatService;
    private final UsageStore usage;
    private final UsageRecorder recorder;
    private final Clock clock;

    public AdminController(AdminProperties props, AdminSession session, LoginThrottle throttle, Keys keys, IndexHolder index,
                           QueryEmbedder embedder, ChatProperties chat, ChatService chatService, UsageStore usage,
                           UsageRecorder recorder, Clock clock) {
        this.props = props;
        this.session = session;
        this.throttle = throttle;
        this.keys = keys;
        this.index = index;
        this.embedder = embedder;
        this.chat = chat;
        this.chatService = chatService;
        this.usage = usage;
        this.recorder = recorder;
        this.clock = clock;
    }

    @GetMapping("/admin/login")
    public ResponseEntity<String> loginForm() {
        return html(AdminPages.loginPage(null));
    }

    @PostMapping("/admin/login")
    public ResponseEntity<String> login(HttpServletRequest request, HttpServletResponse response,
                                        @RequestParam(name = "username", defaultValue = "") String username,
                                        @RequestParam(name = "password", defaultValue = "") String password) {
        // Formulário de outro site não entra: o navegador diz de onde veio o pedido.
        String site = request.getHeader("Sec-Fetch-Site");
        if (site != null && !site.equals("same-origin") && !site.equals("none")) {
            return ResponseEntity.status(403).contentType(HTML).body(AdminPages.loginPage("Pedido de outro site recusado."));
        }
        String who = keys.stableIpHash("admin|" + ClientIp.of(request));
        Optional<Duration> locked = throttle.lockedFor(who);
        if (locked.isPresent()) {
            return ResponseEntity.status(429).header("Retry-After", Long.toString(Math.max(1, locked.get().toSeconds()))).contentType(HTML)
                    .body(AdminPages.loginPage("Muitas tentativas. Tente de novo em " + Math.max(1, locked.get().toMinutes()) + " min."));
        }
        if (!session.credentialsMatch(username, password)) {
            throttle.recordFailure(who);
            return ResponseEntity.status(401).contentType(HTML).body(AdminPages.loginPage("Usuário ou senha inválidos."));
        }
        throttle.reset(who);
        response.addHeader("Set-Cookie", AdminSession.COOKIE + "=" + session.issue() + "; Max-Age=" + session.validity().toSeconds()
                + "; Path=/admin; HttpOnly; Secure; SameSite=Strict");
        return ResponseEntity.status(303).header("Location", "/admin").build();
    }

    @PostMapping("/admin/logout")
    public ResponseEntity<String> logout(HttpServletResponse response) {
        response.addHeader("Set-Cookie", AdminSession.COOKIE + "=; Max-Age=0; Path=/admin; HttpOnly; Secure; SameSite=Strict");
        return ResponseEntity.status(303).header("Location", "/admin/login").build();
    }

    @GetMapping("/admin")
    public ResponseEntity<String> overview() {
        List<UsageStore.Day> rows;
        try {
            rows = usage.since(LocalDate.now(clock).minusDays(29));
        } catch (RuntimeException e) {
            rows = List.of();
        }
        var overview = new AdminPages.Overview(index.current().orElse(null), index.meta().orElse(null), chat, rows,
                recorder.spentToday(), chatService.blockedVisitors());
        return html(AdminPages.overview(overview));
    }

    @GetMapping("/admin/trechos")
    public ResponseEntity<String> chunks(@RequestParam(name = "kind", defaultValue = "") String kind,
                                         @RequestParam(name = "lang", defaultValue = "") String lang,
                                         @RequestParam(name = "repo", defaultValue = "") String repo,
                                         @RequestParam(name = "page", defaultValue = "0") int page) {
        return html(AdminPages.chunks(index.current().orElse(null), clip(kind, 20), lang.equals("pt") || lang.equals("en") ? lang : "", clip(repo, 140), page));
    }

    @GetMapping("/admin/busca")
    public ResponseEntity<String> search(@RequestParam(name = "q", defaultValue = "") String q,
                                         @RequestParam(name = "lang", defaultValue = "pt") String lang) {
        String query = TextCleaner.clean(q, false);
        String language = lang.equals("en") ? "en" : "pt";
        ActiveIndex active = index.current().orElse(null);
        if (active == null || query.length() < 2) {
            return html(AdminPages.search(query.length() < 2 ? "" : query, language, List.of(), false));
        }
        float[] vector = active.hasVectors() ? embedder.embed(query, active.embeddingModel()).orElse(null) : null;
        List<HybridSearcher.Hit> hits = active.search(query, vector, language, 8);
        return html(AdminPages.search(query, language, hits, vector != null));
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static ResponseEntity<String> html(String body) {
        return ResponseEntity.ok().contentType(HTML).body(body);
    }
}

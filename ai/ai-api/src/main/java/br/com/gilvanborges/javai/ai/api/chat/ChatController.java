package br.com.gilvanborges.javai.ai.api.chat;

import br.com.gilvanborges.javai.ai.api.web.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * {@code POST /v1/chat} (spec 03). O corpo é lido à mão, em bytes, para a leitura ser estrita (campo desconhecido é
 * 400) e limitada a 16 KB. Os erros anteriores ao stream saem como Problem Details; depois que o stream abre, tudo
 * vira evento SSE.
 */
@RestController
public class ChatController {

    private static final Logger LOG = LoggerFactory.getLogger(ChatController.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Tempo máximo do stream. O prazo do modelo ({@link OpenRouterClient#TOTAL_BUDGET}) cabe dentro dele com folga. */
    static final long STREAM_TIMEOUT_MS = 90_000L;
    /**
     * De quanto em quanto tempo sai um comentário SSE enquanto o modelo pensa. Sem tráfego, a Cloudflare e os proxies
     * derrubam a conexão parada (~100 s), e o visitante veria a resposta cortada sem aviso.
     */
    static final long HEARTBEAT_SECONDS = 15;
    private static final ScheduledExecutorService HEARTBEATS = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    private final ChatService service;

    public ChatController(ChatService service) {
        this.service = service;
    }

    @PostMapping("/v1/chat")
    public SseEmitter chat(HttpServletRequest http, HttpServletResponse response) throws IOException {
        String contentType = http.getContentType();
        if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
            throw new ChatProblem(415, "/problems/unsupported-media-type", "Unsupported media type",
                    "The body must be application/json.", 0);
        }
        ChatRequest request;
        try {
            request = ChatRequest.parse(http.getInputStream().readNBytes(ChatRequest.MAX_BODY_BYTES + 1));
        } catch (ChatRequest.Invalid e) {
            throw new ChatProblem(400, "/problems/validation", "Invalid request", e.getMessage(), 0);
        }
        service.admit(request, ClientIp.of(http));

        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        var emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        // Estourou o tempo: o visitante recebe um erro explícito em vez de um stream que simplesmente acaba, o que o
        // terminal mostraria como resposta completa.
        emitter.onTimeout(() -> sendQuietly(emitter, "error", Map.of("message", Messages.error(request.lang()))));
        String ip = ClientIp.of(http);
        Thread.startVirtualThread(() -> run(request, ip, emitter));
        return emitter;
    }

    private static void sendQuietly(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(JSON.writeValueAsString(data)));
        } catch (IOException | IllegalStateException e) {
            // o visitante já saiu ou o stream já fechou
        }
    }

    private void run(ChatRequest request, String ip, SseEmitter emitter) {
        EventSink sink = (event, data) -> {
            try {
                emitter.send(SseEmitter.event().name(event).data(JSON.writeValueAsString(data)));
                return true;
            } catch (IOException | IllegalStateException e) {
                return false; // o visitante fechou a conexão
            }
        };
        var heartbeat = HEARTBEATS.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("hb"));
            } catch (IOException | IllegalStateException e) {
                // o visitante fechou a conexão; o fim do run cancela o agendamento
            }
        }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        try {
            service.answer(request, ip, sink);
        } catch (RuntimeException e) {
            LOG.warn("Erro inesperado no chat ({}).", e.getClass().getSimpleName());
            sink.send("error", Map.of("message", Messages.error(request.lang())));
        } finally {
            heartbeat.cancel(false);
            emitter.complete();
        }
    }
}

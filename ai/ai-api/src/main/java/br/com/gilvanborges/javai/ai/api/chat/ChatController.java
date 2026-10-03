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

/**
 * {@code POST /v1/chat} (spec 03). O corpo é lido à mão, em bytes, para a leitura ser estrita (campo desconhecido é
 * 400) e limitada a 16 KB. Os erros anteriores ao stream saem como Problem Details; depois que o stream abre, tudo
 * vira evento SSE.
 */
@RestController
public class ChatController {

    private static final Logger LOG = LoggerFactory.getLogger(ChatController.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

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
        var emitter = new SseEmitter(90_000L);
        String ip = ClientIp.of(http);
        Thread.startVirtualThread(() -> run(request, ip, emitter));
        return emitter;
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
        try {
            service.answer(request, ip, sink);
        } catch (RuntimeException e) {
            LOG.warn("Erro inesperado no chat ({}).", e.getClass().getSimpleName());
            sink.send("error", java.util.Map.of("message", "Something went wrong. Please try again shortly."));
        } finally {
            emitter.complete();
        }
    }
}

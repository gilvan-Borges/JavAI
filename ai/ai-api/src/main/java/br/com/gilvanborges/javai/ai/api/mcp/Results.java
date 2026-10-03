package br.com.gilvanborges.javai.ai.api.mcp;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import java.util.Map;

/** Resultados de ferramenta: texto legível e {@code structuredContent}, ou um erro que o modelo consegue ler e corrigir. */
final class Results {

    private Results() {
    }

    static CallToolResult ok(String text, Map<String, Object> structured) {
        return CallToolResult.builder().addTextContent(text).structuredContent(structured).isError(false).build();
    }

    /** Erro da ferramenta (entrada inválida, item inexistente): volta como resultado, não como falha do protocolo. */
    static CallToolResult error(String message) {
        return CallToolResult.builder().addTextContent(message).isError(true).build();
    }
}

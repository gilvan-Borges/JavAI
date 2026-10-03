package br.com.gilvanborges.javai.ai.api.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Construtores mínimos de JSON Schema para declarar a entrada e a saída (outputSchema) das ferramentas. */
final class Schemas {

    private Schemas() {
    }

    static Map<String, Object> object(Map<String, ?> properties, List<String> required) {
        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        // Campo desconhecido é erro, não é ignorado (spec 03, "Convenções").
        schema.put("additionalProperties", false);
        return schema;
    }

    static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    static Map<String, Object> string(String description, List<String> allowed) {
        return Map.of("type", "string", "description", description, "enum", allowed);
    }

    static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "description", description);
    }

    static Map<String, Object> bool(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    static Map<String, Object> array(Map<String, Object> items) {
        return Map.of("type", "array", "items", items);
    }
}

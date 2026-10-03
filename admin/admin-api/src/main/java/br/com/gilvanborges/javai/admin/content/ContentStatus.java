package br.com.gilvanborges.javai.admin.content;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum ContentStatus {
    @JsonProperty("draft") DRAFT("draft"),
    @JsonProperty("published") PUBLISHED("published");

    private final String db;

    ContentStatus(String db) {
        this.db = db;
    }

    public String db() {
        return db;
    }

    public static ContentStatus fromDb(String value) {
        for (ContentStatus status : values()) {
            if (status.db.equals(value)) {
                return status;
            }
        }
        throw new IllegalStateException("Status desconhecido no banco.");
    }
}

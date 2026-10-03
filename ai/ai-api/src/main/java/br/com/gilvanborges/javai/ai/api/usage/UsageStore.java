package br.com.gilvanborges.javai.ai.api.usage;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Contadores agregados do dia (spec 02, {@code ops.usage_daily}). Só números: nenhuma pergunta, resposta ou IP é
 * gravado (LGPD). A implementação de produção grava com o papel {@code ai_reader}.
 */
public interface UsageStore {

    /** Um dia de um modelo. Os campos de latência são os do dia até agora. */
    record Day(LocalDate day, String model, int requests, int refusals, int retracts, long tokensIn, long tokensOut,
               BigDecimal costUsd, Integer latencyP50, Integer latencyP90, int mcpCalls) {
    }

    BigDecimal spentOn(LocalDate day);

    /** Soma os valores nos do dia e do modelo (cria a linha se não existir); a latência substitui. */
    void add(Day delta);

    /** As linhas de {@code from} em diante. */
    List<Day> since(LocalDate from);
}

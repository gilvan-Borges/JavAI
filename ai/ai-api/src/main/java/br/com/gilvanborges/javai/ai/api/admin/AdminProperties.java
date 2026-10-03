package br.com.gilvanborges.javai.ai.api.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Página de administração da IA ({@code /admin}). Só existe se o usuário e uma senha de pelo menos 16 caracteres
 * estiverem no ambiente; sem isso a rota responde 404, como se não existisse. A página só LÊ (nenhuma ação altera
 * nada) e mostra o que a IA enxerga e como o RAG está sendo usado.
 *
 * @param user         usuário fixo
 * @param password     senha fixa (vem do {@code .env} da VPS, nunca do código)
 * @param sessionHours validade da sessão
 * @param maxFailures  tentativas de login erradas antes do bloqueio daquele IP
 * @param lockMinutes  duração do bloqueio de login (e janela de contagem das tentativas)
 */
@ConfigurationProperties("aiapi.admin")
public record AdminProperties(
        @DefaultValue("") String user,
        @DefaultValue("") String password,
        @DefaultValue("8") int sessionHours,
        @DefaultValue("5") int maxFailures,
        @DefaultValue("15") int lockMinutes) {

    public static final int MIN_PASSWORD = 16;

    public boolean enabled() {
        return user.length() >= 3 && password.length() >= MIN_PASSWORD;
    }
}

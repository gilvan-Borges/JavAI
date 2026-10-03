package br.com.gilvanborges.javai.ai.api.admin;

import br.com.gilvanborges.javai.ai.api.chat.Keys;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Sessão do admin num cookie assinado e sem estado no servidor: {@code <expira-em>.<HMAC>}. A chave mistura o segredo
 * do serviço com a senha do admin, então trocar a senha derruba todas as sessões. A conferência de usuário e senha
 * é em tempo constante (compara HMACs, nunca os textos).
 */
public final class AdminSession {

    public static final String COOKIE = "javai_admin";

    private final byte[] key;
    private final String user;
    private final String password;
    private final Duration validity;
    private final Clock clock;

    public AdminSession(Keys keys, AdminProperties props, Clock clock) {
        this.key = Keys.hmac(keys.derive("admin-session"), props.password().getBytes(StandardCharsets.UTF_8));
        this.user = props.user();
        this.password = props.password();
        this.validity = Duration.ofHours(props.sessionHours());
        this.clock = clock;
    }

    public Duration validity() {
        return validity;
    }

    /** Confere as duas credenciais sem parar na primeira que falhar. */
    public boolean credentialsMatch(String givenUser, String givenPassword) {
        boolean userOk = equalMac(givenUser == null ? "" : givenUser, user);
        boolean passwordOk = equalMac(givenPassword == null ? "" : givenPassword, password);
        return userOk & passwordOk;
    }

    public String issue() {
        long expires = clock.instant().plus(validity).getEpochSecond();
        return expires + "." + mac(Long.toString(expires));
    }

    public boolean valid(String token) {
        if (token == null || token.length() > 200) {
            return false;
        }
        int dot = token.indexOf('.');
        if (dot <= 0) {
            return false;
        }
        try {
            long expires = Long.parseLong(token.substring(0, dot));
            if (Instant.ofEpochSecond(expires).isBefore(clock.instant())) {
                return false;
            }
            return Keys.constantTimeEquals(mac(token.substring(0, dot)).getBytes(StandardCharsets.UTF_8),
                    token.substring(dot + 1).getBytes(StandardCharsets.UTF_8));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String mac(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Keys.hmac(key, (user + "|" + value).getBytes(StandardCharsets.UTF_8)));
    }

    private boolean equalMac(String a, String b) {
        return Keys.constantTimeEquals(Keys.hmac(key, a.getBytes(StandardCharsets.UTF_8)), Keys.hmac(key, b.getBytes(StandardCharsets.UTF_8)));
    }
}

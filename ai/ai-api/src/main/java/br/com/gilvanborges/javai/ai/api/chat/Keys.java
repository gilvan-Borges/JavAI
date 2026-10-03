package br.com.gilvanborges.javai.ai.api.chat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.Base64;

/**
 * Chaves derivadas por HKDF-SHA256 (RFC 5869) de um segredo-mestre. Cada uso tem o seu rótulo, então a chave das
 * assinaturas do histórico, a do hash de IP e o canário não se misturam, e a chave do dia muda todo dia: não dá para
 * reverter o hash de um IP nem ligar o mesmo IP entre dias (spec 01, "Custos e limites").
 */
public final class Keys {

    private static final String HMAC = "HmacSHA256";
    private final byte[] prk;

    /** @param master o segredo-mestre; vazio gera um aleatório (as assinaturas deixam de valer ao reiniciar). */
    public Keys(String master) {
        byte[] ikm;
        if (master == null || master.isBlank()) {
            ikm = new byte[32];
            new SecureRandom().nextBytes(ikm);
        } else {
            ikm = master.getBytes(StandardCharsets.UTF_8);
        }
        this.prk = hmac("javai-hkdf-salt".getBytes(StandardCharsets.UTF_8), ikm);
    }

    /** HKDF-Expand para 32 bytes (um bloco do HMAC-SHA256). */
    public byte[] derive(String label) {
        byte[] info = label.getBytes(StandardCharsets.UTF_8);
        byte[] input = new byte[info.length + 1];
        System.arraycopy(info, 0, input, 0, info.length);
        input[info.length] = 1;
        return hmac(prk, input);
    }

    /** O hash de um IP com a chave do dia. É só isso que fica em memória: o IP em si nunca é guardado. */
    public String ipHash(String ip, LocalDate day) {
        byte[] mac = hmac(derive("ip-" + day), ip.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac);
    }

    /** O canário desta versão do prompt: texto sem sentido que o modelo nunca deve escrever. */
    public String canary(String promptVersion) {
        byte[] mac = hmac(derive("canary-" + promptVersion), new byte[0]);
        return "CNRY-" + Base64.getUrlEncoder().withoutPadding().encodeToString(mac).substring(0, 12);
    }

    public static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 sempre existe no JDK.", e);
        }
    }

    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }
}

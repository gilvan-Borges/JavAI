package br.com.gilvanborges.javai.admin.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;

/** Tokens no formato do Cloudflare Access, assinados por uma chave gerada só para os testes. */
public final class TestTokens {

    public static final String HEADER = "Cf-Access-Jwt-Assertion";
    public static final String ISSUER = "https://teste.cloudflareaccess.com";
    public static final String AUDIENCE = "aud-de-teste";
    public static final String OWNER = "gilvan@example.com";

    /** As propriedades {@code admin.access.*} que combinam com os tokens daqui. */
    public static final String[] PROPERTIES = {
            "admin.access.jwks-uri=" + ISSUER + "/cdn-cgi/access/certs",
            "admin.access.issuer=" + ISSUER,
            "admin.access.audience=" + AUDIENCE,
            "admin.access.owner-email=" + OWNER,
    };

    private static final RSAKey KEY = newKey();
    private static final RSAKey OTHER_KEY = newKey();

    private TestTokens() {
    }

    public static String owner() {
        return token(KEY, OWNER, AUDIENCE, ISSUER, Instant.now().plusSeconds(300));
    }

    public static String withEmail(String email) {
        return token(KEY, email, AUDIENCE, ISSUER, Instant.now().plusSeconds(300));
    }

    public static String withAudience(String audience) {
        return token(KEY, OWNER, audience, ISSUER, Instant.now().plusSeconds(300));
    }

    public static String withIssuer(String issuer) {
        return token(KEY, OWNER, AUDIENCE, issuer, Instant.now().plusSeconds(300));
    }

    public static String expired() {
        return token(KEY, OWNER, AUDIENCE, ISSUER, Instant.now().minusSeconds(600));
    }

    public static String signedByAnotherKey() {
        return token(OTHER_KEY, OWNER, AUDIENCE, ISSUER, Instant.now().plusSeconds(300));
    }

    public static RSAPublicKey publicKey() {
        try {
            return KEY.toRSAPublicKey();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String token(RSAKey key, String email, String audience, String issuer, Instant expiresAt) {
        var claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .subject("usuario-do-access")
                .claim("email", email)
                .issueTime(Date.from(expiresAt.minusSeconds(900)))
                .expirationTime(Date.from(expiresAt))
                .build();
        var header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).type(JOSEObjectType.JWT).build();
        var jwt = new SignedJWT(header, claims);
        try {
            jwt.sign(new RSASSASigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }

    private static RSAKey newKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("chave-de-teste-" + System.nanoTime()).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}

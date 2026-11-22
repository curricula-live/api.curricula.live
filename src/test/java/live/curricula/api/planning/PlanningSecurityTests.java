package live.curricula.api.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "CURRICULA_PLANNING_ENABLED=true")
@DirtiesContext
class PlanningSecurityTests {
    private static final String ISSUER = "https://identity.example.test";
    private static final String AUDIENCE = "curricula-planning-test";
    private static final String SCOPE = "curricula.planning";
    @TempDir static Path store;

    @DynamicPropertySource static void rdfStore(DynamicPropertyRegistry config) {
        config.add("CURRICULA_RDF_STORE", () -> store.toString());
    }

    @LocalServerPort int port;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean PlanningStore plans;

    @Test void requiresSignedResourceTokensAndDerivesTheOwnerFromVerifiedClaims() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair verifiedKeys = generator.generateKeyPair();
        KeyPair untrustedKeys = generator.generateKeyPair();
        var signed = NimbusJwtDecoder.withPublicKey((RSAPublicKey) verifiedKeys.getPublic()).build();
        signed.setJwtValidator(PlanningSecurity.validator(ISSUER, AUDIENCE));
        when(decoder.decode(anyString())).thenAnswer(call -> signed.decode(call.getArgument(0)));
        when(plans.list(new PlanningStore.Account(ISSUER, "teacher-a"), 0))
                .thenReturn(List.of(new PlanningStore.Plan(java.util.UUID.randomUUID(), "Owner's plan",
                        "https://curricula.live/id/formal-sciences/concept/dpll-algorithm", 1)));

        assertEquals(401, get(null).statusCode());
        assertEquals(401, get(token(verifiedKeys, ISSUER, "oidc-browser-client", SCOPE)).statusCode());
        assertEquals(401, get(token(verifiedKeys, ISSUER + "/other", AUDIENCE, SCOPE)).statusCode());
        assertEquals(401, get(token(untrustedKeys, ISSUER, AUDIENCE, SCOPE)).statusCode());
        assertEquals(403, get(token(verifiedKeys, ISSUER, AUDIENCE, "openid")).statusCode());
        var allowed = get(token(verifiedKeys, ISSUER, AUDIENCE, SCOPE));
        assertEquals(200, allowed.statusCode());
        org.junit.jupiter.api.Assertions.assertTrue(allowed.body().contains("Owner's plan"));
    }

    private HttpResponse<String> get(String bearer) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/planning/plans"));
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        try (var http = HttpClient.newHttpClient()) {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static String token(KeyPair keys, String issuer, String audience, String scope) throws Exception {
        long now = Instant.now().getEpochSecond();
        var json = JsonMapper.builder().build();
        var encoding = Base64.getUrlEncoder().withoutPadding();
        String unsigned = encoding.encodeToString(json.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT")))
                + "." + encoding.encodeToString(json.writeValueAsBytes(Map.of(
                        "iss", issuer, "aud", List.of(audience), "sub", "teacher-a", "scope", scope,
                        "iat", now, "exp", now + 120)));
        var signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(keys.getPrivate());
        signature.update(unsigned.getBytes(StandardCharsets.US_ASCII));
        return unsigned + "." + encoding.encodeToString(signature.sign());
    }
}

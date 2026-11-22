package live.curricula.api.planning;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.postgresql.ds.PGSimpleDataSource;
import live.curricula.api.rdf.RdfOntologyRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class PlanningSecurity {
    private static final String PLANNING_PATH = "/api/v1/planning/**";

    @Bean
    @Order(2)
    SecurityFilterChain publicReadOnly(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth -> auth.requestMatchers(PLANNING_PATH).denyAll().anyRequest().permitAll())
                .logout(logout -> logout.disable()).build();
    }

    @Configuration
    @ConditionalOnProperty(name = "CURRICULA_PLANNING_ENABLED", havingValue = "true")
    static class Enabled {
        @Bean
        @Order(1)
        SecurityFilterChain planningRequests(HttpSecurity http, JwtDecoder decoder) throws Exception {
            return http.securityMatcher(PLANNING_PATH)
                    .authorizeHttpRequests(auth -> auth.anyRequest().hasAuthority("SCOPE_curricula.planning"))
                    .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(decoder)))
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .csrf(csrf -> csrf.disable()).build();
        }

        @Bean
        JwtDecoder planningDecoder(Environment environment) {
            String issuer = https(environment.getRequiredProperty("CURRICULA_PLANNING_ISSUER"));
            String audience = environment.getRequiredProperty("CURRICULA_PLANNING_AUDIENCE");
            String configuredKeys = environment.getProperty("CURRICULA_PLANNING_JWK_SET_URI");
            if (audience.isBlank()) throw new IllegalArgumentException("Planning resource audience is required");
            NimbusJwtDecoder decoder = configuredKeys == null
                    ? NimbusJwtDecoder.withIssuerLocation(issuer).build()
                    : NimbusJwtDecoder.withJwkSetUri(https(configuredKeys)).build();
            decoder.setJwtValidator(validator(issuer, audience));
            return decoder;
        }

        @Bean
        PlanningStore planningStore(Environment environment, RdfOntologyRegistry registry) throws java.io.IOException {
            String url = environment.getRequiredProperty("CURRICULA_PLANNING_DB_URL");
            if (!url.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException("Expected a PostgreSQL JDBC URL");
            var source = new PGSimpleDataSource();
            source.setURL(url);
            source.setUser(environment.getRequiredProperty("CURRICULA_PLANNING_DB_USER"));
            Path secret = Path.of(environment.getRequiredProperty("CURRICULA_PLANNING_DB_PASSWORD_FILE"));
            String password = Files.readString(secret).stripTrailing();
            if (password.isBlank()) throw new IllegalArgumentException("Planning database password file is empty");
            source.setPassword(password);
            source.setConnectTimeout(3);
            source.setSocketTimeout(8);
            return new PlanningStore(source, registry);
        }
    }

    static OAuth2TokenValidator<Jwt> validator(String issuer, String audience) {
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), token -> {
            if (token.getAudience() != null && token.getAudience().contains(audience) && token.getExpiresAt() != null) {
                try {
                    new PlanningStore.Account(issuer, token.getSubject());
                    return OAuth2TokenValidatorResult.success();
                } catch (IllegalArgumentException invalidSubject) {
                    // Invalid opaque identity claim.
                }
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid planning token claims", null));
        });
    }

    private static String https(String value) {
        URI uri = URI.create(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getFragment() != null || uri.getQuery() != null) {
            throw new IllegalArgumentException("Expected an HTTPS identity provider URI");
        }
        return value;
    }
}

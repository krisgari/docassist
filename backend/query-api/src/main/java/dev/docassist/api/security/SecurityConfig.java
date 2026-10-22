package dev.docassist.api.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Stateless bearer-token auth. The demo signs its own HS256 tokens; in a customer deployment, delete the demo
 * login and point the resource server at the IdP instead:
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri=https://login.microsoftonline.com/<tenant>/v2.0}.
 * Tenant and user identity always come from the verified token, never from the request body or the prompt.
 */
@Configuration
public class SecurityConfig {

    private final SecretKey key;

    public SecurityConfig(@Value("${docassist.auth.jwt-secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("DOCASSIST_JWT_SECRET must be at least 32 bytes");
        }
        this.key = new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                    // SSE responses complete on an ASYNC dispatch after the original request was authenticated.
                    .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                    .requestMatchers("/auth/**", "/actuator/health/**", "/actuator/health", "/actuator/prometheus").permitAll()
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(j -> j.decoder(jwtDecoder())));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }
}

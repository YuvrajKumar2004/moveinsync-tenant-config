package com.moveinsync.config.server.security;

import com.moveinsync.config.server.error.ProblemWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

/**
 * Stateless bearer-token security. Coarse role checks live here; tenant checks (which need the request
 * body or path) live in {@link AccessPolicy}.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, TokenService tokens, ProblemWriter problems) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/token").permitAll()
                        // only liveness/readiness are anonymous; metrics reveal tenant activity and need a token
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**").hasRole("OPERATOR")
                        .requestMatchers("/api/v1/sync/**").hasAnyRole("SERVICE", "OPERATOR")
                        .requestMatchers("/api/v1/admin/**").hasRole("OPERATOR")
                        .requestMatchers(HttpMethod.GET, "/api/v1/resolve", "/api/v1/definitions/**").authenticated()
                        .requestMatchers("/api/v1/**").hasAnyRole("OPERATOR", "ADMIN")
                        .requestMatchers("/error").permitAll()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.decoder(tokens.decoder()).jwtAuthenticationConverter(SecurityConfig::convert))
                        .authenticationEntryPoint((req, res, e) ->
                                problems.write(res, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "a valid bearer token is required"))
                        .accessDeniedHandler((req, res, e) ->
                                problems.write(res, HttpStatus.FORBIDDEN, "FORBIDDEN", "not allowed for this role")))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) ->
                                problems.write(res, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "a valid bearer token is required"))
                        .accessDeniedHandler((req, res, ex) ->
                                problems.write(res, HttpStatus.FORBIDDEN, "FORBIDDEN", "not allowed for this role")));
        return http.build();
    }

    private static AbstractAuthenticationToken convert(org.springframework.security.oauth2.jwt.Jwt jwt) {
        AuthUser user = TokenService.toUser(jwt);
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())), user.subject());
    }
}

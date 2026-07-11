package org.catalogueoflife.editor.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration
public class SecurityConfig {

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  SecurityFilterChain filterChain(HttpSecurity http,
                                  org.catalogueoflife.editor.user.OrcidUserService orcidUserService) throws Exception {
    http
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/ping").permitAll()
            .requestMatchers("/api/auth/login", "/login/**", "/oauth2/**").permitAll()
            // Public reference-PDF download (name/PdfController) -- hosted PDFs must be citable
            // without auth, same as GET /pdf/{filename} itself is a public read, unlike the
            // authenticated POST/DELETE .../references/{id}/pdf under /api/**.
            .requestMatchers("/pdf/**").permitAll()
            .anyRequest().authenticated())
        .csrf(csrf -> csrf
            .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
            .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
        .addFilterAfter(new CsrfCookieFilter(),
            org.springframework.security.web.authentication.www.BasicAuthenticationFilter.class)
        .formLogin(form -> form
            .loginProcessingUrl("/api/auth/login")
            .successHandler((req, res, a) -> res.setStatus(HttpStatus.OK.value()))
            .failureHandler((req, res, e) -> res.setStatus(HttpStatus.UNAUTHORIZED.value())))
        // After an ORCID login always land on the SPA root, NOT the saved request. Without this the
        // default SavedRequestAwareAuthenticationSuccessHandler replays whatever request triggered
        // auth -- typically the SPA's own unauthenticated GET /api/me (which the ExceptionTranslation
        // filter still saves before the 401 entry point), sending the user to `/api/me?continue`
        // (raw JSON) instead of the app. alwaysUse=true ignores the saved request.
        .oauth2Login(o -> o
            .userInfoEndpoint(u -> u.oidcUserService(orcidUserService))
            .defaultSuccessUrl("/", true))
        .logout(out -> out.logoutUrl("/api/auth/logout")
            .logoutSuccessHandler((req, res, a) -> res.setStatus(HttpStatus.OK.value())))
        .exceptionHandling(ex -> ex
            .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
    return http.build();
  }
}

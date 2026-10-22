package com.javatechie.crud.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    UserDetailsService users(@Value("${app.login.username}") String username,
                             @Value("${app.login.password}") String password,
                             PasswordEncoder encoder) {
        if (username.isBlank() || password.length() < 12) {
            throw new IllegalArgumentException("Login requires a username and a password of at least 12 characters");
        }
        return new InMemoryUserDetailsManager(User.withUsername(username)
            .password(encoder.encode(password)).roles("USER").build());
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .anyRequest().authenticated())
            .formLogin(form -> form.defaultSuccessUrl("/orders", true).permitAll())
            .httpBasic(Customizer.withDefaults())
            // Keep CSRF protection enabled for login, logout and order mutations.
            .build();
    }
}

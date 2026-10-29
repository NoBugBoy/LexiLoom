package io.lexiloom.application.config;

import io.lexiloom.application.enums.AdminRoleEnum;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
@EnableMethodSecurity
public class SecurityConfig {

  private final AuthenticationFilter jwtAuthFilter;
  private final ObjectMapper objectMapper;

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception { // NOSONAR
    return http
        // 1. 现代无状态 API 标配：关闭 CSRF、启用标准 CORS 预检放行、关闭默认 Session
        .csrf(AbstractHttpConfigurer::disable) // NOSONAR: 无状态 JWT API，不使用 Cookie 会话，关闭 CSRF 是安全设计
        .cors(Customizer.withDefaults())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

        // 2. 彻底禁用默认自带的 FormLogin 和 Basic 认证
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)

        // 3. 粗粒度 URL 白名单路由
        .authorizeHttpRequests(
            auth ->
                auth
                    // 允许异步分派（如 SSE 流式响应 emitter.complete() 后的容器内部收尾调度）与错误分派
                    .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers(
                        "/auth/**", // 登录、注册、短信验证码
                        "/admin/auth/**", // 后台管理认证
                        "/v3/api-docs/**", // OpenAPI / Swagger 文档
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/favicon.ico",
                        "/error",
                        "/actuator/health", // 探活检查
                        "/uploads/**", // 静态上传图片资源
                        "/api/documents/parse-callback", // DeepDoc 异步解析结果回调 (免鉴权)
                        "/documents/parse-callback")
                    .permitAll()
                    .requestMatchers("/admin/**")
                    .hasAnyRole(AdminRoleEnum.ADMIN.name(), AdminRoleEnum.SUPER_ADMIN.name())
                    .anyRequest()
                    .authenticated())

        // 4. 统一异常响应 (401 未登录 / 403 权限不足)
        .exceptionHandling(
            ex ->
                ex.authenticationEntryPoint(
                        (req, resp, e) ->
                            writeErrorJson(
                                resp, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "未登录或登录已过期"))
                    .accessDeniedHandler(
                        (req, resp, e) ->
                            writeErrorJson(resp, HttpStatus.FORBIDDEN, "FORBIDDEN", "权限不足，拒绝访问")))

        // 5. 挂载唯一的 Token 验票过滤器
        .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  /** 提供默认跨域配置源，确保浏览器 OPTIONS 预检请求在 Security 层正常放行 */
  @Bean
  @ConditionalOnMissingBean(CorsConfigurationSource.class)
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOriginPatterns(List.of("*"));
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
    config.setAllowedHeaders(List.of("*"));
    config.setAllowCredentials(true);
    config.setMaxAge(3600L);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }

  /** 禁止 Spring Boot 将 AuthenticationFilter 重复自动注册到外层 Servlet 容器链 */
  @Bean
  public FilterRegistrationBean<AuthenticationFilter> authenticationFilterRegistration(
      AuthenticationFilter filter) {
    FilterRegistrationBean<AuthenticationFilter> registration =
        new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);
    return registration;
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  private void writeErrorJson(
      HttpServletResponse resp, HttpStatus status, String code, String msg) throws IOException {
    if (resp.isCommitted()) {
      return;
    }
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, msg);
    problem.setTitle(status == HttpStatus.UNAUTHORIZED ? "认证失败" : "权限不足");
    problem.setType(URI.create("urn:problem-type:security-error"));
    problem.setProperty("code", code);
    problem.setProperty("timestamp", Instant.now());

    resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
    resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
    resp.setStatus(status.value());
    resp.getWriter().write(objectMapper.writeValueAsString(problem));
  }
}

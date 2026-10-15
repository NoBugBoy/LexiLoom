package io.lexiloom.application.config;

import io.lexiloom.application.enums.AdminRoleEnum;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
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
        // 1. 现代无状态 API 标配：关闭 CSRF、CORS 放行、关闭默认 Session
        .csrf(AbstractHttpConfigurer::disable) // NOSONAR: 无状态 JWT API，不使用 Cookie 会话，关闭 CSRF 是安全设计
        .cors(AbstractHttpConfigurer::disable)
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
                            writeErrorJson(resp, HttpStatus.UNAUTHORIZED, "未登录或登录已过期"))
                    .accessDeniedHandler(
                        (req, resp, e) -> writeErrorJson(resp, HttpStatus.FORBIDDEN, "权限不足，拒绝访问")))

        // 5. 挂载唯一的 Token 验票过滤器
        .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  private void writeErrorJson(HttpServletResponse resp, HttpStatusCode status, String msg)
      throws IOException {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, msg);
    resp.setContentType("application/json;charset=UTF-8");
    resp.setStatus(status.value());
    resp.getWriter().write(objectMapper.writeValueAsString(problem));
  }
}

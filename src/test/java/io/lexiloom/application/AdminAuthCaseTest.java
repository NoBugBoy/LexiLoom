package io.lexiloom.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.lexiloom.application.common.CustomerProperties;
import io.lexiloom.application.common.cache.CacheHelper;
import io.lexiloom.application.common.security.RedisTokenManager;
import io.lexiloom.application.enums.AdminRoleEnum;
import io.lexiloom.application.exception.BusinessException;
import io.lexiloom.application.user.api.AdminQueryService;
import io.lexiloom.application.user.api.dto.AdminRegisterDTO;
import io.lexiloom.application.user.api.dto.AdminResetPasswordDTO;
import io.lexiloom.application.user.cache.AccountCacheKey;
import io.lexiloom.application.user.model.entity.AdminEntity;
import io.lexiloom.application.user.model.request.AdminCreateRequest;
import io.lexiloom.application.user.model.request.AdminLoginRequest;
import io.lexiloom.application.user.model.request.ResetPasswordRequest;
import io.lexiloom.application.user.service.AdminCommandService;
import io.lexiloom.application.user.service.AdminRoleService;
import io.lexiloom.application.user.usecase.admin.AdminLoginUseCase;
import io.lexiloom.application.user.usecase.admin.AdminRegisterUseCase;
import io.lexiloom.application.user.usecase.admin.SendAdminRegisterEmailUseCase;
import io.lexiloom.application.user.usecase.admin.SendAdminRestPasswordEmailUseCase;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AdminAuthCaseTest {

  private static final KeyPair KEY_PAIR = rsaKeyPair();

  @Mock private JavaMailSender mailSender;
  @Mock private CacheHelper cacheHelper;
  @Mock private AdminQueryService adminQueryService;
  @Mock private AdminCommandService adminCommandService;
  @Mock private AdminRoleService adminRoleService;
  @Mock private PasswordEncoder passwordEncoder;
  @Mock private CustomerProperties customerProperties;
  @Mock private CustomerProperties.Security security;
  @Mock private RedisTokenManager redisTokenManager;

  @InjectMocks private SendAdminRegisterEmailUseCase sendAdminRegisterEmailUseCase;
  @InjectMocks private SendAdminRestPasswordEmailUseCase sendAdminRestPasswordEmailUseCase;
  @InjectMocks private AdminLoginUseCase adminLoginUseCase;
  @InjectMocks private AdminRegisterUseCase adminRegisterUseCase;

  @BeforeEach
  void setUp() {
    lenient().when(customerProperties.security()).thenReturn(security);
    lenient().when(security.getPrivateKey()).thenReturn(KEY_PAIR.getPrivate());
    lenient().when(security.getPublicKey()).thenReturn(KEY_PAIR.getPublic());
  }

  @Nested
  @DisplayName("SendAdminRegisterEmailUseCase 测试")
  class SendAdminRegisterEmailUseCaseTest {

    @Test
    @DisplayName("当注册邮箱已存在时应抛出异常")
    void shouldThrowExceptionWhenEmailAlreadyExists() {
      String email = "exist@qq.com";
      AdminCreateRequest request = new AdminCreateRequest(email, "password123");

      AdminEntity mockAdmin = new AdminEntity();
      mockAdmin.setEmail(email);
      given(adminQueryService.findAdminByEmail(email)).willReturn(Optional.of(mockAdmin));

      assertThatThrownBy(() -> sendAdminRegisterEmailUseCase.execute(request))
          .isInstanceOf(BusinessException.class)
          .hasMessage("邮箱已注册");

      verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("当注册邮箱不存在时应成功写入缓存并发送邮件")
    void shouldSendEmailWhenEmailNotExists() {
      String email = "new@qq.com";
      AdminCreateRequest request = new AdminCreateRequest(email, "password123");

      given(adminQueryService.findAdminByEmail(email)).willReturn(Optional.empty());
      MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
      given(mailSender.createMimeMessage()).willReturn(mimeMessage);

      sendAdminRegisterEmailUseCase.execute(request);

      verify(cacheHelper)
          .put(eq(AccountCacheKey.OTT_REGISTER), anyString(), any(AdminRegisterDTO.class));
      verify(mailSender).send(mimeMessage);
    }
  }

  @Nested
  @DisplayName("SendAdminRestPasswordEmailUseCase 测试")
  class SendAdminRestPasswordEmailUseCaseTest {

    @Test
    @DisplayName("重置密码时若账号不存在应抛出异常")
    void shouldThrowExceptionWhenAccountNotExists() {
      String email = "notfound@qq.com";
      ResetPasswordRequest request = new ResetPasswordRequest(email, "newPassword123");

      given(adminQueryService.findAdminByEmail(email)).willReturn(Optional.empty());

      assertThatThrownBy(() -> sendAdminRestPasswordEmailUseCase.execute(request))
          .isInstanceOf(BusinessException.class)
          .hasMessage("该账号不存在");

      verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("重置密码时若账号存在应缓存Token并发送重置邮件")
    void shouldSendEmailWhenAccountExists() {
      String email = "exists@qq.com";
      ResetPasswordRequest request = new ResetPasswordRequest(email, "newPassword123");

      AdminEntity mockAdmin = new AdminEntity();
      mockAdmin.setEmail(email);
      given(adminQueryService.findAdminByEmail(email)).willReturn(Optional.of(mockAdmin));

      MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
      given(mailSender.createMimeMessage()).willReturn(mimeMessage);

      sendAdminRestPasswordEmailUseCase.execute(request);

      verify(cacheHelper)
          .put(
              eq(AccountCacheKey.OTT_RESET_PASSWORD),
              anyString(),
              any(AdminResetPasswordDTO.class));
      verify(mailSender).send(mimeMessage);
    }
  }

  @Nested
  @DisplayName("AdminLoginUseCase 测试")
  class AdminLoginUseCaseTest {

    @Test
    @DisplayName("登录时用户不存在应抛出业务异常")
    void shouldThrowExceptionWhenUserNotFound() {
      AdminLoginRequest request = new AdminLoginRequest("admin@lexiloom.com", "wrongpwd", null, null);
      given(adminQueryService.findAdminByEmail("admin@lexiloom.com")).willReturn(Optional.empty());

      assertThatThrownBy(() -> adminLoginUseCase.execute(request))
          .isInstanceOf(BusinessException.class)
          .hasMessage("用户名或密码错误");
    }

    @Test
    @DisplayName("登录时密码不匹配应抛出业务异常")
    void shouldThrowExceptionWhenPasswordMismatch() {
      AdminLoginRequest request = new AdminLoginRequest("admin@lexiloom.com", "wrongpwd", null, null);
      AdminEntity admin = new AdminEntity();
      admin.setId(1L);
      admin.setPassword("encoded_pwd");

      given(adminQueryService.findAdminByEmail("admin@lexiloom.com")).willReturn(Optional.of(admin));
      given(passwordEncoder.matches("wrongpwd", "encoded_pwd")).willReturn(false);

      assertThatThrownBy(() -> adminLoginUseCase.execute(request))
          .isInstanceOf(BusinessException.class)
          .hasMessage("用户名或密码错误");
    }

    @Test
    @DisplayName("登录成功应生成有效 JWT Token")
    void shouldReturnTokenOnSuccess() {
      AdminLoginRequest request =
          new AdminLoginRequest("admin@lexiloom.com", "correctpwd", null, null);
      AdminEntity admin = new AdminEntity();
      admin.setId(88L);
      admin.setPassword("encoded_correct");

      given(adminQueryService.findAdminByEmail("admin@lexiloom.com")).willReturn(Optional.of(admin));
      given(passwordEncoder.matches("correctpwd", "encoded_correct")).willReturn(true);
      given(adminRoleService.findRolesByUserId(88L)).willReturn(List.of(AdminRoleEnum.ADMIN));
      given(redisTokenManager.generateToken(eq(88L), any())).willReturn("mock-admin-token");

      String token = adminLoginUseCase.execute(request);

      assertThat(token).isEqualTo("mock-admin-token");
    }
  }

  @Nested
  @DisplayName("AdminRegisterUseCase 测试")
  class AdminRegisterUseCaseTest {

    @Test
    @DisplayName("OTT无效或过期时应抛出异常")
    void shouldThrowExceptionWhenOttNotFound() {
      given(cacheHelper.getAndEvict(AccountCacheKey.OTT_REGISTER, "invalid_ott"))
          .willReturn(Optional.empty());

      assertThatThrownBy(() -> adminRegisterUseCase.execute("invalid_ott"))
          .isInstanceOf(BusinessException.class)
          .hasMessage("链接不存在或已经失效");
    }

    @Test
    @DisplayName("OTT有效时应保存用户、赋予ADMIN角色并返回JWT Token")
    void shouldRegisterAndReturnTokenWhenOttValid() {
      AdminRegisterDTO dto = new AdminRegisterDTO("newadmin@lexiloom.com", "rawpwd");
      given(cacheHelper.getAndEvict(AccountCacheKey.OTT_REGISTER, "valid_ott"))
          .willReturn(Optional.of(dto));
      given(passwordEncoder.encode("rawpwd")).willReturn("enc_pwd");
      given(adminCommandService.saveAdminUser("newadmin@lexiloom.com", "enc_pwd")).willReturn(99L);
      given(redisTokenManager.generateToken(eq(99L), any())).willReturn("mock-register-token");

      String token = adminRegisterUseCase.execute("valid_ott");

      assertThat(token).isEqualTo("mock-register-token");
      verify(adminRoleService).assignRole(99L, AdminRoleEnum.ADMIN);
    }

    @Test
    @DisplayName("系统初次启动未存在超管时应自动创建超管")
    void shouldInitSuperAdminWhenNotPresent() throws Exception {
      given(adminQueryService.findAdminByEmail("admin@lexiloom.com")).willReturn(Optional.empty());
      given(passwordEncoder.encode("123456")).willReturn("super_enc_pwd");
      given(adminCommandService.saveAdminUser("admin@lexiloom.com", "super_enc_pwd"))
          .willReturn(1L);

      adminRegisterUseCase.run();

      verify(adminRoleService).assignRole(1L, AdminRoleEnum.SUPER_ADMIN);
    }

    @Test
    @DisplayName("系统启动时若超管已存在则不重复创建")
    void shouldNotCreateSuperAdminWhenAlreadyPresent() throws Exception {
      AdminEntity superAdmin = new AdminEntity();
      superAdmin.setId(1L);
      given(adminQueryService.findAdminByEmail("admin@lexiloom.com"))
          .willReturn(Optional.of(superAdmin));

      adminRegisterUseCase.run();

      verify(adminCommandService, never()).saveAdminUser(anyString(), anyString());
    }
  }

  private static KeyPair rsaKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException("生成 RSA 密钥失败", e);
    }
  }
}

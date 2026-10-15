package io.lexiloom.application.user.usecase.admin;

import cn.hutool.core.util.RandomUtil;
import io.lexiloom.application.common.UseCase;
import io.lexiloom.application.common.cache.CacheHelper;
import io.lexiloom.application.exception.BusinessException;
import io.lexiloom.application.user.api.AdminQueryService;
import io.lexiloom.application.user.api.dto.AdminRegisterDTO;
import io.lexiloom.application.user.cache.AccountCacheKey;
import io.lexiloom.application.user.model.request.AdminCreateRequest;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class SendAdminRegisterEmailUseCase implements UseCase<AdminCreateRequest, Void> {

  private final JavaMailSender mailSender;
  private final CacheHelper cacheHelper;
  private final AdminQueryService adminQueryService;

  @org.springframework.beans.factory.annotation.Value("${spring.mail.username:754369677@qq.com}")
  private String mailFrom = "754369677@qq.com";

  private static final String EMAIL_TEMPLATE = loadEmailTemplate();

  private static String loadEmailTemplate() {
    try {
      return new ClassPathResource("templates/mail/register-email.html")
          .getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("无法加载模板 templates/mail/register-email.html", e);
    }
  }

  @Override
  public Void execute(AdminCreateRequest adminCreateRequest) {
    try {
      String email = adminCreateRequest.email();
      adminQueryService
          .findAdminByEmail(email)
          .ifPresent(
              it -> {
                throw BusinessException.badRequest("邮箱已注册");
              });

      String tokenValue = RandomUtil.randomNumbers(4);
      cacheHelper.put(
          AccountCacheKey.OTT_REGISTER,
          tokenValue,
          new AdminRegisterDTO(email, adminCreateRequest.password()));
      sendRegisterEmailLink(email, tokenValue);
    } catch (MessagingException e) {
      throw new RuntimeException(e);
    }
    return null;
  }

  private void sendRegisterEmailLink(String email, String tokenValue) throws MessagingException {

    MimeMessage mimeMessage = mailSender.createMimeMessage();
    MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
    helper.setFrom(mailFrom);
    helper.setTo(email);

    registerEmail(helper, tokenValue);

    mailSender.send(mimeMessage);
  }

  private void registerEmail(MimeMessageHelper helper, String tokenValue)
      throws MessagingException {
    helper.setSubject("【LexiLoom】登录/注册一次性验证链接");
    String magicLink = "http://localhost:5173/complete-register?ott=" + tokenValue;

    String htmlContent =
        EMAIL_TEMPLATE
            .replace("{{magicLink}}", magicLink)
            .replace("{{tokenValue}}", tokenValue)
            .replace("{{expireMinutes}}", "10");

    helper.setText(htmlContent, true);
  }
}

package io.lexiloom.application.user.usecase.admin;

import cn.hutool.core.util.IdUtil;
import io.lexiloom.application.common.UseCase;
import io.lexiloom.application.common.cache.CacheHelper;
import io.lexiloom.application.exception.BusinessException;
import io.lexiloom.application.user.service.AdminQueryService;
import io.lexiloom.application.user.api.dto.AdminResetPasswordDTO;
import io.lexiloom.application.user.cache.AccountCacheKey;
import io.lexiloom.application.user.model.request.ResetPasswordRequest;
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
@Slf4j
@RequiredArgsConstructor
public class SendAdminRestPasswordEmailUseCase implements UseCase<ResetPasswordRequest, Void> {
  private final JavaMailSender mailSender;
  private final CacheHelper cacheHelper;
  private final AdminQueryService adminQueryService;

  @org.springframework.beans.factory.annotation.Value("${spring.mail.username:754369677@qq.com}")
  private String mailFrom = "754369677@qq.com";

  @org.springframework.beans.factory.annotation.Value("${lexiloom.portal.url:http://localhost:5173}")
  private String portalUrl = "http://localhost:5173";

  private static final String EMAIL_TEMPLATE = loadEmailTemplate();

  private static String loadEmailTemplate() {
    try {
      return new ClassPathResource("templates/mail/reset-password-email.html")
          .getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("无法加载模板 templates/mail/reset-password-email.html", e);
    }
  }

  @Override
  public Void execute(ResetPasswordRequest command) {
    adminQueryService
        .findAdminByEmail(command.email())
        .orElseThrow(() -> BusinessException.badRequest("该账号不存在"));

    String tokenValue = IdUtil.fastSimpleUUID();
    cacheHelper.put(
        AccountCacheKey.OTT_RESET_PASSWORD,
        tokenValue,
        new AdminResetPasswordDTO(command.email(), command.newPassword()));

    try {
      sendRestPasswordEmailLink(command.email(), tokenValue);
    } catch (MessagingException e) {
      throw new RuntimeException(e);
    }
    return null;
  }

  private void sendRestPasswordEmailLink(String email, String tokenValue)
      throws MessagingException {

    MimeMessage mimeMessage = mailSender.createMimeMessage();
    MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
    helper.setFrom(mailFrom);
    helper.setTo(email);

    resetEmail(helper, tokenValue);

    mailSender.send(mimeMessage);
  }

  private void resetEmail(MimeMessageHelper helper, String tokenValue) throws MessagingException {
    helper.setSubject("【LexiLoom】重置密码一次性验证链接");
    String baseUrl = portalUrl.endsWith("/") ? portalUrl.substring(0, portalUrl.length() - 1) : portalUrl;
    String magicLink = baseUrl + "/complete-reset?ott=" + tokenValue;

    String htmlContent =
        EMAIL_TEMPLATE
            .replace("{{magicLink}}", magicLink)
            .replace("{{tokenValue}}", tokenValue)
            .replace("{{expireMinutes}}", "10");

    helper.setText(htmlContent, true);
  }
}

package io.lexiloom.application.user.controller;

import io.lexiloom.application.user.service.UserCommandService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author yujian
 */
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

  private final UserCommandService userCommandService;

  public record UpdateProfileRequest(
      @NotBlank(message = "昵称不能为空") String nickName,
      @NotBlank(message = "头像不能为空") String avatarUrl) {}

  /**
   * 更新当前登录用户的昵称和头像。
   *
   * @param request 个人资料更新参数
   * @return 204 No Content
   */
  @PutMapping("/update/profile")
  public ResponseEntity<Void> updateProfile(@Validated @RequestBody UpdateProfileRequest request) {
    userCommandService.updateProfile(request.nickName, request.avatarUrl);
    return ResponseEntity.noContent().build();
  }
}

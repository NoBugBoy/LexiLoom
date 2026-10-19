package io.lexiloom.application.user.api.dto;

/**
 * @author yujian
 */
public record UserDTO(
    Long userId,
    String openId,
    String unionId,
    String userName,
    String avatarUrl,
    String email,
    Boolean emailVerified,
    String phoneNumber,
    String countryCode,
    Boolean phoneVerified) {}

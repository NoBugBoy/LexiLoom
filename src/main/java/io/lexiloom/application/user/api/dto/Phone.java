package io.lexiloom.application.user.api.dto;

public record Phone(String phoneNumber, String countryCode, Boolean phoneVerified) {
  public Phone(String phoneNumber) {
    this(phoneNumber, "86", false);
  }
}

package com.autocare.ai.booking;

/** 输出不符合结构或证据约束，不自动重试修成成功。 */
public class DraftValidationException extends RuntimeException {

  public DraftValidationException() {
    super("模型输出不符合草稿约束");
  }
}

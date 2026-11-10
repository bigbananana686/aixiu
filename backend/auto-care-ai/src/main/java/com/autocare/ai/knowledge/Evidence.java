package com.autocare.ai.knowledge;

public record Evidence(
  String documentKey,
  String documentVersion,
  String chunkKey,
  String sourceLocator,
  String documentTitle,
  String text
) {}

package com.example.domain.model

data class InstalledAppInfo(
  val appId: AppId,
  val displayLabel: String,
  val isSystemEssential: Boolean = false,
)

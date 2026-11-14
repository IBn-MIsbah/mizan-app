package com.example.domain.model

@JvmInline
value class CommitmentId(val value: String) {
  init {
    require(value.isNotBlank()) { "CommitmentId must not be blank" }
  }
}

@JvmInline
value class AppId(val packageName: String) {
  init {
    require(packageName.isNotBlank()) { "AppId packageName must not be blank" }
  }
}

@JvmInline
value class UsageEventId(val value: String) {
  init {
    require(value.isNotBlank()) { "UsageEventId must not be blank" }
  }
}

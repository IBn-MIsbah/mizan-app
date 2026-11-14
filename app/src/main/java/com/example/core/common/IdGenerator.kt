package com.example.core.common

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface IdGenerator {
  fun nextId(): String
}

@Singleton
class UuidGenerator @Inject constructor() : IdGenerator {
  override fun nextId(): String = UUID.randomUUID().toString()
}

package com.example.core.common

import com.example.domain.repository.PersistenceError

fun PersistenceError.toDisplayMessage(): String =
  when (this) {
    is PersistenceError.NotFound -> "$entity could not be found."
    is PersistenceError.DuplicateRecord -> detail
    is PersistenceError.ConstraintViolation -> detail
    is PersistenceError.CorruptData -> detail
    is PersistenceError.DomainRuleViolation -> detail
    is PersistenceError.StorageFailure -> detail
  }

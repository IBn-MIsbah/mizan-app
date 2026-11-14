package com.example.domain.repository

sealed interface PersistenceError {
  data class NotFound(val entity: String) : PersistenceError

  data class DuplicateRecord(val detail: String) : PersistenceError

  data class ConstraintViolation(val detail: String) : PersistenceError

  data class CorruptData(val detail: String) : PersistenceError

  data class DomainRuleViolation(val detail: String) : PersistenceError

  data class StorageFailure(val detail: String) : PersistenceError
}

sealed interface PersistenceResult<out T> {
  data class Success<out T>(val data: T) : PersistenceResult<T>

  data class Failure(val error: PersistenceError) : PersistenceResult<Nothing>
}

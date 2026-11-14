package com.example.domain.repository

import com.example.domain.model.FoundationStatus
import kotlinx.coroutines.flow.Flow

interface FoundationRepository {
  fun observeFoundationStatus(): Flow<FoundationStatus>
}

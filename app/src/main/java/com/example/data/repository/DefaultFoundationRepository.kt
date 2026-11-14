package com.example.data.repository

import com.example.BuildConfig
import com.example.core.common.DispatcherProvider
import com.example.domain.model.FoundationStatus
import com.example.domain.repository.FoundationRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

@Singleton
class DefaultFoundationRepository
@Inject
constructor(private val dispatcherProvider: DispatcherProvider) : FoundationRepository {

  override fun observeFoundationStatus(): Flow<FoundationStatus> =
    flow {
        emit(
          FoundationStatus(
            applicationId = BuildConfig.APPLICATION_ID,
            milestoneLabel = "Milestone 0 · Project Foundation",
            isArchitectureReady = true,
            activeLayers =
              listOf(
                "Domain & Data Abstractions",
                "Hilt Dependency Injection",
                "Type-Safe Navigation Compose",
                "Material 3 Adaptive Scaffold",
              ),
            deferredScopeNotes =
              listOf(
                "Application blocking & enforcement deferred",
                "AccessibilityService & VPN/DNS filtering omitted",
                "Usage tracking & commitment business logic deferred",
              ),
          )
        )
      }
      .flowOn(dispatcherProvider.io)
}

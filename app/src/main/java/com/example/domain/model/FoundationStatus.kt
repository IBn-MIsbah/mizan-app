package com.example.domain.model

data class FoundationStatus(
  val applicationId: String,
  val milestoneLabel: String,
  val isArchitectureReady: Boolean,
  val activeLayers: List<String>,
  val deferredScopeNotes: List<String>,
)

package com.example.domain.repository

import com.example.domain.model.InstalledAppInfo

sealed interface InstalledAppsResult {
  data class Success(val apps: List<InstalledAppInfo>) : InstalledAppsResult

  data class Failure(val message: String) : InstalledAppsResult
}

interface InstalledAppRepository {
  suspend fun getLaunchableApps(): InstalledAppsResult
}

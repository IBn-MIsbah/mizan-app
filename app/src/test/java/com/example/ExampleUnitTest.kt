package com.example

import com.example.core.common.DispatcherProvider
import com.example.data.repository.DefaultFoundationRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
  val testDispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
  override fun starting(description: Description) {
    Dispatchers.setMain(testDispatcher)
  }

  override fun finished(description: Description) {
    Dispatchers.resetMain()
  }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ExampleUnitTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  @Test
  fun `default foundation repository emits mizan application id`() = runTest {
    val dispatcherProvider =
      object : DispatcherProvider {
        override val main: CoroutineDispatcher = mainDispatcherRule.testDispatcher
        override val io: CoroutineDispatcher = mainDispatcherRule.testDispatcher
        override val default: CoroutineDispatcher = mainDispatcherRule.testDispatcher
      }
    val repository = DefaultFoundationRepository(dispatcherProvider)

    val status = repository.observeFoundationStatus().first()

    assertEquals("com.ibnmisbah.mizan", status.applicationId)
    assertTrue(status.isArchitectureReady)
    assertTrue(status.activeLayers.isNotEmpty())
    assertTrue(status.deferredScopeNotes.isNotEmpty())
  }
}

package com.example.feature.guide

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.example.core.navigation.MizanRoute
import com.example.domain.repository.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class GuideUiState(
  val selectedCategory: GuideCategory? = null,
  val selectedTopicId: String = GuideTopicsCatalog.topics.first().id,
  val walkthroughMode: Boolean = false,
  val firstUseGuideCompleted: Boolean = false,
  val reduceGuideAnimations: Boolean = false,
) {
  val visibleTopics: List<GuideTopic>
    get() =
      if (walkthroughMode || selectedCategory == null) {
        GuideTopicsCatalog.topics
      } else {
        GuideTopicsCatalog.topics.filter { it.category == selectedCategory }
      }

  val activeTopic: GuideTopic
    get() =
      visibleTopics.firstOrNull { it.id == selectedTopicId }
        ?: visibleTopics.firstOrNull()
        ?: GuideTopicsCatalog.topics.first()
}

@HiltViewModel
class GuideViewModel
@Inject
constructor(
  savedStateHandle: SavedStateHandle,
  private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

  private val route = runCatching { savedStateHandle.toRoute<MizanRoute.InteractiveGuide>() }.getOrNull()
  private val initialTopicId =
    route?.sectionId?.takeIf { id -> GuideTopicsCatalog.topics.any { it.id == id } }
      ?: GuideTopicsCatalog.topics.first().id
  private val initialWalkthrough = route?.walkthroughMode ?: false

  private val _selectionState =
    MutableStateFlow(
      GuideSelectionInternal(
        selectedCategory = null,
        selectedTopicId = initialTopicId,
        walkthroughMode = initialWalkthrough,
      )
    )

  val uiState: StateFlow<GuideUiState> =
    combine(_selectionState, userPreferencesRepository.preferences) { selection, prefs ->
      GuideUiState(
        selectedCategory = selection.selectedCategory,
        selectedTopicId = selection.selectedTopicId,
        walkthroughMode = selection.walkthroughMode,
        firstUseGuideCompleted = prefs.firstUseGuideCompleted,
        reduceGuideAnimations = prefs.reduceGuideAnimations,
      )
    }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue =
          GuideUiState(
            selectedCategory = null,
            selectedTopicId = initialTopicId,
            walkthroughMode = initialWalkthrough,
          ),
      )

  fun selectCategory(category: GuideCategory?) {
    val topics =
      if (category == null) GuideTopicsCatalog.topics
      else GuideTopicsCatalog.topics.filter { it.category == category }
    val nextId =
      if (topics.any { it.id == _selectionState.value.selectedTopicId }) {
        _selectionState.value.selectedTopicId
      } else {
        topics.firstOrNull()?.id ?: GuideTopicsCatalog.topics.first().id
      }
    _selectionState.value =
      _selectionState.value.copy(
        selectedCategory = category,
        selectedTopicId = nextId,
        walkthroughMode = false,
      )
  }

  fun selectTopic(topicId: String) {
    if (GuideTopicsCatalog.topics.none { it.id == topicId }) return
    _selectionState.value = _selectionState.value.copy(selectedTopicId = topicId)
  }

  fun nextTopic() {
    val list = uiState.value.visibleTopics
    val idx = list.indexOfFirst { it.id == uiState.value.activeTopic.id }
    if (idx in 0 until list.lastIndex) {
      _selectionState.value = _selectionState.value.copy(selectedTopicId = list[idx + 1].id)
    }
  }

  fun previousTopic() {
    val list = uiState.value.visibleTopics
    val idx = list.indexOfFirst { it.id == uiState.value.activeTopic.id }
    if (idx > 0) {
      _selectionState.value = _selectionState.value.copy(selectedTopicId = list[idx - 1].id)
    }
  }

  fun completeWalkthrough() {
    viewModelScope.launch { userPreferencesRepository.setFirstUseGuideCompleted(true) }
    _selectionState.value = _selectionState.value.copy(walkthroughMode = false)
  }

  fun skipWalkthrough() {
    viewModelScope.launch { userPreferencesRepository.setFirstUseGuideCompleted(true) }
    _selectionState.value = _selectionState.value.copy(walkthroughMode = false)
  }

  fun replayWalkthrough() {
    viewModelScope.launch { userPreferencesRepository.setFirstUseGuideCompleted(false) }
    _selectionState.value =
      GuideSelectionInternal(
        selectedCategory = null,
        selectedTopicId = GuideTopicsCatalog.topics.first().id,
        walkthroughMode = true,
      )
  }

  fun setReduceMotion(reduce: Boolean) {
    viewModelScope.launch { userPreferencesRepository.setReduceGuideAnimations(reduce) }
  }

  private data class GuideSelectionInternal(
    val selectedCategory: GuideCategory?,
    val selectedTopicId: String,
    val walkthroughMode: Boolean,
  )
}

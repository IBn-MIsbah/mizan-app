package com.example.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.core.designsystem.component.MizanBrandHeader
import com.example.core.designsystem.component.MizanFlowTopBar
import com.example.core.designsystem.component.MizanScopeNoticeCard
import com.example.core.designsystem.component.MizanSurfaceCard
import kotlinx.coroutines.flow.collectLatest

@Composable
fun OnboardingRoute(
  onOnboardingCompleted: () -> Unit,
  onNavigateBack: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: OnboardingViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  LaunchedEffect(viewModel) {
    viewModel.effects.collectLatest { effect ->
      when (effect) {
        OnboardingEffect.NavigateToDashboard -> onOnboardingCompleted()
      }
    }
  }

  OnboardingScreen(
    uiState = uiState,
    onContinue = viewModel::completeOnboarding,
    onNavigateBack = onNavigateBack,
    modifier = modifier,
  )
}

@Composable
fun OnboardingScreen(
  uiState: OnboardingUiState,
  onContinue: () -> Unit,
  onNavigateBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val state = uiState as OnboardingUiState.Ready

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp).testTag("onboarding_screen_list"),
      contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      if (state.revisitFromSettings) {
        item {
          MizanFlowTopBar(
            title = stringResource(R.string.onboarding_title),
            onNavigateBack = onNavigateBack,
            backTestTag = "onboarding_back_button",
          )
        }
      } else {
        item { MizanBrandHeader() }

        item {
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
              text = stringResource(R.string.onboarding_title),
              style = MaterialTheme.typography.headlineSmall,
              color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
              text = stringResource(R.string.onboarding_subtitle),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }

      item {
        OnboardingInfoCard(
          icon = Icons.Outlined.Timer,
          iconDescription = stringResource(R.string.cd_status_indicator),
          title = stringResource(R.string.onboarding_card_commitment_title),
          body = stringResource(R.string.onboarding_card_commitment_body),
          modifier = Modifier.testTag("onboarding_commitment_card"),
        )
      }

      item {
        OnboardingInfoCard(
          icon = Icons.Outlined.Lock,
          iconDescription = stringResource(R.string.cd_privacy_icon),
          title = stringResource(R.string.onboarding_card_privacy_title),
          body = stringResource(R.string.onboarding_card_privacy_body),
          modifier = Modifier.testTag("onboarding_privacy_card"),
        )
      }

      item {
        OnboardingInfoCard(
          icon = Icons.Outlined.Security,
          iconDescription = stringResource(R.string.cd_permissions_icon),
          title = stringResource(R.string.onboarding_card_permissions_title),
          body = stringResource(R.string.onboarding_card_permissions_body),
          modifier = Modifier.testTag("onboarding_permissions_card"),
        )
      }

      item { MizanScopeNoticeCard() }

      if (state.errorMessage != null) {
        item {
          MizanSurfaceCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
            Text(
              text = state.errorMessage,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onErrorContainer,
              modifier = Modifier.testTag("onboarding_error_text"),
            )
          }
        }
      }

      item {
        if (state.revisitFromSettings) {
          OutlinedButton(
            onClick = onNavigateBack,
            modifier =
              Modifier.fillMaxWidth()
                .minimumInteractiveComponentSize()
                .heightIn(min = 48.dp)
                .testTag("onboarding_return_settings_button"),
          ) {
            Text(text = stringResource(R.string.onboarding_return_settings_button))
          }
        } else {
          Button(
            onClick = onContinue,
            enabled = !state.isSubmitting,
            modifier =
              Modifier.fillMaxWidth()
                .minimumInteractiveComponentSize()
                .heightIn(min = 48.dp)
                .testTag("onboarding_complete_button"),
          ) {
            if (state.isSubmitting) {
              CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
              )
            } else {
              Text(text = stringResource(R.string.onboarding_continue_button))
            }
          }
        }
      }
    }
  }
}

@Composable
private fun OnboardingInfoCard(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  iconDescription: String,
  title: String,
  body: String,
  modifier: Modifier = Modifier,
) {
  MizanSurfaceCard(modifier = modifier) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(
        imageVector = icon,
        contentDescription = iconDescription,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(22.dp),
      )
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
    Text(
      text = body,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

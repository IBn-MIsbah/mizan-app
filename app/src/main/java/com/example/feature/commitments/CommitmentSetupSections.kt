package com.example.feature.commitments

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.designsystem.component.ContextualHelpButton
import com.example.core.designsystem.component.MizanSurfaceCard
import com.example.core.time.WallClockFormatting
import com.example.domain.model.AllowlistCategory
import com.example.domain.model.AppId
import com.example.domain.model.InstalledAppInfo
import com.example.domain.model.RestrictionMode
import com.example.domain.time.DomainDayOfWeek
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
private fun SectionHeaderWithHelp(
  title: String,
  onShowHelp: (() -> Unit)?,
  helpTestTag: String,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(
      text = title,
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.weight(1f),
    )
    if (onShowHelp != null) {
      ContextualHelpButton(
        onClick = onShowHelp,
        contentDescription = stringResource(R.string.guide_contextual_help_cd),
        testTag = helpTestTag,
      )
    }
  }
}

@Composable
internal fun ModeSelectionCard(
  selectedMode: RestrictionMode,
  onSelectMode: (RestrictionMode) -> Unit,
  onShowHelp: (() -> Unit)? = null,
) {
  MizanSurfaceCard(modifier = Modifier.testTag("setup_mode_card")) {
    SectionHeaderWithHelp(
      title = stringResource(R.string.setup_mode_title),
      onShowHelp = onShowHelp,
      helpTestTag = "setup_mode_help_button",
    )
    RestrictionMode.entries.forEach { mode ->
      val (titleRes, descRes) =
        when (mode) {
          RestrictionMode.STRICT ->
            R.string.setup_mode_strict_title to R.string.setup_mode_strict_desc
          RestrictionMode.SCHEDULED ->
            R.string.setup_mode_scheduled_title to R.string.setup_mode_scheduled_desc
          RestrictionMode.HYBRID ->
            R.string.setup_mode_hybrid_title to R.string.setup_mode_hybrid_desc
        }
      Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
      ) {
        RadioButton(
          selected = selectedMode == mode,
          onClick = { onSelectMode(mode) },
          modifier =
            Modifier.minimumInteractiveComponentSize()
              .testTag("setup_mode_${mode.name.lowercase()}"),
        )
        Column(modifier = Modifier.padding(start = 4.dp)) {
          Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = stringResource(descRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

@Composable
internal fun RestrictedAppsSelectionCard(
  editor: DraftEditorState,
  onSearchChange: (String) -> Unit,
  onToggleInstalledApp: (InstalledAppInfo) -> Unit,
  onCustomLabelChange: (String) -> Unit,
  onCustomPackageChange: (String) -> Unit,
  onAddCustom: () -> Unit,
  onRemoveApp: (AppId) -> Unit,
  onShowHelp: (() -> Unit)? = null,
) {
  val restrictableInstalled =
    editor.installedApps
      .filterNot { it.isSystemEssential }
      .filter { app ->
        val query = editor.restrictedSearchQuery.trim()
        query.isEmpty() ||
          app.displayLabel.contains(query, ignoreCase = true) ||
          app.appId.packageName.contains(query, ignoreCase = true)
      }

  val installedPackageSet = editor.installedApps.map { it.appId }.toSet()

  MizanSurfaceCard(modifier = Modifier.testTag("setup_restricted_apps_card")) {
    SectionHeaderWithHelp(
      title = stringResource(R.string.setup_restricted_title),
      onShowHelp = onShowHelp,
      helpTestTag = "setup_restricted_help_button",
    )
    Text(
      text = stringResource(R.string.setup_installed_apps_note),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (editor.installedAppsError != null) {
      Text(
        text = editor.installedAppsError,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )
    }

    if (editor.installedApps.any { !it.isSystemEssential }) {
      OutlinedTextField(
        value = editor.restrictedSearchQuery,
        onValueChange = onSearchChange,
        label = { Text(stringResource(R.string.setup_search_apps_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("setup_restricted_search_input"),
      )
    }

    if (editor.installedAppsLoaded && editor.installedApps.none { !it.isSystemEssential }) {
      Text(
        text = stringResource(R.string.setup_installed_apps_empty),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        restrictableInstalled.take(30).forEach { app ->
          val selected = editor.restrictedApps.any { it.appId == app.appId }
          InstalledAppSelectionRow(
            app = app,
            selected = selected,
            onToggle = { onToggleInstalledApp(app) },
            testTag = "setup_restricted_app_${app.appId.packageName}",
          )
        }
      }
    }

    val staleOrManualSelected =
      editor.restrictedApps.filter { selected ->
        selected.appId !in installedPackageSet ||
          editor.installedApps.any { it.appId == selected.appId && it.isSystemEssential }
      }
    if (staleOrManualSelected.isNotEmpty()) {
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        staleOrManualSelected.forEach { app ->
          val isMissing = editor.installedAppsLoaded && app.appId !in installedPackageSet
          SelectedPackageRow(
            label = app.displayLabel,
            packageName = app.appId.packageName,
            staleNote = if (isMissing) stringResource(R.string.setup_stale_app_badge) else null,
            onRemove = { onRemoveApp(app.appId) },
            testTag = "setup_selected_restricted_${app.appId.packageName}",
          )
        }
      }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    OutlinedTextField(
      value = editor.customRestrictedLabel,
      onValueChange = onCustomLabelChange,
      label = { Text(stringResource(R.string.setup_custom_app_label_hint)) },
      singleLine = true,
      modifier = Modifier.fillMaxWidth().testTag("setup_custom_restricted_label_input"),
    )
    OutlinedTextField(
      value = editor.customRestrictedPackage,
      onValueChange = onCustomPackageChange,
      label = { Text(stringResource(R.string.setup_custom_app_package_hint)) },
      singleLine = true,
      modifier = Modifier.fillMaxWidth().testTag("setup_custom_restricted_package_input"),
    )
    OutlinedButton(
      onClick = onAddCustom,
      modifier =
        Modifier.fillMaxWidth()
          .minimumInteractiveComponentSize()
          .heightIn(min = 48.dp)
          .testTag("setup_add_custom_restricted_button"),
    ) {
      Text(stringResource(R.string.setup_add_restricted_custom))
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AllowlistSelectionCard(
  editor: DraftEditorState,
  onSearchChange: (String) -> Unit,
  onToggleInstalledApp: (InstalledAppInfo) -> Unit,
  onCustomLabelChange: (String) -> Unit,
  onCustomPackageChange: (String) -> Unit,
  onCategoryChange: (AllowlistCategory) -> Unit,
  onAddCustom: () -> Unit,
  onRemoveApp: (AppId) -> Unit,
  onShowHelp: (() -> Unit)? = null,
) {
  val installedFiltered =
    editor.installedApps
      .sortedByDescending { it.isSystemEssential }
      .filter { app ->
        val query = editor.allowlistSearchQuery.trim()
        query.isEmpty() ||
          app.displayLabel.contains(query, ignoreCase = true) ||
          app.appId.packageName.contains(query, ignoreCase = true)
      }

  val installedPackageSet = editor.installedApps.map { it.appId }.toSet()

  MizanSurfaceCard(modifier = Modifier.testTag("setup_allowlist_card")) {
    SectionHeaderWithHelp(
      title = stringResource(R.string.setup_allowlist_title),
      onShowHelp = onShowHelp,
      helpTestTag = "setup_allowlist_help_button",
    )
    Text(
      text = stringResource(R.string.setup_allowlist_desc),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (editor.installedApps.isNotEmpty()) {
      OutlinedTextField(
        value = editor.allowlistSearchQuery,
        onValueChange = onSearchChange,
        label = { Text(stringResource(R.string.setup_search_apps_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("setup_allowlist_search_input"),
      )
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        installedFiltered.take(20).forEach { app ->
          val selected = editor.allowedApps.any { it.appId == app.appId }
          InstalledAppSelectionRow(
            app = app,
            selected = selected,
            onToggle = { onToggleInstalledApp(app) },
            testTag = "setup_allowed_app_${app.appId.packageName}",
          )
        }
      }
    }

    val manualOrMissingAllowed =
      editor.allowedApps.filter { selected -> selected.appId !in installedPackageSet }
    if (manualOrMissingAllowed.isNotEmpty()) {
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        manualOrMissingAllowed.forEach { app ->
          val isMissing = editor.installedAppsLoaded && app.appId !in installedPackageSet
          SelectedPackageRow(
            label = app.displayLabel,
            packageName = app.appId.packageName,
            staleNote = if (isMissing) stringResource(R.string.setup_stale_app_badge) else null,
            onRemove = { onRemoveApp(app.appId) },
            testTag = "setup_selected_allowed_${app.appId.packageName}",
          )
        }
      }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      AllowlistCategory.entries.forEach { category ->
        FilterChip(
          selected = editor.customAllowedCategory == category,
          onClick = { onCategoryChange(category) },
          label = { Text(category.name.replace('_', ' ').lowercase()) },
          modifier = Modifier.minimumInteractiveComponentSize(),
        )
      }
    }
    OutlinedTextField(
      value = editor.customAllowedLabel,
      onValueChange = onCustomLabelChange,
      label = { Text(stringResource(R.string.setup_custom_app_label_hint)) },
      singleLine = true,
      modifier = Modifier.fillMaxWidth().testTag("setup_custom_allowed_label_input"),
    )
    OutlinedTextField(
      value = editor.customAllowedPackage,
      onValueChange = onCustomPackageChange,
      label = { Text(stringResource(R.string.setup_custom_app_package_hint)) },
      singleLine = true,
      modifier = Modifier.fillMaxWidth().testTag("setup_custom_allowed_package_input"),
    )
    OutlinedButton(
      onClick = onAddCustom,
      modifier =
        Modifier.fillMaxWidth()
          .minimumInteractiveComponentSize()
          .heightIn(min = 48.dp)
          .testTag("setup_add_custom_allowed_button"),
    ) {
      Text(stringResource(R.string.setup_add_allowed_custom))
    }
  }
}

@Composable
private fun InstalledAppSelectionRow(
  app: InstalledAppInfo,
  selected: Boolean,
  onToggle: () -> Unit,
  testTag: String,
) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .minimumInteractiveComponentSize()
        .semantics(mergeDescendants = true) {},
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Checkbox(
      checked = selected,
      onCheckedChange = { onToggle() },
      modifier = Modifier.minimumInteractiveComponentSize().testTag(testTag),
    )
    InstalledAppRowIcon(
      packageName = app.appId.packageName,
      contentDescription = app.displayLabel,
    )
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = app.displayLabel,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text =
          if (app.isSystemEssential) {
            "${app.appId.packageName} · Essential tool"
          } else {
            app.appId.packageName
          },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun SelectedPackageRow(
  label: String,
  packageName: String,
  staleNote: String?,
  onRemove: () -> Unit,
  testTag: String,
) {
  Row(
    modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag(testTag),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = "$label ($packageName)",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
      )
      if (staleNote != null) {
        Text(
          text = staleNote,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.error,
        )
      }
    }
    IconButton(
      onClick = onRemove,
      modifier = Modifier.minimumInteractiveComponentSize(),
    ) {
      Icon(
        imageVector = Icons.Outlined.Close,
        contentDescription = "Remove $label",
      )
    }
  }
}

@Composable
private fun InstalledAppRowIcon(
  packageName: String,
  contentDescription: String,
) {
  val context = LocalContext.current
  val iconBitmap by
    produceState<ImageBitmap?>(initialValue = null, key1 = packageName) {
      value =
        withContext(Dispatchers.IO) {
          runCatching {
            drawableToImageBitmap(context.packageManager.getApplicationIcon(packageName))
          }.getOrNull()
        }
    }

  if (iconBitmap != null) {
    Image(
      bitmap = iconBitmap!!,
      contentDescription = contentDescription,
      modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)),
    )
  } else {
    Surface(
      modifier = Modifier.size(32.dp),
      shape = RoundedCornerShape(8.dp),
      color = MaterialTheme.colorScheme.secondaryContainer,
      contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
      Icon(
        imageVector = Icons.Outlined.Apps,
        contentDescription = stringResource(R.string.cd_app_icon_fallback),
        modifier = Modifier.padding(6.dp),
      )
    }
  }
}

private fun drawableToImageBitmap(drawable: Drawable): ImageBitmap? {
  if (drawable is BitmapDrawable && drawable.bitmap != null && !drawable.bitmap.isRecycled) {
    return drawable.bitmap.asImageBitmap()
  }
  val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 72
  val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 72
  val bitmap =
    Bitmap.createBitmap(width.coerceAtMost(144), height.coerceAtMost(144), Bitmap.Config.ARGB_8888)
  val canvas = Canvas(bitmap)
  drawable.setBounds(0, 0, canvas.width, canvas.height)
  drawable.draw(canvas)
  return bitmap.asImageBitmap()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScheduleBuilderCard(
  editor: DraftEditorState,
  onToggleDay: (DomainDayOfWeek) -> Unit,
  onAdjustStart: (Int) -> Unit,
  onAdjustEnd: (Int) -> Unit,
  onAddWindow: () -> Unit,
  onRemoveWindow: (Int) -> Unit,
  onShowHelp: (() -> Unit)? = null,
) {
  MizanSurfaceCard(modifier = Modifier.testTag("setup_schedule_card")) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Row(
        modifier = Modifier.weight(1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
          imageVector = Icons.Outlined.Schedule,
          contentDescription = stringResource(R.string.cd_schedule_icon),
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(20.dp),
        )
        Text(
          text = stringResource(R.string.setup_schedule_title),
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
      if (onShowHelp != null) {
        ContextualHelpButton(
          onClick = onShowHelp,
          contentDescription = stringResource(R.string.guide_contextual_help_cd),
          testTag = "setup_schedule_help_button",
        )
      }
    }
    Text(
      text = stringResource(R.string.setup_schedule_desc),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      DomainDayOfWeek.entries.forEach { day ->
        FilterChip(
          selected = day in editor.windowDraft.daysOfWeek,
          onClick = { onToggleDay(day) },
          label = { Text(WallClockFormatting.formatShortDay(day)) },
          modifier =
            Modifier.minimumInteractiveComponentSize()
              .testTag("setup_day_${day.name.lowercase()}"),
        )
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "Start: ${WallClockFormatting.formatTime(editor.windowDraft.startTime)}",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag("setup_schedule_start_text"),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(
          onClick = { onAdjustStart(-30) },
          modifier = Modifier.minimumInteractiveComponentSize().testTag("setup_start_minus_30"),
        ) {
          Text("-30m")
        }
        TextButton(
          onClick = { onAdjustStart(30) },
          modifier = Modifier.minimumInteractiveComponentSize().testTag("setup_start_plus_30"),
        ) {
          Text("+30m")
        }
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "End: ${WallClockFormatting.formatTime(editor.windowDraft.endTime)}",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag("setup_schedule_end_text"),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(
          onClick = { onAdjustEnd(-30) },
          modifier = Modifier.minimumInteractiveComponentSize().testTag("setup_end_minus_30"),
        ) {
          Text("-30m")
        }
        TextButton(
          onClick = { onAdjustEnd(30) },
          modifier = Modifier.minimumInteractiveComponentSize().testTag("setup_end_plus_30"),
        ) {
          Text("+30m")
        }
      }
    }

    OutlinedButton(
      onClick = onAddWindow,
      modifier =
        Modifier.fillMaxWidth()
          .minimumInteractiveComponentSize()
          .heightIn(min = 48.dp)
          .testTag("setup_add_schedule_window_button"),
    ) {
      Text(stringResource(R.string.setup_schedule_add_button))
    }

    if (editor.scheduleWindows.isNotEmpty()) {
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      editor.scheduleWindows.forEachIndexed { index, window ->
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            text = WallClockFormatting.formatScheduleWindow(window),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).testTag("setup_window_item_$index"),
          )
          IconButton(
            onClick = { onRemoveWindow(index) },
            modifier =
              Modifier.minimumInteractiveComponentSize()
                .testTag("setup_remove_window_$index"),
          ) {
            Icon(
              imageVector = Icons.Outlined.Close,
              contentDescription = "Remove schedule window ${index + 1}",
            )
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AllowanceSelectionCard(
  allowanceMinutes: Int,
  onSetAllowance: (Int) -> Unit,
  onAdjustAllowance: (Int) -> Unit,
  onShowHelp: (() -> Unit)? = null,
) {
  MizanSurfaceCard(modifier = Modifier.testTag("setup_allowance_card")) {
    SectionHeaderWithHelp(
      title = stringResource(R.string.setup_allowance_title),
      onShowHelp = onShowHelp,
      helpTestTag = "setup_allowance_help_button",
    )
    Text(
      text = stringResource(R.string.setup_allowance_desc),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = "Configured shared allowance: $allowanceMinutes minutes / day",
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.testTag("setup_allowance_value_text"),
    )
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      listOf(15, 30, 45, 60, 90).forEach { preset ->
        FilterChip(
          selected = allowanceMinutes == preset,
          onClick = { onSetAllowance(preset) },
          label = { Text("${preset}m") },
          modifier =
            Modifier.minimumInteractiveComponentSize().testTag("setup_allowance_preset_$preset"),
        )
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      OutlinedButton(
        onClick = { onAdjustAllowance(-5) },
        modifier =
          Modifier.weight(1f)
            .minimumInteractiveComponentSize()
            .heightIn(min = 48.dp)
            .testTag("setup_allowance_minus_5"),
      ) {
        Text("-5 min")
      }
      OutlinedButton(
        onClick = { onAdjustAllowance(5) },
        modifier =
          Modifier.weight(1f)
            .minimumInteractiveComponentSize()
            .heightIn(min = 48.dp)
            .testTag("setup_allowance_plus_5"),
      ) {
        Text("+5 min")
      }
    }
  }
}

@Composable
internal fun WebsiteFilteringSetupCard(
  editor: DraftEditorState,
  onToggleEnabled: (Boolean) -> Unit,
  onDomainInputChange: (String) -> Unit,
  onAddBlockedDomain: () -> Unit,
  onAddAllowedDomain: () -> Unit,
  onRemoveDomain: (com.example.domain.model.DomainName, com.example.domain.model.DomainRuleType) -> Unit,
  onToggleCategory: (com.example.domain.model.DomainCategory) -> Unit,
  onShowHelp: (() -> Unit)? = null,
) {
  MizanSurfaceCard(modifier = Modifier.testTag("setup_website_filtering_card")) {
    SectionHeaderWithHelp(
      title = stringResource(R.string.setup_website_title),
      onShowHelp = onShowHelp,
      helpTestTag = "setup_website_help_button",
    )
    Text(
      text = stringResource(R.string.setup_website_desc),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Row(
      modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
        text = stringResource(R.string.setup_website_toggle_label),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f).padding(end = 12.dp),
      )
      androidx.compose.material3.Switch(
        checked = editor.websiteFilteringEnabled,
        onCheckedChange = onToggleEnabled,
        modifier =
          Modifier.minimumInteractiveComponentSize().testTag("setup_website_toggle_switch"),
      )
    }

    OutlinedTextField(
      value = editor.customDomainInput,
      onValueChange = onDomainInputChange,
      label = { Text(stringResource(R.string.setup_website_domain_hint)) },
      singleLine = true,
      modifier = Modifier.fillMaxWidth().testTag("setup_website_domain_input"),
    )

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      OutlinedButton(
        onClick = onAddBlockedDomain,
        modifier =
          Modifier.weight(1f)
            .minimumInteractiveComponentSize()
            .heightIn(min = 48.dp)
            .testTag("setup_website_add_block_button"),
      ) {
        Text(stringResource(R.string.setup_website_add_block_button))
      }
      OutlinedButton(
        onClick = onAddAllowedDomain,
        modifier =
          Modifier.weight(1f)
            .minimumInteractiveComponentSize()
            .heightIn(min = 48.dp)
            .testTag("setup_website_add_allow_button"),
      ) {
        Text(stringResource(R.string.setup_website_add_allow_button))
      }
    }

    if (editor.blockedDomains.isNotEmpty()) {
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        editor.blockedDomains
          .sortedWith(compareBy({ it.ruleType.name }, { it.domain.value }))
          .forEach { entry ->
            val typeTag =
              if (entry.ruleType == com.example.domain.model.DomainRuleType.BLOCK) {
                "Block"
              } else {
                "Allow Exception"
              }
            Row(
              modifier =
                Modifier.fillMaxWidth()
                  .testTag(
                    "setup_website_domain_item_${entry.domain.value}_${entry.ruleType.name.lowercase()}"
                  ),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Text(
                text = "[$typeTag] ${entry.domain.value}",
                style = MaterialTheme.typography.bodySmall,
                color =
                  if (entry.ruleType == com.example.domain.model.DomainRuleType.BLOCK) {
                    MaterialTheme.colorScheme.onSurface
                  } else {
                    MaterialTheme.colorScheme.primary
                  },
                modifier = Modifier.weight(1f),
              )
              IconButton(
                onClick = { onRemoveDomain(entry.domain, entry.ruleType) },
                modifier =
                  Modifier.minimumInteractiveComponentSize()
                    .testTag(
                      "setup_website_remove_${entry.domain.value}_${entry.ruleType.name.lowercase()}"
                    ),
              ) {
                Icon(
                  imageVector = Icons.Outlined.Close,
                  contentDescription = "Remove ${entry.domain.value}",
                )
              }
            }
          }
      }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text(
      text = stringResource(R.string.setup_website_categories_title),
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurface,
    )

    val categories =
      listOf(
        com.example.domain.model.DomainCategory.SOCIAL_MEDIA to
          stringResource(R.string.setup_website_category_social),
        com.example.domain.model.DomainCategory.ADULT_CONTENT to
          stringResource(R.string.setup_website_category_adult),
      )

    categories.forEach { (category, label) ->
      val meta = editor.datasetMetadata.firstOrNull { it.category == category }
      val evalNow =
        if (editor.datasetEvalEpochMillis > 0L) {
          editor.datasetEvalEpochMillis
        } else {
          System.currentTimeMillis()
        }
      val statusNote =
        if (meta == null) {
          "Unavailable — remote updates disabled (no signed feed configured)"
        } else {
          when (
            com.example.domain.model.DatasetStatusEvaluator.evaluate(
              metadata = meta,
              isUpdating = false,
              nowEpochMillis = evalNow,
            )
          ) {
            com.example.domain.model.DatasetAvailabilityStatus.AVAILABLE ->
              "Available · v${meta.versionCode} (${meta.recordCount} domains verified)"
            com.example.domain.model.DatasetAvailabilityStatus.UPDATING ->
              "Updating…"
            com.example.domain.model.DatasetAvailabilityStatus.OUTDATED ->
              "Outdated · cached v${meta.versionCode} (${meta.recordCount} domains still active)"
            com.example.domain.model.DatasetAvailabilityStatus.FAILED ->
              if (meta.isUsable) {
                "Failed — cached v${meta.versionCode} (${meta.recordCount} domains active): ${meta.lastError ?: "update rejected"}"
              } else {
                "Failed — ${meta.lastError ?: "dataset verification rejected"}"
              }
            com.example.domain.model.DatasetAvailabilityStatus.UNAVAILABLE ->
              "Unavailable — ${meta.lastError ?: "no verified dataset cached locally"}"
          }
        }

      Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Checkbox(
          checked = category in editor.blockedDomainCategories,
          onCheckedChange = { onToggleCategory(category) },
          modifier =
            Modifier.minimumInteractiveComponentSize()
              .testTag("setup_website_category_${category.name.lowercase()}"),
        )
        Column(modifier = Modifier.padding(start = 6.dp)) {
          Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = statusNote,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

@Composable
internal fun AccountabilityPinSetupCard(
  editor: DraftEditorState,
  onToggleRequirePin: (Boolean) -> Unit,
  onShowHelp: (() -> Unit)? = null,
) {
  MizanSurfaceCard(modifier = Modifier.testTag("setup_pin_card")) {
    SectionHeaderWithHelp(
      title = stringResource(R.string.setup_pin_title),
      onShowHelp = onShowHelp,
      helpTestTag = "setup_pin_help_button",
    )
    Row(
      modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
        text = stringResource(R.string.setup_pin_toggle_label),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f).padding(end = 12.dp),
      )
      androidx.compose.material3.Switch(
        checked = editor.requirePinForEmergencyTermination,
        onCheckedChange = onToggleRequirePin,
        modifier =
          Modifier.minimumInteractiveComponentSize().testTag("setup_pin_toggle_switch"),
      )
    }
    Text(
      text =
        if (editor.isPinConfigured) {
          stringResource(R.string.setup_pin_configured_note)
        } else {
          stringResource(R.string.setup_pin_missing_note)
        },
      style = MaterialTheme.typography.bodySmall,
      color =
        if (editor.requirePinForEmergencyTermination && !editor.isPinConfigured) {
          MaterialTheme.colorScheme.error
        } else {
          MaterialTheme.colorScheme.onSurfaceVariant
        },
      modifier = Modifier.testTag("setup_pin_status_note"),
    )
    Text(
      text = stringResource(R.string.setup_pin_disclosure_note),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}



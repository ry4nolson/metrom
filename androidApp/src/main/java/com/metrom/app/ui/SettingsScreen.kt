package com.metrom.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.metrom.app.BuildConfig
import com.metrom.app.MetronomeViewModel
import com.metrom.app.ui.theme.Ash
import com.metrom.app.ui.theme.BackgroundBottom
import com.metrom.app.ui.theme.BackgroundTop
import com.metrom.app.ui.theme.Bone
import com.metrom.app.ui.theme.Ember
import com.metrom.app.ui.theme.Ink
import com.metrom.app.ui.theme.InkElevated
import com.metrom.app.ui.theme.InkLine
import com.metrom.app.ui.theme.Mist
import com.metrom.shared.domain.AccentNote
import com.metrom.shared.domain.TimeSignature
import com.metrom.shared.practice.MetronomeUiState
import com.metrom.shared.theme.ColorTheme

@Composable
fun SettingsScreen(
    state: MetronomeUiState,
    viewModel: MetronomeViewModel,
    colorTheme: ColorTheme,
    savedThemes: List<ColorTheme>,
    customMeters: List<TimeSignature>,
    openMeters: Boolean = false,
    onClose: () -> Unit,
) {
    var editingTheme by remember { mutableStateOf(false) }
    var editingMeters by remember { mutableStateOf(openMeters) }
    if (editingTheme) {
        ThemeEditorScreen(
            theme = colorTheme,
            onSlot = viewModel::updateThemeSlot,
            onSave = viewModel::saveNamedTheme,
            onClose = { editingTheme = false }
        )
        return
    }
    if (editingMeters) {
        CustomMetersScreen(
            current = state.timeSignature,
            saved = customMeters,
            onAdd = viewModel::addCustomMeter,
            onSelect = viewModel::setTimeSignature,
            onDelete = viewModel::deleteCustomMeter,
            onClose = { editingMeters = false }
        )
        return
    }

    BackHandler(onBack = onClose)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(BackgroundTop, Ink, BackgroundBottom)
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = Bone
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "SETTINGS",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Bone
                )
                Text(
                    text = state.tone.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = Ash
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            SettingsSection(title = "SOUND") {
                LabeledChipRow(label = "CLICK") {
                    state.toneOptions.forEach { tone ->
                        ChoiceChip(
                            label = tone.label,
                            selected = state.tone.id == tone.id,
                            onClick = { viewModel.setTone(tone) }
                        )
                    }
                }
                if (state.tone.supportsPitchAccent) {
                    LabeledChipRow(label = "ONE") {
                        AccentNote.entries.forEach { note ->
                            ChoiceChip(
                                label = note.label,
                                selected = state.accentNote == note,
                                onClick = { viewModel.setAccentNote(note) }
                            )
                        }
                    }
                    LabeledChipRow(label = "OTHERS") {
                        AccentNote.entries.forEach { note ->
                            ChoiceChip(
                                label = note.label,
                                selected = state.restNote == note,
                                onClick = { viewModel.setRestNote(note) }
                            )
                        }
                    }
                }
            }

            SettingsSection(title = "LEVEL") {
                VolumeRow(state, viewModel)
                SettingsToggleRow(
                    title = "Mute",
                    subtitle = "Silence the click without stopping",
                    icon = if (state.muted) {
                        Icons.AutoMirrored.Rounded.VolumeOff
                    } else {
                        Icons.AutoMirrored.Rounded.VolumeUp
                    },
                    checked = state.muted,
                    onToggle = viewModel::toggleMute
                )
            }

            SettingsSection(title = "FEEL") {
                SettingsToggleRow(
                    title = "Haptics",
                    subtitle = "Vibrate on each click",
                    icon = Icons.Rounded.Vibration,
                    checked = state.haptics,
                    onToggle = viewModel::toggleHaptics
                )
            }

            SettingsSection(title = "METER") {
                Text(
                    "Odd meters like 7/4 or 11/8",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ash
                )
                if (customMeters.isNotEmpty()) {
                    LabeledChipRow(label = "SAVED") {
                        customMeters.forEach { sig ->
                            ChoiceChip(
                                label = sig.label,
                                selected = state.timeSignature == sig,
                                onClick = { viewModel.setTimeSignature(sig) }
                            )
                        }
                    }
                }
                ChoiceChip(
                    label = "Custom meters",
                    selected = false,
                    onClick = { editingMeters = true }
                )
            }

            SettingsSection(title = "LOOK") {
                ThemePickerRow(
                    current = colorTheme,
                    saved = savedThemes,
                    onSelect = viewModel::selectColorTheme,
                    onEditCustom = {
                        viewModel.customizeCurrentTheme()
                        editingTheme = true
                    },
                    onDeleteSaved = viewModel::deleteSavedTheme
                )
            }

            SettingsSection(title = "ABOUT") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(InkElevated)
                        .border(1.dp, InkLine, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Text("Metrom", style = MaterialTheme.typography.titleLarge, color = Bone)
                    Text(
                        "Version ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ash
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = Ash,
            modifier = Modifier.padding(start = 2.dp)
        )
        content()
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(InkElevated)
            .border(1.dp, InkLine, RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (checked) Ember else Ash,
            modifier = Modifier.padding(end = 12.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Bone)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Ash)
        }
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Ink,
                checkedTrackColor = Ember,
                uncheckedThumbColor = Mist,
                uncheckedTrackColor = InkLine,
                uncheckedBorderColor = InkLine
            )
        )
    }
}

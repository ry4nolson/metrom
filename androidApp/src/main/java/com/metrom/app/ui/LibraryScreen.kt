package com.metrom.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrom.app.EditorNav
import com.metrom.app.MetronomeViewModel
import com.metrom.app.ui.theme.Ash
import com.metrom.app.ui.theme.BackgroundBottom
import com.metrom.app.ui.theme.BackgroundTop
import com.metrom.app.ui.theme.Bone
import com.metrom.app.ui.theme.Ember
import com.metrom.app.ui.theme.EmberSoft
import com.metrom.app.ui.theme.Ink
import com.metrom.app.ui.theme.InkElevated
import com.metrom.app.ui.theme.InkLine
import com.metrom.shared.library.Section
import com.metrom.shared.library.Setlist
import com.metrom.shared.library.Song
import com.metrom.shared.practice.MetronomeUiState
import com.metrom.shared.practice.SetlistSlot

enum class LibraryTab { Sections, Songs, Setlists }

@Composable
fun LibraryScreen(
    state: MetronomeUiState,
    viewModel: MetronomeViewModel,
    onClose: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(LibraryTab.Sections) }
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
                    text = "LIBRARY",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Bone
                )
                Text(
                    text = when (tab) {
                        LibraryTab.Sections ->
                            if (state.sections.isEmpty()) "bookmark to save" else "${state.sections.size} saved"
                        LibraryTab.Songs ->
                            if (state.songs.isEmpty()) "build a song" else "${state.songs.size} saved"
                        LibraryTab.Setlists ->
                            if (state.setlists.isEmpty()) "build a set" else "${state.setlists.size} saved"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = Ash
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ChoiceChip(label = "SECTIONS", selected = tab == LibraryTab.Sections, onClick = { tab = LibraryTab.Sections })
            ChoiceChip(label = "SONGS", selected = tab == LibraryTab.Songs, onClick = { tab = LibraryTab.Songs })
            ChoiceChip(label = "SETLISTS", selected = tab == LibraryTab.Setlists, onClick = { tab = LibraryTab.Setlists })
        }

        when (tab) {
            LibraryTab.Sections -> SectionsTab(state, viewModel, onArmAndBounce = onClose)
            LibraryTab.Songs -> SongsTab(state, viewModel, onArmAndBounce = onClose)
            LibraryTab.Setlists -> SetlistsTab(state, viewModel, onArmAndBounce = onClose)
        }
    }
}

@Composable
private fun SectionsTab(
    state: MetronomeUiState,
    viewModel: MetronomeViewModel,
    onArmAndBounce: () -> Unit,
) {
    var renaming by remember { mutableStateOf<Section?>(null) }
    var renameText by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var createName by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ChoiceChip(
            label = "New section",
            selected = false,
            onClick = {
                createName = Section.autoName(state.bpm, state.timeSignature, state.subdivision)
                creating = true
            }
        )
        if (state.activeSavedSectionId != null) {
            ChoiceChip(
                label = "Update active",
                selected = false,
                onClick = viewModel::updateActiveSection
            )
        }
        if (state.sections.isEmpty()) {
            Text(
                "Bookmark tempo, meter, swing, and practice settings. Long-press to rename.",
                style = MaterialTheme.typography.bodyMedium,
                color = Ash
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.sections, key = { it.id }) { section ->
                    SectionLibraryRow(
                        section = section,
                        selected = state.activeSavedSectionId == section.id,
                        onLoad = {
                            viewModel.loadSection(section)
                            onArmAndBounce()
                        },
                        onEdit = { viewModel.openSectionEditor(section.id, EditorNav.Origin.Library) },
                        onDelete = { viewModel.deleteSection(section) },
                        onRename = {
                            renaming = section
                            renameText = section.displayName()
                        }
                    )
                }
            }
        }
    }

    if (creating) {
        NameDialog(
            title = "Save section",
            value = createName,
            onValueChange = { createName = it },
            onConfirm = {
                viewModel.saveCurrentSection(createName)
                creating = false
            },
            onDismiss = { creating = false }
        )
    }

    renaming?.let { section ->
        NameDialog(
            title = "Rename section",
            value = renameText,
            onValueChange = { renameText = it },
            onConfirm = {
                viewModel.renameSection(section, renameText)
                renaming = null
            },
            onDismiss = { renaming = null }
        )
    }
}

@Composable
private fun SongsTab(
    state: MetronomeUiState,
    viewModel: MetronomeViewModel,
    onArmAndBounce: () -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var fromCurrent by remember { mutableStateOf(false) }
    var createName by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ChoiceChip(
            label = "New song",
            selected = false,
            onClick = {
                createName = "Song ${state.songs.size + 1}"
                fromCurrent = false
                creating = true
            }
        )
        ChoiceChip(
            label = "New song from current",
            selected = false,
            onClick = {
                createName = Section.autoName(state.bpm, state.timeSignature, state.subdivision)
                fromCurrent = true
                creating = true
            }
        )
        if (state.songs.isEmpty()) {
            Text(
                "A song is an ordered list of sections. Tap to load, edit to arrange.",
                style = MaterialTheme.typography.bodyMedium,
                color = Ash
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.songs, key = { it.id }) { song ->
                    SongLibraryRow(
                        song = song,
                        selected = state.activeSongId == song.id,
                        onLoad = {
                            viewModel.loadSong(song)
                            onArmAndBounce()
                        },
                        onEdit = { viewModel.openSongEditor(song.id) },
                        onDelete = { viewModel.deleteSong(song) }
                    )
                }
            }
        }
    }

    if (creating) {
        NameDialog(
            title = if (fromCurrent) "New song from current" else "New song",
            value = createName,
            onValueChange = { createName = it },
            onConfirm = {
                if (fromCurrent) viewModel.createSongFromCurrent(createName)
                else viewModel.createSong(createName)
                creating = false
            },
            onDismiss = { creating = false }
        )
    }
}

@Composable
private fun SetlistsTab(
    state: MetronomeUiState,
    viewModel: MetronomeViewModel,
    onArmAndBounce: () -> Unit,
) {
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var createName by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<Setlist?>(null) }
    var renameText by remember { mutableStateOf("") }
    val editing = state.setlists.firstOrNull { it.id == editingId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (editing != null) {
            ChoiceChip(
                label = "All setlists",
                selected = false,
                onClick = { editingId = null }
            )
            Text(
                editing.name,
                style = MaterialTheme.typography.titleLarge,
                color = Bone,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            ChoiceChip(
                label = "Add current as section",
                selected = false,
                onClick = { viewModel.addSectionFromCurrent(editing.id) }
            )
            val slots = state.setlistSlots(editing)
            if (slots.isEmpty()) {
                Text(
                    "Add the current tempo, meter, and tone as a section. Open-ended until you set a bar count.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ash
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(slots, key = { index, slot -> "${index}-${slot.section.id}" }) { index, slot ->
                        SectionEditorRow(
                            section = slot,
                            selected = state.inSetMode &&
                                state.activeSetlistId == editing.id &&
                                state.activeSectionIndex == index,
                            isFirst = index == 0,
                            isLast = index == slots.lastIndex,
                            onBars = { bars ->
                                viewModel.setSectionBars(editing.id, slot.section.id, bars)
                            },
                            onToggleAuto = {
                                viewModel.setSectionAutoAdvance(editing.id, slot.section.id, !slot.autoAdvance)
                            },
                            onMoveUp = { viewModel.moveSection(editing.id, index, index - 1) },
                            onMoveDown = { viewModel.moveSection(editing.id, index, index + 1) },
                            onRemove = { viewModel.removeSection(editing.id, slot.section.id) }
                        )
                    }
                }
            }
        } else {
            ChoiceChip(
                label = "New setlist",
                selected = false,
                onClick = {
                    createName = "Set ${state.setlists.size + 1}"
                    creating = true
                }
            )
            if (state.setlists.isEmpty()) {
                Text(
                    "Save an ordered set of songs. Tap to load, long-press to rename.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ash
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.setlists, key = { it.id }) { setlist ->
                        SetlistRow(
                            setlist = setlist,
                            slotCount = state.setlistSlots(setlist).size,
                            selected = state.activeSetlistId == setlist.id,
                            onLoad = {
                                viewModel.loadSetlist(setlist)
                                onArmAndBounce()
                            },
                            onEdit = { editingId = setlist.id },
                            onDelete = { viewModel.deleteSetlist(setlist.id) },
                            onRename = {
                                renaming = setlist
                                renameText = setlist.name
                            }
                        )
                    }
                }
            }
        }
    }

    if (creating) {
        NameDialog(
            title = "New setlist",
            value = createName,
            onValueChange = { createName = it },
            onConfirm = {
                viewModel.createSetlist(createName)
                creating = false
            },
            onDismiss = { creating = false }
        )
    }

    renaming?.let { setlist ->
        NameDialog(
            title = "Rename setlist",
            value = renameText,
            onValueChange = { renameText = it },
            onConfirm = {
                viewModel.renameSetlist(setlist.id, renameText)
                renaming = null
            },
            onDismiss = { renaming = null }
        )
    }
}

@Composable
private fun SectionLibraryRow(
    section: Section,
    selected: Boolean,
    onLoad: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Ember.copy(alpha = 0.14f) else Color.Transparent)
            .border(1.dp, if (selected) Ember.copy(alpha = 0.55f) else InkLine, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .pointerInput(section.id) {
                    detectTapGestures(
                        onTap = { onLoad() },
                        onLongPress = { onRename() }
                    )
                }
        ) {
            Text(
                sectionPrimaryLabel(section),
                style = MaterialTheme.typography.titleLarge,
                color = Bone,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                sectionSummary(section),
                style = MaterialTheme.typography.labelMedium,
                color = Ash,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Outlined.Edit, contentDescription = "Edit", tint = Ash)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Close, contentDescription = "Delete", tint = Ash)
        }
    }
}

@Composable
private fun SongLibraryRow(
    song: Song,
    selected: Boolean,
    onLoad: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val count = song.sectionRefs.size
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Ember.copy(alpha = 0.14f) else Color.Transparent)
            .border(1.dp, if (selected) Ember.copy(alpha = 0.55f) else InkLine, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onLoad)
        ) {
            Text(
                song.name,
                style = MaterialTheme.typography.titleLarge,
                color = Bone,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (count == 1) "1 section" else "$count sections",
                style = MaterialTheme.typography.labelMedium,
                color = Ash
            )
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Outlined.Edit, contentDescription = "Edit", tint = Ash)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Close, contentDescription = "Delete", tint = Ash)
        }
    }
}

@Composable
private fun SetlistRow(
    setlist: Setlist,
    slotCount: Int,
    selected: Boolean,
    onLoad: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit
) {
    val countLabel = if (slotCount == 1) "1 section" else "$slotCount sections"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Ember.copy(alpha = 0.14f) else Color.Transparent)
            .border(1.dp, if (selected) Ember.copy(alpha = 0.55f) else InkLine, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .pointerInput(setlist.id) {
                    detectTapGestures(
                        onTap = { onLoad() },
                        onLongPress = { onRename() }
                    )
                }
        ) {
            Text(
                setlist.name,
                style = MaterialTheme.typography.titleLarge,
                color = Bone,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                countLabel,
                style = MaterialTheme.typography.labelMedium,
                color = Ash,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Outlined.Edit, contentDescription = "Edit", tint = Ash)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Close, contentDescription = "Delete", tint = Ash)
        }
    }
}

@Composable
private fun SectionEditorRow(
    section: SetlistSlot,
    selected: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onBars: (Int) -> Unit,
    onToggleAuto: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    val barOptions = listOf(0, 2, 4, 8, 16, 32)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Ember.copy(alpha = 0.14f) else Color.Transparent)
            .border(1.dp, if (selected) Ember.copy(alpha = 0.55f) else InkLine, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    section.section.displayName(),
                    style = MaterialTheme.typography.titleLarge,
                    color = Bone,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    sectionLengthLabel(section),
                    style = MaterialTheme.typography.labelMedium,
                    color = Ash,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onMoveUp, enabled = !isFirst) {
                Icon(
                    Icons.Filled.ExpandLess,
                    contentDescription = "Move up",
                    tint = if (isFirst) InkLine else Ash
                )
            }
            IconButton(onClick = onMoveDown, enabled = !isLast) {
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = "Move down",
                    tint = if (isLast) InkLine else Ash
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = "Remove", tint = Ash)
            }
        }
        LabeledChipRow(label = "BARS") {
            barOptions.forEach { bars ->
                ChoiceChip(
                    label = if (bars == 0) "Open" else bars.toString(),
                    selected = section.section.bars == bars,
                    onClick = { onBars(bars) }
                )
            }
        }
        if (section.section.bars > 0) {
            ChoiceChip(
                label = "Auto",
                selected = section.autoAdvance,
                onClick = onToggleAuto
            )
        }
    }
}

private fun sectionLengthLabel(slot: SetlistSlot): String = when {
    slot.section.bars <= 0 -> "open-ended"
    slot.autoAdvance -> "${slot.section.bars} bars · auto"
    else -> "${slot.section.bars} bars"
}

@Composable
private fun NameDialog(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = Bone) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                label = { Text("Name") }
            )
        },
        confirmButton = {
            Text(
                "SAVE",
                color = EmberSoft,
                modifier = Modifier
                    .clickable(onClick = onConfirm)
                    .padding(8.dp)
            )
        },
        dismissButton = {
            Text(
                "CANCEL",
                color = Ash,
                modifier = Modifier
                    .clickable(onClick = onDismiss)
                    .padding(8.dp)
            )
        },
        containerColor = InkElevated
    )
}

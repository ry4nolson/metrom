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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.metrom.app.ui.theme.Ash
import com.metrom.app.ui.theme.BackgroundBottom
import com.metrom.app.ui.theme.BackgroundTop
import com.metrom.app.ui.theme.Bone
import com.metrom.app.ui.theme.Ink
import com.metrom.app.ui.theme.InkElevated
import com.metrom.app.ui.theme.InkLine
import com.metrom.app.ui.theme.Mist
import com.metrom.shared.domain.TimeSignature

@Composable
fun CustomMetersScreen(
    current: TimeSignature,
    saved: List<TimeSignature>,
    onAdd: (Int, Int) -> Unit,
    onSelect: (TimeSignature) -> Unit,
    onDelete: (TimeSignature) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    var beats by remember { mutableIntStateOf(current.beats.coerceIn(TimeSignature.MIN_BEATS, TimeSignature.MAX_BEATS)) }
    var noteValue by remember { mutableIntStateOf(current.noteValue.let { if (it in TimeSignature.NOTE_VALUES) it else 4 }) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BackgroundTop, Ink, BackgroundBottom)))
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
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Bone)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("CUSTOM METERS", style = MaterialTheme.typography.headlineMedium, color = Bone)
                Text("add odd meters like 7/4", style = MaterialTheme.typography.labelMedium, color = Ash)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("BEATS", style = MaterialTheme.typography.labelMedium, color = Ash)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconButton(
                    onClick = { beats = (beats - 1).coerceAtLeast(TimeSignature.MIN_BEATS) },
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(InkElevated)
                        .border(1.dp, InkLine, RoundedCornerShape(12.dp))
                ) {
                    Icon(Icons.Filled.Remove, contentDescription = "Fewer beats", tint = Mist)
                }
                Text(
                    beats.toString(),
                    style = MaterialTheme.typography.headlineMedium,
                    color = Bone,
                    modifier = Modifier.width(48.dp),
                    textAlign = TextAlign.Center
                )
                IconButton(
                    onClick = { beats = (beats + 1).coerceAtMost(TimeSignature.MAX_BEATS) },
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(InkElevated)
                        .border(1.dp, InkLine, RoundedCornerShape(12.dp))
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "More beats", tint = Mist)
                }
            }

            LabeledChipRow(label = "NOTE") {
                TimeSignature.NOTE_VALUES.forEach { note ->
                    ChoiceChip(
                        label = note.toString(),
                        selected = noteValue == note,
                        onClick = { noteValue = note }
                    )
                }
            }

            ChoiceChip(
                label = "Add $beats/$noteValue",
                selected = false,
                onClick = { onAdd(beats, noteValue) }
            )

            if (saved.isNotEmpty()) {
                LabeledChipRow(label = "SAVED") {
                    saved.forEach { sig ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ChoiceChip(
                                label = sig.label,
                                selected = current == sig,
                                onClick = { onSelect(sig) }
                            )
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Delete ${sig.label}",
                                tint = Ash,
                                modifier = Modifier
                                    .padding(start = 4.dp, end = 8.dp)
                                    .clickable { onDelete(sig) }
                            )
                        }
                    }
                }
            }
        }
    }
}

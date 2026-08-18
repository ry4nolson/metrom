package com.metrom.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.metrom.app.ui.theme.Ash
import com.metrom.app.ui.theme.BackgroundBottom
import com.metrom.app.ui.theme.BackgroundTop
import com.metrom.app.ui.theme.Bone
import com.metrom.app.ui.theme.Ember
import com.metrom.app.ui.theme.Ink
import com.metrom.app.ui.theme.InkElevated
import com.metrom.app.ui.theme.InkLine
import com.metrom.app.ui.theme.Mist
import com.metrom.app.ui.theme.hexColor
import com.metrom.app.ui.theme.toPalette
import com.metrom.shared.theme.ColorSlots
import com.metrom.shared.theme.ColorTheme

private const val THEME_COLUMNS = 3

@Composable
fun ThemePickerRow(
    current: ColorTheme,
    saved: List<ColorTheme>,
    onSelect: (String) -> Unit,
    onEditCustom: () -> Unit,
    onDeleteSaved: (String) -> Unit,
) {
    val selectedId = current.id
    val customPreview = if (selectedId == ColorTheme.CUSTOM_ID) current else ColorTheme.EMBER.asCustom()
    val themes = ColorTheme.PRESETS + saved + customPreview
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        themes.chunked(THEME_COLUMNS).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                row.forEach { theme ->
                    ThemeCard(
                        theme = theme,
                        selected = selectedId == theme.id,
                        showEdit = theme.id == ColorTheme.CUSTOM_ID,
                        onClick = { onSelect(theme.id) },
                        onEdit = onEditCustom.takeIf { theme.id == ColorTheme.CUSTOM_ID },
                        onDelete = onDeleteSaved.takeIf { theme.isSaved() }?.let { { it(theme.id) } },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(THEME_COLUMNS - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ThemeCard(
    theme: ColorTheme,
    selected: Boolean,
    showEdit: Boolean = false,
    onClick: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val palette = remember(theme) { theme.toPalette() }
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.ink)
            .border(
                1.dp,
                if (selected) palette.ember else palette.inkLine,
                shape
            )
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                theme.label,
                style = MaterialTheme.typography.labelMedium,
                color = palette.bone,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (showEdit && onEdit != null) {
                Icon(
                    Icons.Rounded.Tune,
                    contentDescription = "Edit custom theme",
                    tint = palette.ash,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable(onClick = onEdit)
                )
            }
            if (onDelete != null) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "Delete saved theme",
                    tint = palette.ash,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable(onClick = onDelete)
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(palette.pulse)
            )
            repeat(3) {
                Box(
                    modifier = Modifier
                        .size(11.dp)
                        .clip(CircleShape)
                        .background(palette.ember)
                )
            }
        }
        Text(
            "allegro 140",
            style = MaterialTheme.typography.labelSmall,
            color = palette.ash,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun ThemeEditorScreen(
    theme: ColorTheme,
    onSlot: (String, String) -> Unit,
    onSave: (String) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    var editingKey by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveName by remember(theme.id, theme.label) {
        mutableStateOf(if (theme.label == "Custom") "My theme" else theme.label)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(BackgroundTop, Ink, BackgroundBottom))
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
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Bone)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("CUSTOM THEME", style = MaterialTheme.typography.headlineMedium, color = Bone)
                Text("tap a swatch to mix", style = MaterialTheme.typography.labelMedium, color = Ash)
            }
            Text(
                "SAVE",
                style = MaterialTheme.typography.labelLarge,
                color = Ember,
                modifier = Modifier
                    .clickable { saving = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ColorSlots.ALL.groupBy { it.group }.forEach { (group, slots) ->
                Text(group, style = MaterialTheme.typography.labelMedium, color = Ash)
                slots.forEach { slot ->
                    val hex = theme.hex(slot.key)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(InkElevated)
                            .border(1.dp, InkLine, RoundedCornerShape(12.dp))
                            .clickable { editingKey = slot.key }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(hexColor(hex))
                                .border(1.dp, InkLine, CircleShape)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(slot.label, style = MaterialTheme.typography.titleLarge, color = Bone)
                            Text("#$hex", style = MaterialTheme.typography.labelMedium, color = Ash)
                        }
                    }
                }
            }
        }
    }

    editingKey?.let { key ->
        HsvPickerDialog(
            label = ColorSlots.ALL.first { it.key == key }.label,
            hex = theme.hex(key),
            onHex = { onSlot(key, it) },
            onDismiss = { editingKey = null }
        )
    }

    if (saving) {
        AlertDialog(
            onDismissRequest = { saving = false },
            title = { Text("Save theme") },
            text = {
                OutlinedTextField(
                    value = saveName,
                    onValueChange = { saveName = it.take(18) },
                    singleLine = true,
                    label = { Text("Name") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Bone,
                        unfocusedTextColor = Bone,
                        focusedBorderColor = Ember,
                        unfocusedBorderColor = InkLine,
                        focusedLabelColor = Ash,
                        unfocusedLabelColor = Ash,
                        cursorColor = Ember,
                    )
                )
            },
            confirmButton = {
                Text(
                    "SAVE",
                    color = Ember,
                    modifier = Modifier
                        .clickable {
                            onSave(saveName)
                            saving = false
                            onClose()
                        }
                        .padding(8.dp)
                )
            },
            dismissButton = {
                Text(
                    "CANCEL",
                    color = Ash,
                    modifier = Modifier
                        .clickable { saving = false }
                        .padding(8.dp)
                )
            },
            containerColor = InkElevated
        )
    }
}

@Composable
private fun HsvPickerDialog(
    label: String,
    hex: String,
    onHex: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var color by remember(hex) { mutableStateOf(hexColor(hex)) }
    var hexText by remember(hex) { mutableStateOf("#${colorHex(color)}") }
    var lockedHue by remember(hex) { mutableFloatStateOf(hsvOf(color)[0]) }
    val hsv = hsvOf(color)
    val hue = if (hsv[1] < 0.001f) lockedHue else hsv[0]
    val sat = hsv[1]
    val value = hsv[2]
    val rgb = rgbOf(color)
    val hexValid = ColorTheme.normalizeHex(hexText) != null
    val nextHex = colorHex(color)
    val sliderColors = SliderDefaults.colors(
        thumbColor = Ember,
        activeTrackColor = Ember,
        inactiveTrackColor = InkLine
    )
    val maxDialogHeight = LocalConfiguration.current.screenHeightDp.dp * 0.88f

    fun applyColor(next: Color) {
        color = next
        val nextHsv = hsvOf(next)
        if (nextHsv[1] >= 0.001f) lockedHue = nextHsv[0]
        hexText = "#${colorHex(next)}"
    }

    BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth()
                .heightIn(max = maxDialogHeight)
                .clip(RoundedCornerShape(18.dp))
                .background(InkElevated)
                .border(1.dp, InkLine, RoundedCornerShape(18.dp))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {}
                )
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(label, style = MaterialTheme.typography.titleLarge, color = Bone)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(color)
                    .border(1.dp, InkLine, RoundedCornerShape(12.dp))
            )
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = hexText,
                    onValueChange = { typed ->
                        hexText = typed
                        val parsed = ColorTheme.normalizeHex(typed)
                        if (parsed != null) {
                            val next = hexColor(parsed)
                            color = next
                            val nextHsv = hsvOf(next)
                            if (nextHsv[1] >= 0.001f) lockedHue = nextHsv[0]
                        }
                    },
                    singleLine = true,
                    label = { Text("HEX") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = if (hexValid) Bone else Ash,
                        unfocusedTextColor = if (hexValid) Bone else Ash,
                        focusedBorderColor = if (hexValid) Ember else Ash,
                        unfocusedBorderColor = if (hexValid) InkLine else Ash,
                        focusedLabelColor = Ash,
                        unfocusedLabelColor = Ash,
                        cursorColor = Ember,
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                SliderLabel("Hue", hue.roundToInt().toString())
                Slider(
                    value = hue,
                    onValueChange = {
                        lockedHue = it
                        applyColor(Color.hsv(it, sat, value))
                    },
                    valueRange = 0f..360f,
                    colors = sliderColors
                )
                SliderLabel("Saturation", "${(sat * 100f).roundToInt()}%")
                Slider(
                    value = sat,
                    onValueChange = { applyColor(Color.hsv(hue, it, value)) },
                    valueRange = 0f..1f,
                    colors = sliderColors
                )
                SliderLabel("Value", "${(value * 100f).roundToInt()}%")
                Slider(
                    value = value,
                    onValueChange = { applyColor(Color.hsv(hue, sat, it)) },
                    valueRange = 0f..1f,
                    colors = sliderColors
                )
                SliderLabel("Red", rgb[0].toString())
                Slider(
                    value = rgb[0].toFloat(),
                    onValueChange = {
                        applyColor(Color(it.roundToInt().coerceIn(0, 255), rgb[1], rgb[2]))
                    },
                    valueRange = 0f..255f,
                    steps = 254,
                    colors = sliderColors
                )
                SliderLabel("Green", rgb[1].toString())
                Slider(
                    value = rgb[1].toFloat(),
                    onValueChange = {
                        applyColor(Color(rgb[0], it.roundToInt().coerceIn(0, 255), rgb[2]))
                    },
                    valueRange = 0f..255f,
                    steps = 254,
                    colors = sliderColors
                )
                SliderLabel("Blue", rgb[2].toString())
                Slider(
                    value = rgb[2].toFloat(),
                    onValueChange = {
                        applyColor(Color(rgb[0], rgb[1], it.roundToInt().coerceIn(0, 255)))
                    },
                    valueRange = 0f..255f,
                    steps = 254,
                    colors = sliderColors
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "CANCEL",
                    color = Ash,
                    modifier = Modifier
                        .clickable(onClick = onDismiss)
                        .padding(8.dp)
                )
                Text(
                    "APPLY",
                    color = Ember,
                    modifier = Modifier
                        .clickable {
                            onHex(nextHex)
                            onDismiss()
                        }
                        .padding(8.dp)
                )
            }
        }
    }
}

@Composable
private fun SliderLabel(text: String, value: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = Mist)
        if (value != null) {
            Text(value, style = MaterialTheme.typography.labelMedium, color = Ash)
        }
    }
}

private fun colorHex(color: Color): String =
    color.toArgb().toUInt().toString(16).uppercase().takeLast(6)

private fun hsvOf(color: Color): FloatArray {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    return hsv
}

private fun rgbOf(color: Color): IntArray = intArrayOf(
    (color.red * 255f).roundToInt().coerceIn(0, 255),
    (color.green * 255f).roundToInt().coerceIn(0, 255),
    (color.blue * 255f).roundToInt().coerceIn(0, 255),
)

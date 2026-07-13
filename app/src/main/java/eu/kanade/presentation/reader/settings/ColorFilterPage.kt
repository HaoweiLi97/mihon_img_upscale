package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.graphics.alpha
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.Companion.ColorFilterMode
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

@Composable
internal fun ColumnScope.ColorFilterPage(screenModel: ReaderSettingsScreenModel) {
    val customBrightness by screenModel.preferences.customBrightness().collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.pref_custom_brightness),
        pref = screenModel.preferences.customBrightness(),
    )

    /*
     * Sets the brightness of the screen. Range is [-75, 100].
     * From -75 to -1 a semi-transparent black view is shown at the top with the minimum brightness.
     * From 1 to 100 it sets that value as brightness.
     * 0 sets system brightness and hides the overlay.
     */
    if (customBrightness) {
        val customBrightnessValue by screenModel.preferences.customBrightnessValue().collectAsState()
        SliderItem(
            value = customBrightnessValue,
            valueRange = -75..100,
            steps = 0,
            label = stringResource(MR.strings.pref_custom_brightness),
            onChange = { screenModel.preferences.customBrightnessValue().set(it) },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }

    val colorFilter by screenModel.preferences.colorFilter().collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.pref_custom_color_filter),
        pref = screenModel.preferences.colorFilter(),
    )
    if (colorFilter) {
        val colorFilterValue by screenModel.preferences.colorFilterValue().collectAsState()
        SliderItem(
            value = colorFilterValue.red,
            valueRange = 0..255,
            steps = 0,
            label = stringResource(MR.strings.color_filter_r_value),
            onChange = { newRValue ->
                screenModel.preferences.colorFilterValue().getAndSet {
                    getColorValue(it, newRValue, RED_MASK, 16)
                }
            },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        SliderItem(
            value = colorFilterValue.green,
            valueRange = 0..255,
            steps = 0,
            label = stringResource(MR.strings.color_filter_g_value),
            onChange = { newGValue ->
                screenModel.preferences.colorFilterValue().getAndSet {
                    getColorValue(it, newGValue, GREEN_MASK, 8)
                }
            },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        SliderItem(
            value = colorFilterValue.blue,
            valueRange = 0..255,
            steps = 0,
            label = stringResource(MR.strings.color_filter_b_value),
            onChange = { newBValue ->
                screenModel.preferences.colorFilterValue().getAndSet {
                    getColorValue(it, newBValue, BLUE_MASK, 0)
                }
            },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        SliderItem(
            value = colorFilterValue.alpha,
            valueRange = 0..255,
            steps = 0,
            label = stringResource(MR.strings.color_filter_a_value),
            onChange = { newAValue ->
                screenModel.preferences.colorFilterValue().getAndSet {
                    getColorValue(it, newAValue, ALPHA_MASK, 24)
                }
            },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )

        val colorFilterMode by screenModel.preferences.colorFilterMode().collectAsState()
        SettingsChipRow(MR.strings.pref_color_filter_mode) {
            ColorFilterMode.mapIndexed { index, it ->
                FilterChip(
                    selected = colorFilterMode == index,
                    onClick = { screenModel.preferences.colorFilterMode().set(index) },
                    label = { Text(stringResource(it.first)) },
                )
            }
        }
    }

    CheckboxItem(
        label = stringResource(MR.strings.pref_grayscale),
        pref = screenModel.preferences.grayscale(),
    )
    CheckboxItem(
        label = stringResource(MR.strings.pref_inverted_colors),
        pref = screenModel.preferences.invertedColors(),
    )

    val realCuganEnabled by screenModel.preferences.realCuganEnabled().collectAsState()

    CheckboxItem(
        label = stringResource(MR.strings.reader_image_enhancement),
        checked = realCuganEnabled,
        onClick = {
            screenModel.preferences.realCuganEnabled().set(!realCuganEnabled)
        },
    )
    if (realCuganEnabled) {
        val realCuganModel by screenModel.preferences.realCuganModel().collectAsState()
        val realCuganNoiseLevel by screenModel.preferences.realCuganNoiseLevel().collectAsState()
        val realCuganScale by screenModel.preferences.realCuganScale().collectAsState()
        val realCuganInputScale by screenModel.preferences.realCuganInputScale().collectAsState()

        SettingsChipRow(stringResource(MR.strings.reader_model)) {
            listOf(
                "Real-CUGAN SE",
                "Real-CUGAN Pro",
                "Real-ESRGAN",
                "Real-CUGAN Nose",
                "Waifu2x",
                "Waifu2x (Fast)",
                "W2xEX Universal Fast",
                "W2xEX Omni Mini",
                "W2xEX Omni Mini V2",
                "W2xEX Photo Small",
                "W2xEX Anime HQ",
                "W2xEX Photo HQ",
                "SPAN x2 ch48",
                "SPAN x2 ch52",
                "SPAN x4 ch48",
                "SPAN x4 ch52",
            ).mapIndexed { index, name ->
                FilterChip(
                    selected = realCuganModel == index,
                    onClick = { screenModel.preferences.realCuganModel().set(index) },
                    label = { Text(name) },
                )
            }
        }

        if (realCuganModel == 0 || realCuganModel == 1 || realCuganModel == 4 || realCuganModel == 5) {
            val levels = if (realCuganModel == 1) { // Pro only has no-denoise, denoise3x, conservative
                listOf(0 to stringResource(MR.strings.reader_none), 3 to "3x", 4 to stringResource(MR.strings.reader_conservative))
            } else if (realCuganModel == 4) { // Waifu2x
                listOf(0 to "1x", 1 to "2x", 2 to "3x")
            } else if (realCuganModel == 5) { // Waifu2x Fast (UpConv7)
                listOf(0 to stringResource(MR.strings.reader_none), 1 to "1x", 2 to "2x", 3 to "3x")
            } else { // SE
                listOf(0 to stringResource(MR.strings.reader_none), 1 to "1x", 2 to "2x", 3 to "3x", 4 to stringResource(MR.strings.reader_conservative))
            }
            
            SettingsChipRow(stringResource(MR.strings.reader_denoise_level)) {
                levels.map { (index, name) ->
                    FilterChip(
                        selected = realCuganNoiseLevel == index,
                        onClick = { screenModel.preferences.realCuganNoiseLevel().set(index) },
                        label = { Text(name) },
                    )
                }
            }
        }

        if (realCuganModel == 3 || realCuganModel == 4 || realCuganModel == 5 || realCuganModel in 6..9 || realCuganModel in 12..13) {
             SettingsChipRow(stringResource(MR.strings.reader_scale_factor)) {
                  FilterChip(
                      selected = true,
                      onClick = {},
                      label = { Text(stringResource(MR.strings.reader_scale_fixed_2x)) }
                  )
             }
        } else if (realCuganModel == 10 || realCuganModel == 11 || realCuganModel in 14..15) {
            SettingsChipRow(stringResource(MR.strings.reader_scale_factor)) {
                FilterChip(
                    selected = true,
                    onClick = {},
                    label = { Text("4x") },
                )
            }
        } else if (realCuganModel == 1) { // Pro only supports 2x, 3x
            SettingsChipRow(stringResource(MR.strings.reader_scale_factor)) {
                listOf(2, 3).map { scale ->
                    FilterChip(
                        selected = realCuganScale == scale,
                        onClick = { screenModel.preferences.realCuganScale().set(scale) },
                        label = { Text("${scale}x") },
                    )
                }
            }
        } else {
            SettingsChipRow(stringResource(MR.strings.reader_scale_factor)) {
                listOf(2, 3, 4).map { scale ->
                    FilterChip(
                        selected = realCuganScale == scale,
                        onClick = { screenModel.preferences.realCuganScale().set(scale) },
                        label = { Text("${scale}x") },
                    )
                }
            }
        }

        SettingsChipRow(stringResource(MR.strings.reader_preload_pages)) {
            listOf(1, 2, 3, 5, 8).map { size ->
                val realCuganPreloadSize by screenModel.preferences.realCuganPreloadSize().collectAsState()
                FilterChip(
                    selected = realCuganPreloadSize == size,
                    onClick = { screenModel.preferences.realCuganPreloadSize().set(size) },
                    label = { Text(stringResource(MR.strings.reader_preload_pages_value, size)) },
                )
            }
        }

        SettingsChipRow(stringResource(MR.strings.reader_gpu_performance_mode)) {
            val performanceMode by screenModel.preferences.realCuganPerformanceMode().collectAsState()
            listOf(
                0 to stringResource(MR.strings.reader_gpu_performance_high),
                1 to stringResource(MR.strings.reader_gpu_performance_balanced),
                2 to stringResource(MR.strings.reader_gpu_performance_power_saving),
            ).map { (value, name) ->
                FilterChip(
                    selected = performanceMode == value,
                    onClick = { screenModel.preferences.realCuganPerformanceMode().set(value) },
                    label = { Text(name) },
                )
            }
        }

        SettingsChipRow(stringResource(MR.strings.reader_tile_size)) {
            val tileSize by screenModel.preferences.realCuganTileSize().collectAsState()
            listOf(64, 96, 128, 192, 256).map { value ->
                FilterChip(
                    selected = tileSize == value,
                    onClick = { screenModel.preferences.realCuganTileSize().set(value) },
                    label = { Text(value.toString()) },
                )
            }
        }

        SettingsChipRow(stringResource(MR.strings.reader_processing_jobs)) {
            val jobs by screenModel.preferences.realCuganJobs().collectAsState()
            (1..6).map { value ->
                FilterChip(
                    selected = jobs == value,
                    onClick = { screenModel.preferences.realCuganJobs().set(value) },
                    label = { Text(value.toString()) },
                )
            }
        }

        SettingsChipRow(stringResource(MR.strings.reader_precision)) {
            val precision by screenModel.preferences.realCuganPrecision().collectAsState()
            listOf(
                0 to stringResource(MR.strings.reader_precision_fp16),
                1 to stringResource(MR.strings.reader_precision_fp32),
                2 to stringResource(MR.strings.reader_precision_int8),
                3 to stringResource(MR.strings.reader_precision_bf16),
            ).map { (value, name) ->
                FilterChip(
                    selected = precision == value,
                    onClick = { screenModel.preferences.realCuganPrecision().set(value) },
                    label = { Text(name) },
                )
            }
        }

        val processMaxWidth by screenModel.preferences.realCuganMaxSizeWidth().collectAsState()
        val processMaxHeight by screenModel.preferences.realCuganMaxSizeHeight().collectAsState()
        ResolutionLimitFields(
            heading = stringResource(MR.strings.reader_processing_resolution),
            width = processMaxWidth,
            height = processMaxHeight,
            onWidthChange = { screenModel.preferences.realCuganMaxSizeWidth().set(it) },
            onHeightChange = { screenModel.preferences.realCuganMaxSizeHeight().set(it) },
        )

        val skipMaxWidth by screenModel.preferences.realCuganSkipMaxSizeWidth().collectAsState()
        val skipMaxHeight by screenModel.preferences.realCuganSkipMaxSizeHeight().collectAsState()
        ResolutionLimitFields(
            heading = stringResource(MR.strings.reader_max_resolution),
            width = skipMaxWidth,
            height = skipMaxHeight,
            onWidthChange = { screenModel.preferences.realCuganSkipMaxSizeWidth().set(it) },
            onHeightChange = { screenModel.preferences.realCuganSkipMaxSizeHeight().set(it) },
        )

        CheckboxItem(
            label = stringResource(MR.strings.reader_show_processing_status),
            pref = screenModel.preferences.realCuganShowStatus(),
        )
    }
}

@Composable
private fun ResolutionLimitFields(
    heading: String,
    width: Int,
    height: Int,
    onWidthChange: (Int) -> Unit,
    onHeightChange: (Int) -> Unit,
) {
    Column {
        HeadingItem(heading)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ResolutionNumberField(
                modifier = Modifier.weight(1f),
                value = width,
                onValueChange = onWidthChange,
                label = stringResource(MR.strings.reader_resolution_width),
            )
            ResolutionNumberField(
                modifier = Modifier.weight(1f),
                value = height,
                onValueChange = onHeightChange,
                label = stringResource(MR.strings.reader_resolution_height),
            )
        }
    }
}

@Composable
private fun ResolutionNumberField(
    value: Int,
    onValueChange: (Int) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf(value.toResolutionText()) }

    LaunchedEffect(value) {
        val normalized = value.toResolutionText()
        if (text != normalized && (text.toIntOrNull() ?: 0) != value) {
            text = normalized
        }
    }

    OutlinedTextField(
        modifier = modifier,
        value = text,
        onValueChange = { raw ->
            val filtered = raw.filter(Char::isDigit)
            text = filtered
            onValueChange(filtered.toIntOrNull() ?: 0)
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
    )
}

private fun Int.toResolutionText(): String = if (this == 0) "" else toString()

private fun getColorValue(currentColor: Int, color: Int, mask: Long, bitShift: Int): Int {
    return (color shl bitShift) or (currentColor and mask.inv().toInt())
}
private const val ALPHA_MASK: Long = 0xFF000000
private const val RED_MASK: Long = 0x00FF0000
private const val GREEN_MASK: Long = 0x0000FF00
private const val BLUE_MASK: Long = 0x000000FF

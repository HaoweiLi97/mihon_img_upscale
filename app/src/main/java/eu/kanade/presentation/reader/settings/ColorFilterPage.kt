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
import androidx.compose.runtime.getValue
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
        label = "图像增强",
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

        SettingsChipRow("模型") {
            listOf("Real-CUGAN", "Real-ESRGAN", "Real-CUGAN Nose", "Waifu2x").mapIndexed { index, name ->
                FilterChip(
                    selected = realCuganModel == index,
                    onClick = { screenModel.preferences.realCuganModel().set(index) },
                    label = { Text(name) },
                )
            }
        }

        if (realCuganModel == 0 || realCuganModel == 3) {
            SettingsChipRow("降噪等级") {
                listOf("无", "1x", "2x", "3x", "保守").mapIndexed { index, name ->
                    FilterChip(
                        selected = realCuganNoiseLevel == index,
                        onClick = { screenModel.preferences.realCuganNoiseLevel().set(index) },
                        label = { Text(name) },
                    )
                }
            }
        }

        if (realCuganModel == 2 || realCuganModel == 3) {
             SettingsChipRow("放大倍率") {
                  FilterChip(
                      selected = true,
                      onClick = {},
                      label = { Text("2x (固定)") }
                  )
             }
        } else {
            SettingsChipRow("放大倍率") {
                listOf(2, 3, 4).map { scale ->
                    FilterChip(
                        selected = realCuganScale == scale,
                        onClick = { screenModel.preferences.realCuganScale().set(scale) },
                        label = { Text("${scale}x") },
                    )
                }
            }
        }

        SettingsChipRow("处理预加载量") {
            listOf(6, 7, 8, 9, 10).map { size ->
                val realCuganPreloadSize by screenModel.preferences.realCuganPreloadSize().collectAsState()
                FilterChip(
                    selected = realCuganPreloadSize == size,
                    onClick = { screenModel.preferences.realCuganPreloadSize().set(size) },
                    label = { Text("${size}页") },
                )
            }
        }

        SettingsChipRow("输入缩放 (加速)") {
            listOf(100 to "原图", 90 to "90%", 80 to "80%", 70 to "70%").map { (value, name) ->
                FilterChip(
                    selected = realCuganInputScale == value,
                    onClick = { screenModel.preferences.realCuganInputScale().set(value) },
                    label = { Text(name) },
                )
            }
        }

        Column {
            HeadingItem("最大处理分辨率 (2K = 2048)")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val maxWidth by screenModel.preferences.realCuganMaxSizeWidth().collectAsState()
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = maxWidth.toString(),
                    onValueChange = { s ->
                        s.toIntOrNull()?.let { screenModel.preferences.realCuganMaxSizeWidth().set(it) }
                    },
                    label = { Text("最大宽度") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                val maxHeight by screenModel.preferences.realCuganMaxSizeHeight().collectAsState()
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = maxHeight.toString(),
                    onValueChange = { s ->
                        s.toIntOrNull()?.let { screenModel.preferences.realCuganMaxSizeHeight().set(it) }
                    },
                    label = { Text("最大高度") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            }
        }

        CheckboxItem(
            label = "在左下角显示处理状态",
            pref = screenModel.preferences.realCuganShowStatus(),
        )
    }
}

private fun getColorValue(currentColor: Int, color: Int, mask: Long, bitShift: Int): Int {
    return (color shl bitShift) or (currentColor and mask.inv().toInt())
}
private const val ALPHA_MASK: Long = 0xFF000000
private const val RED_MASK: Long = 0x00FF0000
private const val GREEN_MASK: Long = 0x0000FF00
private const val BLUE_MASK: Long = 0x000000FF

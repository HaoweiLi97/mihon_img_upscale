package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.graphics.alpha
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.Companion.ColorFilterMode
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.util.mediatek.MediaTekNpu
import eu.kanade.tachiyomi.util.npu.NpuModelStatus
import eu.kanade.tachiyomi.util.qnn.QnnModelContexts
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
internal fun ColumnScope.ColorFilterPage(viewModel: ReaderSettingsViewModel) {
    val customBrightness by viewModel.preferences.customBrightness.collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.pref_custom_brightness),
        pref = viewModel.preferences.customBrightness,
    )

    /*
     * Sets the brightness of the screen. Range is [-75, 100].
     * From -75 to -1 a semi-transparent black view is shown at the top with the minimum brightness.
     * From 1 to 100 it sets that value as brightness.
     * 0 sets system brightness and hides the overlay.
     */
    if (customBrightness) {
        val customBrightnessValue by viewModel.preferences.customBrightnessValue.collectAsState()
        SliderItem(
            value = customBrightnessValue,
            valueRange = -75..100,
            steps = 0,
            label = stringResource(MR.strings.pref_custom_brightness),
            onChange = { viewModel.preferences.customBrightnessValue.set(it) },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }

    val colorFilter by viewModel.preferences.colorFilter.collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.pref_custom_color_filter),
        pref = viewModel.preferences.colorFilter,
    )
    if (colorFilter) {
        val colorFilterValue by viewModel.preferences.colorFilterValue.collectAsState()
        SliderItem(
            value = colorFilterValue.red,
            valueRange = 0..255,
            steps = 0,
            label = stringResource(MR.strings.color_filter_r_value),
            onChange = { newRValue ->
                viewModel.preferences.colorFilterValue.getAndSet {
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
                viewModel.preferences.colorFilterValue.getAndSet {
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
                viewModel.preferences.colorFilterValue.getAndSet {
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
                viewModel.preferences.colorFilterValue.getAndSet {
                    getColorValue(it, newAValue, ALPHA_MASK, 24)
                }
            },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )

        val colorFilterMode by viewModel.preferences.colorFilterMode.collectAsState()
        SettingsChipRow(MR.strings.pref_color_filter_mode) {
            ColorFilterMode.mapIndexed { index, it ->
                FilterChip(
                    selected = colorFilterMode == index,
                    onClick = { viewModel.preferences.colorFilterMode.set(index) },
                    label = { Text(stringResource(it.first)) },
                )
            }
        }
    }

    CheckboxItem(
        label = stringResource(MR.strings.pref_grayscale),
        pref = viewModel.preferences.grayscale,
    )
    CheckboxItem(
        label = stringResource(MR.strings.pref_inverted_colors),
        pref = viewModel.preferences.invertedColors,
    )

    val realCuganEnabled by viewModel.preferences.realCuganEnabled().collectAsState()

    CheckboxItem(
        label = stringResource(MR.strings.reader_image_enhancement),
        checked = realCuganEnabled,
        onClick = {
            viewModel.preferences.realCuganEnabled().set(!realCuganEnabled)
        },
    )
    if (realCuganEnabled) {
        val realCuganModel by viewModel.preferences.realCuganModel().collectAsState()
        val realEsrganStyle by viewModel.preferences.realEsrganStyle().collectAsState()
        val realCuganNoiseLevel by viewModel.preferences.realCuganNoiseLevel().collectAsState()
        val realCuganScale by viewModel.preferences.realCuganScale().collectAsState()
        val processingBackend by viewModel.preferences.realCuganProcessingBackend().collectAsState()
        val npuDeviceAvailable = remember { Waifu2x.isQualcommNpuAvailable() }
        val mediaTekDeviceAvailable = remember { MediaTekNpu.isDeviceSupported() }
        val useQualcommNpu = processingBackend == Waifu2x.PROCESSING_BACKEND_QUALCOMM_NPU
        val useMediaTekNpu = processingBackend == Waifu2x.PROCESSING_BACKEND_MEDIATEK_NPU
        val useNpu = useQualcommNpu || useMediaTekNpu
        val context = LocalContext.current.applicationContext
        val qnnModelName = if (useNpu) {
            Waifu2x.qnnModelName(realCuganModel, realCuganScale, realCuganNoiseLevel, realEsrganStyle)
        } else {
            null
        }
        val qnnPreparationStates by QnnModelContexts.states.collectAsState()
        val mediaTekPreparationStates by MediaTekNpu.states.collectAsState()
        val preparationStates = if (useMediaTekNpu) mediaTekPreparationStates else qnnPreparationStates
        var preparationRetry by remember { mutableStateOf(0) }
        LaunchedEffect(processingBackend, qnnModelName, preparationRetry) {
            val modelName = qnnModelName ?: return@LaunchedEffect
            withContext(Dispatchers.IO) {
                if (useMediaTekNpu) {
                    MediaTekNpu.prepare(context, modelName, retry = preparationRetry > 0)
                } else {
                    QnnModelContexts.prepare(context, modelName, retry = preparationRetry > 0)
                }
            }
        }

        LaunchedEffect(useNpu, realCuganModel, realCuganScale) {
            if (useNpu) {
                if (
                    realCuganModel != 0 &&
                    realCuganModel != 1 &&
                    realCuganModel != Waifu2x.MODEL_REAL_ESRGAN_ANIME &&
                    realCuganModel != Waifu2x.MODEL_W2XEX_PHOTO_SMALL &&
                    realCuganModel != Waifu2x.MODEL_SPAN_NOMOSUNI_PHOTO
                ) {
                    viewModel.preferences.realCuganModel().set(0)
                }
                if (
                    (realCuganModel == 1 && realCuganScale !in 2..3) ||
                    (realCuganModel != 1 && realCuganScale != 2)
                ) {
                    viewModel.preferences.realCuganScale().set(2)
                }
            }
        }
        LaunchedEffect(realCuganModel, realCuganNoiseLevel) {
            if (realCuganModel == 1 && realCuganNoiseLevel !in setOf(0, 3, 4)) {
                viewModel.preferences.realCuganNoiseLevel().set(3)
            }
        }

        SettingsChipRow(stringResource(MR.strings.reader_processing_backend)) {
            FilterChip(
                selected = processingBackend == Waifu2x.PROCESSING_BACKEND_VULKAN,
                onClick = {
                    viewModel.preferences.realCuganProcessingBackend().set(Waifu2x.PROCESSING_BACKEND_VULKAN)
                },
                label = { Text(stringResource(MR.strings.reader_backend_vulkan)) },
            )
            FilterChip(
                selected = useQualcommNpu,
                onClick = {
                    viewModel.preferences.realCuganProcessingBackend().set(Waifu2x.PROCESSING_BACKEND_QUALCOMM_NPU)
                },
                enabled = npuDeviceAvailable,
                label = { Text(stringResource(MR.strings.reader_backend_qualcomm_npu)) },
            )
            FilterChip(
                selected = useMediaTekNpu,
                onClick = {
                    viewModel.preferences.realCuganProcessingBackend().set(Waifu2x.PROCESSING_BACKEND_MEDIATEK_NPU)
                },
                enabled = mediaTekDeviceAvailable,
                label = { Text(stringResource(MR.strings.reader_backend_mediatek_npu)) },
            )
        }

        SettingsChipRow(stringResource(MR.strings.reader_model)) {
            val models = if (useNpu) {
                listOf(
                    0 to "Real-CUGAN SE",
                    1 to "Real-CUGAN Pro",
                    Waifu2x.MODEL_REAL_ESRGAN_ANIME to "Real-ESRGAN",
                    Waifu2x.MODEL_W2XEX_PHOTO_SMALL to "W2xEX Photo Small",
                    Waifu2x.MODEL_SPAN_NOMOSUNI_PHOTO to "SPAN NomosUni Photo",
                )
            } else {
                listOf(
                    0 to "Real-CUGAN SE",
                    1 to "Real-CUGAN Pro",
                    Waifu2x.MODEL_REAL_ESRGAN_ANIME to "Real-ESRGAN",
                    3 to "Real-CUGAN Nose",
                    4 to "Waifu2x",
                    5 to "Waifu2x (Fast)",
                    6 to "W2xEX Universal Fast",
                    8 to "W2xEX Omni Mini V2",
                    Waifu2x.MODEL_W2XEX_PHOTO_SMALL to "W2xEX Photo Small",
                    16 to "AnimeJaNai v2 UltraCompact",
                    18 to "sudo UltraCompact",
                    Waifu2x.MODEL_SPAN_NOMOSUNI_PHOTO to "SPAN NomosUni Photo",
                )
            }
            models.map { (modelId, name) ->
                FilterChip(
                    selected = realCuganModel == modelId,
                    onClick = { viewModel.preferences.realCuganModel().set(modelId) },
                    label = { Text(name) },
                )
            }
        }

        if (qnnModelName != null) {
            val status = preparationStates[qnnModelName]
            Text(
                text = stringResource(
                    when (status) {
                        is NpuModelStatus.Ready -> MR.strings.reader_npu_model_ready
                        is NpuModelStatus.Failed -> MR.strings.reader_npu_model_failed
                        else -> MR.strings.reader_npu_model_preparing
                    },
                ),
                modifier = Modifier.padding(horizontal = 24.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            if (status is NpuModelStatus.Failed) {
                TextButton(onClick = { preparationRetry++ }, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(stringResource(MR.strings.action_retry))
                }
            }
        }

        if (realCuganModel == Waifu2x.MODEL_REAL_ESRGAN_ANIME) {
            SettingsChipRow(stringResource(MR.strings.reader_model_style)) {
                listOf(
                    Waifu2x.REAL_ESRGAN_STYLE_ANIME to "Anime",
                    Waifu2x.REAL_ESRGAN_STYLE_PHOTO to "Photo",
                ).map { (style, name) ->
                    FilterChip(
                        selected = realEsrganStyle == style,
                        onClick = { viewModel.preferences.realEsrganStyle().set(style) },
                        label = { Text(name) },
                    )
                }
            }
        }

        if (realCuganModel == 0 || realCuganModel == 1 || realCuganModel == 4 || realCuganModel == 5) {
            val levels = if (realCuganModel == 1) { // Pro only has no-denoise, denoise3x, conservative
                listOf(
                    0 to stringResource(MR.strings.reader_none),
                    3 to "3x",
                    4 to stringResource(MR.strings.reader_conservative),
                )
            } else if (realCuganModel == 4) { // Waifu2x
                listOf(0 to "1x", 1 to "2x", 2 to "3x")
            } else if (realCuganModel == 5) { // Waifu2x Fast (UpConv7)
                listOf(0 to stringResource(MR.strings.reader_none), 1 to "1x", 2 to "2x", 3 to "3x")
            } else { // SE
                listOf(
                    0 to stringResource(MR.strings.reader_none),
                    1 to "1x",
                    2 to "2x",
                    3 to "3x",
                    4 to stringResource(MR.strings.reader_conservative),
                )
            }

            SettingsChipRow(stringResource(MR.strings.reader_denoise_level)) {
                levels.map { (index, name) ->
                    FilterChip(
                        selected = realCuganNoiseLevel == index,
                        onClick = { viewModel.preferences.realCuganNoiseLevel().set(index) },
                        label = { Text(name) },
                    )
                }
            }
        }

        val fixedW2xExScale = Waifu2x.w2xExScaleFor(realCuganModel)
        if (
            (useNpu && realCuganModel != 1) ||
            realCuganModel == 3 || realCuganModel == 4 || realCuganModel == 5 ||
            (realCuganModel == Waifu2x.MODEL_REAL_ESRGAN_ANIME && realEsrganStyle == Waifu2x.REAL_ESRGAN_STYLE_PHOTO) ||
            fixedW2xExScale == 2
        ) {
            SettingsChipRow(stringResource(MR.strings.reader_scale_factor)) {
                FilterChip(
                    selected = true,
                    onClick = {},
                    label = {
                        Text(
                            if (fixedW2xExScale == 4) {
                                "4x"
                            } else {
                                stringResource(MR.strings.reader_scale_fixed_2x)
                            },
                        )
                    },
                )
            }
        } else if (fixedW2xExScale == 4) {
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
                        onClick = { viewModel.preferences.realCuganScale().set(scale) },
                        label = { Text("${scale}x") },
                    )
                }
            }
        } else {
            SettingsChipRow(stringResource(MR.strings.reader_scale_factor)) {
                listOf(2, 3, 4).map { scale ->
                    FilterChip(
                        selected = realCuganScale == scale,
                        onClick = { viewModel.preferences.realCuganScale().set(scale) },
                        label = { Text("${scale}x") },
                    )
                }
            }
        }

        SettingsChipRow(stringResource(MR.strings.reader_preload_pages)) {
            listOf(1, 2, 3, 5, 8).map { size ->
                val realCuganPreloadSize by viewModel.preferences.realCuganPreloadSize().collectAsState()
                FilterChip(
                    selected = realCuganPreloadSize == size,
                    onClick = { viewModel.preferences.realCuganPreloadSize().set(size) },
                    label = { Text(stringResource(MR.strings.reader_preload_pages_value, size)) },
                )
            }
        }

        if (!useNpu) {
            SettingsChipRow(stringResource(MR.strings.reader_gpu_performance_mode)) {
                val performanceMode by viewModel.preferences.realCuganPerformanceMode().collectAsState()
                listOf(
                    0 to stringResource(MR.strings.reader_gpu_performance_high),
                    1 to stringResource(MR.strings.reader_gpu_performance_balanced),
                    2 to stringResource(MR.strings.reader_gpu_performance_power_saving),
                ).map { (value, name) ->
                    FilterChip(
                        selected = performanceMode == value,
                        onClick = { viewModel.preferences.realCuganPerformanceMode().set(value) },
                        label = { Text(name) },
                    )
                }
            }

            SettingsChipRow(stringResource(MR.strings.reader_tile_size)) {
                val tileSize by viewModel.preferences.realCuganTileSize().collectAsState()
                listOf(64, 96, 128, 192, 256).map { value ->
                    FilterChip(
                        selected = tileSize == value,
                        onClick = { viewModel.preferences.realCuganTileSize().set(value) },
                        label = { Text(value.toString()) },
                    )
                }
            }
        }

        val precision by viewModel.preferences.realCuganPrecision().collectAsState()
        val supportedPrecisions = if (useNpu) {
            listOf(0)
        } else {
            listOf(0, 1, 3)
        }
        LaunchedEffect(useNpu, realCuganModel, precision) {
            if (precision !in supportedPrecisions) {
                viewModel.preferences.realCuganPrecision().set(supportedPrecisions.first())
            }
        }
        SettingsChipRow(stringResource(MR.strings.reader_precision)) {
            listOf(
                0 to stringResource(MR.strings.reader_precision_fp16),
                1 to stringResource(MR.strings.reader_precision_fp32),
                3 to stringResource(MR.strings.reader_precision_bf16),
            ).filter { (value, _) -> value in supportedPrecisions }.map { (value, name) ->
                FilterChip(
                    selected = precision == value,
                    onClick = { viewModel.preferences.realCuganPrecision().set(value) },
                    label = { Text(name) },
                )
            }
        }

        if (!useNpu && precision == 0) {
            CheckboxItem(
                label = stringResource(MR.strings.reader_fp16_arithmetic),
                pref = viewModel.preferences.realCuganFp16Arithmetic(),
            )
        }

        val processMaxWidth by viewModel.preferences.realCuganMaxSizeWidth().collectAsState()
        val processMaxHeight by viewModel.preferences.realCuganMaxSizeHeight().collectAsState()
        ResolutionLimitFields(
            heading = stringResource(MR.strings.reader_processing_resolution),
            width = processMaxWidth,
            height = processMaxHeight,
            onWidthChange = { viewModel.preferences.realCuganMaxSizeWidth().set(it) },
            onHeightChange = { viewModel.preferences.realCuganMaxSizeHeight().set(it) },
        )

        val skipMaxWidth by viewModel.preferences.realCuganSkipMaxSizeWidth().collectAsState()
        val skipMaxHeight by viewModel.preferences.realCuganSkipMaxSizeHeight().collectAsState()
        ResolutionLimitFields(
            heading = stringResource(MR.strings.reader_max_resolution),
            width = skipMaxWidth,
            height = skipMaxHeight,
            onWidthChange = { viewModel.preferences.realCuganSkipMaxSizeWidth().set(it) },
            onHeightChange = { viewModel.preferences.realCuganSkipMaxSizeHeight().set(it) },
        )

        CheckboxItem(
            label = stringResource(MR.strings.reader_show_processing_status),
            pref = viewModel.preferences.realCuganShowStatus(),
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

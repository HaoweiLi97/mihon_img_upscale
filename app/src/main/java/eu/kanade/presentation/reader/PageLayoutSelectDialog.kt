package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.PreviewLightDark
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.reader.components.ModeSelectionDialog
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.ui.reader.setting.PageLayout
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import androidx.compose.ui.Alignment

@Composable
fun PageLayoutSelectDialog(
    onDismissRequest: () -> Unit,
    screenModel: ReaderSettingsScreenModel,
    onChange: (StringResource) -> Unit,
) {
    val pageLayout by screenModel.preferences.pageLayout().collectAsState()

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        DialogContent(
            pageLayout = PageLayout.fromPreference(pageLayout),
            onChangePageLayout = {
                screenModel.preferences.pageLayout().set(it.value)
                onChange(it.stringRes)
                onDismissRequest()
            },
        )
    }
}

@Composable
private fun DialogContent(
    pageLayout: PageLayout,
    onChangePageLayout: (PageLayout) -> Unit,
) {
    var selected by remember(pageLayout) { mutableStateOf(pageLayout) }

    ModeSelectionDialog(
        onApply = { onChangePageLayout(selected) },
    ) {
        Column(
            modifier = Modifier.padding(vertical = SettingsItemsPaddings.Vertical),
        ) {
            Text(
                text = stringResource(MR.strings.pref_page_layout),
                modifier = Modifier.padding(
                    horizontal = SettingsItemsPaddings.Horizontal,
                    vertical = MaterialTheme.padding.small,
                ),
            )

            PageLayout.selectableEntries.forEach { layout ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selected == layout,
                            onClick = { selected = layout },
                            role = Role.RadioButton,
                        )
                        .padding(
                            horizontal = SettingsItemsPaddings.Horizontal,
                            vertical = MaterialTheme.padding.extraSmall,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selected == layout,
                        onClick = null,
                    )
                    Text(text = stringResource(layout.stringRes))
                }
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun DialogContentPreview() {
    TachiyomiPreviewTheme {
        Surface {
            Column {
                DialogContent(
                    pageLayout = PageLayout.SINGLE_PAGE,
                    onChangePageLayout = {},
                )
            }
        }
    }
}

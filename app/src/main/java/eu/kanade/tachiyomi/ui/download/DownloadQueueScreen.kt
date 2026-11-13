package eu.kanade.tachiyomi.ui.download

import android.view.LayoutInflater
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.updatePadding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.davidea.flexibleadapter.items.AbstractFlexibleItem
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.databinding.DownloadListBinding
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

object DownloadQueueScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<DownloadQueueViewModel>()
        val downloads by viewModel.downloads.collectAsStateWithLifecycle()
        val uploads by viewModel.uploads.collectAsStateWithLifecycle()
        var downloadsExpanded by rememberSaveable { mutableStateOf(true) }
        var uploadsExpanded by rememberSaveable { mutableStateOf(true) }
        val items = remember(downloads, uploads, downloadsExpanded, uploadsExpanded, viewModel) {
            buildList<AbstractFlexibleItem<*>> {
                add(
                    TransferHeaderItem(
                        isUpload = false,
                        size = downloads.size,
                        expanded = downloadsExpanded,
                        onToggle = { downloadsExpanded = !downloadsExpanded },
                        viewModel = viewModel,
                    ),
                )
                if (downloadsExpanded) addAll(downloads.toSourceHeaders(isUpload = false))
                add(
                    TransferHeaderItem(
                        isUpload = true,
                        size = uploads.size,
                        expanded = uploadsExpanded,
                        onToggle = { uploadsExpanded = !uploadsExpanded },
                        viewModel = viewModel,
                    ),
                )
                if (uploadsExpanded) addAll(uploads.toSourceHeaders(isUpload = true))
            }
        }

        LaunchedEffect(viewModel) {
            viewModel.statusFlow.collect(viewModel::updateRow)
        }
        LaunchedEffect(viewModel) {
            viewModel.progressFlow.collect(viewModel::updateRow)
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.label_transfer_queue),
                    navigateUp = navigator::pop,
                )
            },
        ) { contentPadding ->
            val direction = LocalLayoutDirection.current
            val density = LocalDensity.current
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    val queueAdapter = DownloadAdapter(viewModel.listener)
                    DownloadListBinding.inflate(LayoutInflater.from(context)).root.apply {
                        adapter = queueAdapter
                        queueAdapter.isHandleDragEnabled = true
                        layoutManager = LinearLayoutManager(context)
                        viewModel.list = this
                    }
                },
                onRelease = { list ->
                    if (viewModel.list === list) viewModel.list = null
                },
                update = { list ->
                    with(density) {
                        list.updatePadding(
                            left = contentPadding.calculateLeftPadding(direction).roundToPx(),
                            top = contentPadding.calculateTopPadding().roundToPx(),
                            right = contentPadding.calculateRightPadding(direction).roundToPx(),
                            bottom = contentPadding.calculateBottomPadding().roundToPx(),
                        )
                    }
                    (list.adapter as DownloadAdapter).submitItems(items)
                },
            )
        }
    }
}

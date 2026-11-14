package eu.kanade.tachiyomi.ui.download

import android.view.MenuItem
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.RecyclerView
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import kotlinx.coroutines.launch

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class DownloadQueueViewModel(
    private val downloadManager: DownloadManager,
) : ViewModel() {

    val downloads = downloadManager.queueState
    val uploads = downloadManager.uploadQueueState
    val isDownloaderRunning get() = downloadManager.isDownloadQueueRunning
    val statusFlow get() = downloadManager.statusFlow()
    val progressFlow get() = downloadManager.progressFlow()

    var list: RecyclerView? = null
    private val adapter get() = list?.adapter as? DownloadAdapter

    val listener = object : DownloadAdapter.DownloadItemListener {
        override fun onItemReleased(position: Int) {
            val adapter = adapter ?: return
            val downloads = adapter.headerItems.filterIsInstance<DownloadHeaderItem>()
                .filterNot { it.isUpload }
                .flatMap { header ->
                    if (header.isExpanded) {
                        adapter.getSectionItems(header).filterIsInstance<DownloadItem>()
                    } else {
                        header.subItems
                    }
                }
                .map(DownloadItem::download)
            reorder(downloads)
        }

        override fun onMenuItemClick(position: Int, menuItem: MenuItem) {
            val item = adapter?.getItem(position) as? DownloadItem ?: return
            val download = item.download
            when (menuItem.itemId) {
                R.id.retry_download -> viewModelScope.launch {
                    downloadManager.startDownloadNow(download.chapter.id)
                }
                R.id.resume_upload -> viewModelScope.launch {
                    downloadManager.resumeCloudUpload(download.chapter.id)
                }
                R.id.pause_upload -> viewModelScope.launch {
                    downloadManager.pauseCloudUpload(download.chapter.id)
                }
                R.id.cancel_upload -> viewModelScope.launch {
                    downloadManager.cancelCloudUpload(download.chapter.id)
                }
                R.id.move_to_top, R.id.move_to_bottom -> {
                    val toTop = menuItem.itemId == R.id.move_to_top
                    val reordered = downloads.value.groupBy { it.source }.flatMap { (source, group) ->
                        if (source != download.source) return@flatMap group
                        val others = group.filterNot { it.chapter.id == download.chapter.id }
                        if (toTop) listOf(download) + others else others + download
                    }
                    reorder(reordered)
                }
                R.id.move_to_top_series, R.id.move_to_bottom_series -> {
                    val (selected, others) = downloads.value.partition { it.manga.id == download.manga.id }
                    reorder(if (menuItem.itemId == R.id.move_to_top_series) selected + others else others + selected)
                }
                R.id.cancel_download -> cancel(listOf(download))
                R.id.cancel_series -> cancel(downloads.value.filter { it.manga.id == download.manga.id })
            }
        }
    }

    fun startDownloads() {
        downloadManager.startDownloads(retryFailed = true)
    }

    fun pauseDownloads() {
        viewModelScope.launch { downloadManager.pauseDownloads() }
    }

    fun cancelDownloads() {
        viewModelScope.launch { downloadManager.clearQueue() }
    }

    fun resumeUploads() {
        viewModelScope.launch { downloadManager.resumeCloudUploads() }
    }

    fun pauseUploads() {
        viewModelScope.launch { downloadManager.pauseCloudUploads() }
    }

    fun cancelUploads() {
        viewModelScope.launch { downloadManager.cancelCloudUploads() }
    }

    fun <R : Comparable<R>> sortDownloads(selector: (Download) -> R?, descending: Boolean) {
        val groups = downloads.value.groupBy { it.source }.values
        reorder(
            groups.flatMap { group ->
                val sorted = group.sortedBy(selector)
                if (descending) sorted.asReversed() else sorted
            },
        )
    }

    private fun reorder(downloads: List<Download>) {
        viewModelScope.launch { downloadManager.reorderQueue(downloads) }
    }

    private fun cancel(downloads: List<Download>) {
        viewModelScope.launch { downloadManager.cancelQueuedDownloads(downloads) }
    }

    fun updateRow(download: Download) {
        val list = list ?: return
        for (isUpload in listOf(false, true)) {
            val id = DownloadItem.stableId(download.chapter.id, isUpload)
            val holder = list.findViewHolderForItemId(id) as? DownloadHolder ?: continue
            if (holder.isBoundTo(download)) {
                holder.notifyProgress()
                holder.notifyDownloadedPages()
            }
        }
    }
}

internal fun List<Download>.toSourceHeaders(isUpload: Boolean): List<DownloadHeaderItem> =
    groupBy { it.source }.map { (source, items) ->
        DownloadHeaderItem(source.id, source.name, items.size, isUpload).apply {
            addSubItems(0, items.map { DownloadItem(it, this, isUpload) })
        }
    }

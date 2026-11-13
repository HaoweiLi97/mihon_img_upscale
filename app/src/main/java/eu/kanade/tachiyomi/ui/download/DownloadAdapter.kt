package eu.kanade.tachiyomi.ui.download

import android.view.MenuItem
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractFlexibleItem

/**
 * Adapter storing a list of downloads.
 *
 * @param downloadItemListener Listener called when an item of the list is released.
 */
class DownloadAdapter(val downloadItemListener: DownloadItemListener) : FlexibleAdapter<AbstractFlexibleItem<*>>(
    null,
    downloadItemListener,
    true,
) {

    private var submittedItems: List<AbstractFlexibleItem<*>>? = null
    private val sourceHeaderIds = mutableMapOf<Pair<Long, Boolean>, Long>()

    fun submitItems(items: List<AbstractFlexibleItem<*>>) {
        if (submittedItems === items) return
        submittedItems = items
        updateDataSet(items)
    }

    override fun getItemId(position: Int): Long = when (val item = getItem(position)) {
        is DownloadItem -> DownloadItem.stableId(item.download.chapter.id, item.isUpload)
        is DownloadHeaderItem -> sourceHeaderIds.getOrPut(item.id to item.isUpload) { -4L - sourceHeaderIds.size }
        is TransferHeaderItem -> if (item.isUpload) -3L else -2L
        else -> super.getItemId(position)
    }

    override fun shouldMove(fromPosition: Int, toPosition: Int): Boolean {
        val from = getItem(fromPosition)
        val to = getItem(toPosition)
        return when {
            from is DownloadHeaderItem && to is DownloadHeaderItem -> !from.isUpload && !to.isUpload
            from is DownloadItem && to is DownloadItem -> !from.isUpload && !to.isUpload && from.header == to.header
            else -> false
        }
    }

    interface DownloadItemListener {
        fun onItemReleased(position: Int)
        fun onMenuItemClick(position: Int, menuItem: MenuItem)
    }
}

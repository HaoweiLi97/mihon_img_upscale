package eu.kanade.tachiyomi.ui.download

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractFlexibleItem
import eu.davidea.flexibleadapter.items.IFlexible
import eu.davidea.viewholders.FlexibleViewHolder
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.databinding.TransferQueueHeaderBinding
import eu.kanade.tachiyomi.util.view.popupMenu
import java.text.NumberFormat
import tachiyomi.i18n.R as I18nR

internal data class TransferHeaderItem(
    val isUpload: Boolean,
    val size: Int,
    val expanded: Boolean,
    val onToggle: () -> Unit,
    val viewModel: DownloadQueueViewModel,
) : AbstractFlexibleItem<TransferHeaderHolder>() {

    init {
        isDraggable = false
        isSelectable = false
    }

    override fun getLayoutRes(): Int = R.layout.transfer_queue_header

    override fun createViewHolder(
        view: View,
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
    ) = TransferHeaderHolder(view, adapter)

    override fun bindViewHolder(
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
        holder: TransferHeaderHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        holder.bind(this)
    }
}

internal class TransferHeaderHolder(view: View, adapter: FlexibleAdapter<*>) : FlexibleViewHolder(view, adapter) {
    private val binding = TransferQueueHeaderBinding.bind(view)

    fun bind(item: TransferHeaderItem) {
        val context = itemView.context
        val title = if (item.isUpload) I18nR.string.transfer_queue_uploads else I18nR.string.pref_category_downloads
        val actions = if (item.isUpload) {
            I18nR.string.transfer_queue_upload_actions
        } else {
            I18nR.string.transfer_queue_download_actions
        }
        val state = if (item.expanded) I18nR.string.transfer_queue_expanded else I18nR.string.transfer_queue_collapsed
        binding.title.setText(title)
        binding.count.text = NumberFormat.getIntegerInstance().format(item.size)
        binding.chevron.rotation = if (item.expanded) 180f else 0f
        ViewCompat.setAccessibilityHeading(binding.toggle, true)
        ViewCompat.setStateDescription(binding.toggle, context.getString(state))
        binding.toggle.setOnClickListener { item.onToggle() }
        binding.actions.contentDescription = context.getString(actions)
        binding.actions.isVisible = item.size > 0
        binding.actions.setOnClickListener { showActions(item) }
        binding.empty.isVisible = item.expanded && item.size == 0
        binding.empty.setText(
            if (item.isUpload) I18nR.string.transfer_queue_no_uploads else I18nR.string.information_no_downloads,
        )
    }

    private fun showActions(item: TransferHeaderItem) {
        val viewModel = item.viewModel
        binding.actions.popupMenu(
            menuRes = R.menu.transfer_queue,
            initMenu = {
                findItem(R.id.sort_transfers).isVisible = !item.isUpload
                if (item.isUpload) {
                    val uploads = viewModel.uploads.value
                    findItem(R.id.pause_transfers).isEnabled = uploads.any {
                        it.status == Download.State.QUEUE || it.status == Download.State.UPLOADING
                    }
                    findItem(R.id.resume_transfers).isEnabled = uploads.any {
                        it.status == Download.State.PAUSED || it.status == Download.State.ERROR
                    }
                } else {
                    val running = viewModel.isDownloaderRunning
                    findItem(R.id.pause_transfers).isEnabled = running
                    findItem(R.id.resume_transfers).isEnabled = !running
                }
            },
            onMenuItemClick = {
                when (itemId) {
                    R.id.pause_transfers -> {
                        if (item.isUpload) viewModel.pauseUploads() else viewModel.pauseDownloads()
                    }
                    R.id.resume_transfers -> {
                        if (item.isUpload) viewModel.resumeUploads() else viewModel.startDownloads()
                    }
                    R.id.cancel_transfers -> confirmCancellation(item)
                    R.id.sort_transfers_newest -> viewModel.sortDownloads({ it.chapter.dateUpload }, descending = true)
                    R.id.sort_transfers_oldest -> viewModel.sortDownloads({ it.chapter.dateUpload }, descending = false)
                    R.id.sort_transfers_asc -> viewModel.sortDownloads({ it.chapter.chapterNumber }, descending = false)
                    R.id.sort_transfers_desc -> viewModel.sortDownloads({ it.chapter.chapterNumber }, descending = true)
                }
            },
        )
    }

    private fun confirmCancellation(item: TransferHeaderItem) {
        val title = if (item.isUpload) {
            I18nR.string.transfer_queue_cancel_uploads
        } else {
            I18nR.string.transfer_queue_cancel_downloads
        }
        MaterialAlertDialogBuilder(itemView.context)
            .setTitle(title)
            .setNegativeButton(I18nR.string.action_close, null)
            .setPositiveButton(I18nR.string.action_cancel_all) { _, _ ->
                if (item.isUpload) item.viewModel.cancelUploads() else item.viewModel.cancelDownloads()
            }
            .show()
    }
}

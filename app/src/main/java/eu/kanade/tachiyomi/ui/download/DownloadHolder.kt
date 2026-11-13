package eu.kanade.tachiyomi.ui.download

import android.view.View
import androidx.recyclerview.widget.ItemTouchHelper
import eu.davidea.viewholders.FlexibleViewHolder
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.databinding.DownloadItemBinding
import eu.kanade.tachiyomi.util.view.popupMenu
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/**
 * Class used to hold the data of a download.
 * All the elements from the layout file "download_item" are available in this class.
 *
 * @param view the inflated view for this holder.
 * @constructor creates a new download holder.
 */
class DownloadHolder(private val view: View, val adapter: DownloadAdapter) :
    FlexibleViewHolder(view, adapter) {

    private val binding = DownloadItemBinding.bind(view)

    init {
        setDragHandleView(binding.reorder)
        binding.menu.setOnClickListener { it.post { showPopupMenu(it) } }
    }

    private lateinit var download: Download
    private var isUpload: Boolean = false

    fun isBoundTo(download: Download): Boolean = this.download === download

    /**
     * Binds this holder with the given category.
     *
     * @param category The category to bind.
     */
    fun bind(download: Download, isUpload: Boolean) {
        this.download = download
        this.isUpload = isUpload
        binding.reorder.visibility = if (isUpload) View.INVISIBLE else View.VISIBLE
        binding.menu.visibility = View.VISIBLE

        // Update the chapter name.
        binding.chapterTitle.text = download.chapter.name

        // Update the manga title
        binding.mangaFullTitle.text = download.manga.title

        // Update the progress bar and the number of downloaded pages
        val pages = download.pages
        if (isUpload) {
            binding.downloadProgress.max = 100
            binding.downloadProgress.progress = download.uploadProgress
            notifyProgress()
            notifyDownloadedPages()
        } else if (pages == null) {
            binding.downloadProgress.visibility = View.VISIBLE
            binding.downloadProgress.progress = 0
            binding.downloadProgress.max = 1
            binding.downloadProgressText.text = ""
        } else {
            binding.downloadProgress.visibility = View.VISIBLE
            binding.downloadProgress.max = pages.size * 100
            notifyProgress()
            notifyDownloadedPages()
        }
    }

    /**
     * Updates the progress bar of the download.
     */
    fun notifyProgress() {
        if (isUpload) {
            val uploading = download.status == Download.State.UPLOADING
            binding.downloadProgress.visibility = if (uploading) View.VISIBLE else View.INVISIBLE
            if (uploading) binding.downloadProgress.setProgressCompat(download.uploadProgress, true)
            return
        }
        val pages = download.pages ?: return
        if (binding.downloadProgress.max == 1) {
            binding.downloadProgress.max = pages.size * 100
        }
        binding.downloadProgress.setProgressCompat(download.totalProgress, true)
    }

    /**
     * Updates the text field of the number of downloaded pages.
     */
    fun notifyDownloadedPages() {
        if (isUpload) {
            val context = view.context
            binding.downloadProgressText.text = when (download.status) {
                Download.State.QUEUE -> context.stringResource(MR.strings.cloud_sync_upload_queued)
                Download.State.PAUSED -> context.stringResource(MR.strings.cloud_sync_upload_paused)
                Download.State.ERROR -> context.stringResource(MR.strings.cloud_sync_upload_failed)
                Download.State.UPLOADING -> context.stringResource(
                    MR.strings.cloud_sync_uploading_progress,
                    download.uploadProgress,
                )
                else -> ""
            }
            return
        }
        val pages = download.pages ?: return
        binding.downloadProgressText.text = "${download.downloadedImages}/${pages.size}"
    }

    override fun onItemReleased(position: Int) {
        super.onItemReleased(position)
        adapter.downloadItemListener.onItemReleased(position)
        binding.container.isDragged = false
    }

    override fun onActionStateChanged(position: Int, actionState: Int) {
        super.onActionStateChanged(position, actionState)
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
            binding.container.isDragged = true
        }
    }

    private fun showPopupMenu(view: View) {
        view.popupMenu(
            menuRes = if (isUpload) R.menu.upload_single else R.menu.download_single,
            initMenu = {
                if (isUpload) {
                    val status = download.status
                    findItem(R.id.resume_upload).apply {
                        isVisible = status == Download.State.PAUSED || status == Download.State.ERROR
                        if (status == Download.State.ERROR) {
                            title = view.context.stringResource(MR.strings.action_retry)
                        }
                    }
                    findItem(R.id.pause_upload).isVisible =
                        status == Download.State.QUEUE || status == Download.State.UPLOADING
                } else {
                    val position = bindingAdapterPosition
                    val header = (adapter.getItem(position) as? DownloadItem)?.header
                    findItem(R.id.move_to_top).isVisible =
                        header != null && (adapter.getItem(position - 1) as? DownloadItem)?.header == header
                    findItem(R.id.move_to_bottom).isVisible =
                        header != null && (adapter.getItem(position + 1) as? DownloadItem)?.header == header
                }
            },
            onMenuItemClick = {
                adapter.downloadItemListener.onMenuItemClick(bindingAdapterPosition, this)
            },
        )
    }
}

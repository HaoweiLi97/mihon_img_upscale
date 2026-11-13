package eu.kanade.tachiyomi.ui.download

import android.annotation.SuppressLint
import android.view.View
import androidx.recyclerview.widget.ItemTouchHelper
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.viewholders.ExpandableViewHolder
import eu.kanade.tachiyomi.databinding.DownloadHeaderBinding

class DownloadHeaderHolder(view: View, adapter: FlexibleAdapter<*>) : ExpandableViewHolder(view, adapter) {

    private val binding = DownloadHeaderBinding.bind(view)

    @SuppressLint("SetTextI18n")
    fun bind(item: DownloadHeaderItem) {
        binding.reorder.visibility = if (item.isUpload) View.INVISIBLE else View.VISIBLE
        if (!item.isUpload) setDragHandleView(binding.reorder)
        binding.title.text = "${item.name} (${item.size})"
    }

    override fun onActionStateChanged(position: Int, actionState: Int) {
        super.onActionStateChanged(position, actionState)
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
            binding.container.isDragged = true
            val adapter = mAdapter as DownloadAdapter
            adapter.headerItems.filterIsInstance<DownloadHeaderItem>()
                .filterNot { it.isUpload }
                .asReversed()
                .forEach { adapter.collapse(adapter.getGlobalPositionOf(it)) }
        }
    }

    override fun onItemReleased(position: Int) {
        super.onItemReleased(position)
        binding.container.isDragged = false
        val adapter = mAdapter as DownloadAdapter
        adapter.headerItems.filterIsInstance<DownloadHeaderItem>()
            .filterNot { it.isUpload }
            .forEach { adapter.expand(adapter.getGlobalPositionOf(it)) }
        adapter.downloadItemListener.onItemReleased(position)
    }
}

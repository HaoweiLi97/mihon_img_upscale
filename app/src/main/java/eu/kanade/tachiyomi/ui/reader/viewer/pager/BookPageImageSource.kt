package eu.kanade.tachiyomi.ui.reader.viewer.pager

import okio.BufferedSource
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/** Picture rectangles travel with composed sources without retaining their image buffers. */
internal object BookPageImageSource {
    private val queue = ReferenceQueue<BufferedSource>()
    private val pictures = mutableMapOf<SourceReference, List<BookPageBounds>>()

    // Buffer equality and hash codes change as bytes are consumed; metadata must use identity.
    private class SourceReference(source: BufferedSource, queue: ReferenceQueue<BufferedSource>? = null) :
        WeakReference<BufferedSource>(source, queue) {
        private val identity = System.identityHashCode(source)
        override fun hashCode() = identity
        override fun equals(other: Any?): Boolean =
            this === other || (other is SourceReference && get()?.let { it === other.get() } == true)
    }

    @Synchronized
    operator fun invoke(source: BufferedSource, bounds: List<BookPageBounds>): BufferedSource {
        prune()
        pictures[SourceReference(source, queue)] = bounds
        return source
    }

    @Synchronized
    fun pictureBounds(source: BufferedSource): List<BookPageBounds>? {
        prune()
        return pictures[SourceReference(source)]
    }

    private fun prune() {
        while (true) {
            val stale = queue.poll() as? SourceReference ?: return
            pictures.remove(stale)
        }
    }
}

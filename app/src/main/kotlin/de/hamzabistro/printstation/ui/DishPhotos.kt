package de.hamzabistro.printstation.ui

import androidx.compose.ui.graphics.ImageBitmap
import de.hamzabistro.printstation.AppGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The dishes' photos for the big tiles of "Neue Bestellung" and "Kasse", each fetched once. */
class DishPhotos(private val graph: AppGraph, private val scope: CoroutineScope) {
    private val cache = mutableMapOf<String, ImageBitmap?>()
    private val _photos = MutableStateFlow<Map<String, ImageBitmap?>>(emptyMap())

    /** By URL; null for one there is nothing to show for. */
    val photos: StateFlow<Map<String, ImageBitmap?>> = _photos.asStateFlow()

    /** Fetches [url]'s photo once, small enough for a tile; null when there is none to show. */
    fun load(url: String) {
        if (url.isBlank() || url in cache) return
        cache[url] = null
        scope.launch {
            val bitmap =
                try {
                    graph.photoPreview(url)?.let { bytes -> withContext(Dispatchers.Default) { tile(bytes) } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            cache[url] = bitmap
            _photos.update { it + (url to bitmap) }
        }
    }
}

package com.fancyshark.wpdialer.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * WP-style square contact tile: the contact photo, or an accent-colored
 * square with the contact's initials centered in a light weight — visually
 * distinct from the bold lowercase letter-group tiles.
 *
 * Photos are decoded subsampled to the tile's pixel size and cached, so long
 * lists (history, people, T9) never hold full-resolution bitmaps.
 */
@Composable
fun ContactTile(
    name: String,
    photoUri: String?,
    accent: Color,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    // Bumped when the contacts provider changes (an edited photo keeps its
    // thumbnail URI, so the cache can't key on the URI alone).
    val generation by ContactThumbs.generation.collectAsState()
    // Seeded from the cache: a row scrolled back into view shows its photo
    // on the first frame instead of flashing the initials.
    val photo by produceState(ContactThumbs.cachedPhoto(photoUri, px), photoUri, px, generation) {
        value = ContactThumbs.photo(context, photoUri, px)
    }
    Box(
        modifier.size(size).background(accent),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = photo
        if (bitmap != null) {
            Image(
                bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        } else {
            val initials = initialsOf(name)
            Text(
                initials,
                color = Metro.onAccent(accent),
                fontSize = (size.value * if (initials.length > 1) 0.30f else 0.40f).sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 1.sp,
            )
        }
    }
}

/**
 * Tile for a call-log row: the matching contact's photo, else the accent
 * initials tile for a named caller, else a neutral gray "#" tile for an
 * unknown or withheld number.
 */
@Composable
fun CallerTile(
    number: String,
    name: String?,
    accent: Color,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val generation by ContactThumbs.generation.collectAsState()
    val thumb by produceState(ContactThumbs.cachedNumberThumb(number), number, generation) {
        value = ContactThumbs.numberThumb(context, number)
    }
    val photoUri = thumb
    if (name.isNullOrBlank() && photoUri == null) {
        Box(
            modifier.size(size).background(Metro.Key),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "#",
                color = Metro.Subtle,
                fontSize = (size.value * 0.40f).sp,
                fontWeight = FontWeight.Light,
            )
        }
    } else {
        ContactTile(name.orEmpty(), photoUri, accent, size, modifier)
    }
}

/**
 * Process-wide thumbnail caches: decoded bitmaps (by URI + pixel size) and
 * number -> contact thumbnail URI lookups. Both are wiped, debounced, when
 * the contacts provider reports a change.
 */
object ContactThumbs {

    /** Cached "this number has no contact photo". */
    private const val NONE = ""

    private val bitmaps = object : android.util.LruCache<String, ImageBitmap>(
        (Runtime.getRuntime().maxMemory() / 16).coerceAtMost(16L * 1024 * 1024).toInt(),
    ) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    private val byNumber = android.util.LruCache<String, String>(1024)

    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation

    @Volatile
    private var observing = false
    private val handler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val flush = Runnable {
        bitmaps.evictAll()
        byNumber.evictAll()
        _generation.value++
    }

    private fun key(uri: String, px: Int) = "$px@$uri"

    fun cachedPhoto(uri: String?, px: Int): ImageBitmap? =
        uri?.let { bitmaps.get(key(it, px)) }

    /** The photo at [uri], subsampled to at least [px] on its short side. */
    suspend fun photo(context: Context, uri: String?, px: Int): ImageBitmap? {
        if (uri == null) return null
        observe(context)
        bitmaps.get(key(uri, px))?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching { decode(context, Uri.parse(uri), px) }.getOrNull()
                ?.also { bitmaps.put(key(uri, px), it) }
        }
    }

    private fun decode(context: Context, uri: Uri, px: Int): ImageBitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // A bounds-only decode always returns null — read the size from the
        // options instead of treating the null as failure.
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val shortSide = minOf(bounds.outWidth, bounds.outHeight)
        if (shortSide <= 0) return null
        var sample = 1
        while (shortSide / (sample * 2) >= px) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }?.asImageBitmap()
    }

    /** Cached thumbnail URI for [number]; null when unknown or photo-less. */
    fun cachedNumberThumb(number: String): String? =
        byNumber.get(number)?.takeIf { it != NONE }

    /** The thumbnail URI of the contact owning [number], if it has a photo. */
    suspend fun numberThumb(context: Context, number: String): String? {
        if (number.isBlank()) return null
        observe(context)
        byNumber.get(number)?.let { return it.takeIf { v -> v != NONE } }
        return withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.query(
                    Uri.withAppendedPath(
                        ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                        Uri.encode(number),
                    ),
                    arrayOf(ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI),
                    null, null, null,
                )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    .also { byNumber.put(number, it ?: NONE) }
            }.getOrNull() // No READ_CONTACTS: not cached, retried next time.
        }
    }

    private fun observe(context: Context) {
        if (observing) return
        synchronized(this) {
            if (observing) return
            observing = true
        }
        runCatching {
            context.applicationContext.contentResolver.registerContentObserver(
                ContactsContract.AUTHORITY_URI,
                true,
                object : android.database.ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) {
                        // Syncs fire bursts of changes — reload once they settle.
                        handler.removeCallbacks(flush)
                        handler.postDelayed(flush, 800)
                    }
                },
            )
        }
    }
}

/** Shared with the contact live tile and shortcut icons (widget/). */
internal fun initialsOf(name: String): String {
    val parts = name.trim()
        .split(Regex("\\s+"))
        .mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() } }
    if (parts.isEmpty()) return "?"
    return listOfNotNull(parts.firstOrNull(), if (parts.size > 1) parts.last() else null)
        .joinToString("") { it.uppercase() }
}

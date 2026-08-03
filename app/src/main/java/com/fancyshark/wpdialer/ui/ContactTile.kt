package com.fancyshark.wpdialer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.fancyshark.wpdialer.data.Repo

/**
 * WP-style square contact tile: the contact photo, or an accent-colored
 * square with the contact's initials centered in a light weight — visually
 * distinct from the bold lowercase letter-group tiles.
 */
@Composable
fun ContactTile(name: String, photoUri: String?, accent: Color, size: Dp) {
    val context = LocalContext.current
    val photo by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, photoUri) {
        value = Repo.loadPhoto(context, photoUri)
    }
    Box(
        Modifier.size(size).background(accent),
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
                color = Color.White,
                fontSize = (size.value * if (initials.length > 1) 0.30f else 0.40f).sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 1.sp,
            )
        }
    }
}

private fun initialsOf(name: String): String {
    val parts = name.trim()
        .split(Regex("\\s+"))
        .mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() } }
    if (parts.isEmpty()) return "?"
    return listOfNotNull(parts.firstOrNull(), if (parts.size > 1) parts.last() else null)
        .joinToString("") { it.uppercase() }
}

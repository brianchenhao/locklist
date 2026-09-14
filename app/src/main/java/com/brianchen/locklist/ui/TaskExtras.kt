package com.brianchen.locklist.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.brianchen.locklist.sync.ThumbCache

private val URL_REGEX = Regex("""https?://[^\s<>\]]+""")

fun firstNoteLine(notes: String): String {
    return notes.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
}

@Composable
fun TaskNoteText(
    notes: String,
    color: Color,
    linkColor: Color,
    onUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE
) {
    if (notes.isBlank()) return
    val annotated = remember(notes, color, linkColor) {
        buildAnnotatedString {
            var cursor = 0
            for (match in URL_REGEX.findAll(notes)) {
                val raw = match.value.trimEnd('.', ',', ';', ')', ']')
                append(notes.substring(cursor, match.range.first))
                val start = length
                append(raw)
                addLink(
                    LinkAnnotation.Url(
                        raw,
                        TextLinkStyles(
                            style = SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline
                            )
                        )
                    ) {
                        onUrl(raw)
                    },
                    start,
                    start + raw.length
                )
                cursor = match.range.first + raw.length
            }
            if (cursor < notes.length) append(notes.substring(cursor))
        }
    }
    Text(
        text = annotated,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/**
 * Row of cached thumbnails. Tap opens the full image; when [onRemove] is given, each thumb
 * gets a small close button (app only, never on the lock screen).
 */
@Composable
fun TaskThumbs(
    paths: List<String>,
    thumbs: ThumbCache,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    onRemove: ((String) -> Unit)? = null
) {
    if (paths.isEmpty()) return
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        paths.forEach { path ->
            var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(path) {
                bitmap = thumbs.thumb(path)
            }
            Box(modifier = Modifier.size(size)) {
                val bmp = bitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Attachment",
                        modifier = Modifier
                            .size(size)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onOpen(path) },
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(size)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Gray.copy(alpha = 0.25f))
                    )
                }
                if (onRemove != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f))
                            .clickable { onRemove(path) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove photo",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

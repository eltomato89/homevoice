package de.homevoice.assistant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import de.homevoice.assistant.media.NowPlaying

/** Compact "now playing" card with previous / play-pause / next. Sizes are pre-scaled by the caller. */
@Composable
fun NowPlayingWidget(
    now: NowPlaying,
    onAction: (String) -> Unit,
    unit: Dp,
    text: TextUnit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(unit * 1.25f))
            .padding(unit * 0.75f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(unit * 0.75f),
    ) {
        now.artwork?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(unit * 4).clip(RoundedCornerShape(unit * 0.5f)),
            )
        }
        Column(Modifier.width(unit * 8)) {
            Text(now.title, color = Color.White, fontSize = text, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (now.artist.isNotBlank()) {
                Text(now.artist, color = Color.White.copy(alpha = 0.7f), fontSize = text * 0.85f,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        MediaButton(unit, { onAction("previous") }) { skip(forward = false) }
        MediaButton(unit, { onAction(if (now.isPlaying) "pause" else "resume") }, large = true) {
            if (now.isPlaying) pause() else play()
        }
        MediaButton(unit, { onAction("next") }) { skip(forward = true) }
    }
}

@Composable
private fun MediaButton(unit: Dp, onClick: () -> Unit, large: Boolean = false, icon: DrawScope.() -> Unit) {
    val size = if (large) unit * 3.5f else unit * 3f
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (large) 0.25f else 0.12f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.42f)) { icon() }
    }
}

private fun DrawScope.play() {
    val path = Path().apply {
        moveTo(size.width * 0.15f, 0f)
        lineTo(size.width, size.height / 2)
        lineTo(size.width * 0.15f, size.height)
        close()
    }
    drawPath(path, Color.White)
}

private fun DrawScope.pause() {
    val w = size.width * 0.32f
    drawRect(Color.White, Offset(size.width * 0.08f, 0f), Size(w, size.height))
    drawRect(Color.White, Offset(size.width * 0.6f, 0f), Size(w, size.height))
}

private fun DrawScope.skip(forward: Boolean) {
    val bar = size.width * 0.16f
    val path = Path().apply {
        if (forward) {
            moveTo(0f, 0f); lineTo(size.width - bar, size.height / 2); lineTo(0f, size.height)
        } else {
            moveTo(size.width, 0f); lineTo(bar, size.height / 2); lineTo(size.width, size.height)
        }
        close()
    }
    drawPath(path, Color.White)
    drawRect(Color.White, Offset(if (forward) size.width - bar else 0f, 0f), Size(bar, size.height))
}

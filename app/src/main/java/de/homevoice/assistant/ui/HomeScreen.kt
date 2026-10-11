package de.homevoice.assistant.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Scale
import de.homevoice.assistant.media.NowPlaying
import de.homevoice.assistant.service.AssistantState
import de.homevoice.assistant.service.AssistantStatus
import de.homevoice.assistant.settings.Settings
import de.homevoice.assistant.slideshow.Photo
import de.homevoice.assistant.timer.TimerEntry
import de.homevoice.assistant.ui.theme.Palette
import de.homevoice.vision.Shot
import de.homevoice.vision.ShotPlanner
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** UI scale relative to a 420 dp tall screen; ~0.94 on an Echo Show 5 (5", read from across the room). */
private val LocalUiScale = staticCompositionLocalOf { 1f }

private val Number.sdp: Dp @Composable get() = (toFloat() * LocalUiScale.current).dp
private val Number.ssp: TextUnit @Composable get() = (toFloat() * LocalUiScale.current).sp

@Composable
fun HomeScreen(
    settings: Settings,
    status: AssistantStatus,
    photos: List<Photo>,
    timers: List<TimerEntry>,
    ringing: List<TimerEntry>,
    night: Boolean,
    setupHint: String?,
    /** Web address (null without network) and PIN, shown after a long press. */
    setupCard: Pair<String?, String>?,
    nowPlaying: NowPlaying?,
    onMediaAction: (String) -> Unit,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val scale = (LocalConfiguration.current.screenHeightDp / 420f).coerceIn(0.8f, 1.5f)
    CompositionLocalProvider(LocalUiScale provides scale) {
        HomeContent(settings, status, photos, timers, ringing, night, setupHint, setupCard, nowPlaying, onMediaAction, onTap, onLongPress)
    }
}

@Composable
private fun HomeContent(
    settings: Settings,
    status: AssistantStatus,
    photos: List<Photo>,
    timers: List<TimerEntry>,
    ringing: List<TimerEntry>,
    night: Boolean,
    setupHint: String?,
    setupCard: Pair<String?, String>?,
    nowPlaying: NowPlaying?,
    onMediaAction: (String) -> Unit,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000 - now % 1000)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }, onLongPress = { onLongPress() }) }
    ) {
        if (!night) {
            if (photos.isNotEmpty()) {
                Slideshow(photos, settings.slideIntervalSeconds)
            } else {
                EmptyBackground()
            }
        }

        // Scrim so the clock stays readable on bright photos.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.35f),
                        0.25f to Color.Transparent,
                        0.6f to Color.Transparent,
                        1.0f to Color.Black.copy(alpha = 0.65f),
                    )
                )
        )

        Clock(
            nowMillis = now,
            showSeconds = settings.showSeconds,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 48.sdp, bottom = 40.sdp)
                .alpha(if (night) 0.45f else 1f),
        )

        TimerChips(
            timers = timers,
            nowMillis = now,
            modifier = Modifier.align(Alignment.TopEnd).padding(24.sdp),
        )

        StatusHint(status, setupHint, photosEmpty = photos.isEmpty() && !night, modifier = Modifier.align(Alignment.TopStart).padding(24.sdp))

        Transcript(status, modifier = Modifier.align(Alignment.Center).padding(horizontal = 64.sdp))

        if (nowPlaying != null && !night) {
            NowPlayingWidget(
                now = nowPlaying,
                onAction = onMediaAction,
                unit = 16.sdp,
                text = 18.ssp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.sdp, bottom = 40.sdp),
            )
        }

        LightBar(status.state, modifier = Modifier.align(Alignment.BottomCenter))

        AnimatedVisibility(
            visible = setupCard != null,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            setupCard?.let { (url, pin) -> SetupCard(url, pin) }
        }

        AnimatedVisibility(visible = ringing.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
            AlarmOverlay(ringing)
        }
    }
}

@Composable
private fun Slideshow(photos: List<Photo>, intervalSeconds: Int) {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val widthPx = with(density) { config.screenWidthDp.dp.roundToPx() }
    val heightPx = with(density) { config.screenHeightDp.dp.roundToPx() }
    val interval = intervalSeconds.coerceAtLeast(5) * 1000L

    var index by remember(photos) { mutableIntStateOf(0) }
    // FILL keeps the overflowing side at full resolution so panning stays sharp.
    fun request(uri: Uri) = ImageRequest.Builder(context).data(uri).size(widthPx, heightPx).scale(Scale.FILL).build()

    LaunchedEffect(photos, interval) {
        while (true) {
            // Preload the next image so the crossfade does not show a black frame.
            context.imageLoader.enqueue(request(photos[(index + 1) % photos.size].uri))
            delay(interval)
            index = (index + 1) % photos.size
        }
    }

    Crossfade(targetState = photos[index % photos.size], animationSpec = tween(1500), label = "slideshow") { photo ->
        val painter = rememberAsyncImagePainter(request(photo.uri))
        // The move is slow, so ~25 fps look the same as 60 but cost far less on this small device.
        var progress by remember(photo) { mutableFloatStateOf(0f) }
        LaunchedEffect(photo) {
            val duration = (interval + 1500).toFloat()
            val start = withFrameMillis { it }
            while (true) {
                val t = ((withFrameMillis { it } - start) / duration).coerceAtMost(1f)
                progress = EaseInOutSine.transform(t)
                if (t >= 1f) break
                delay(KEN_BURNS_FRAME_MS)
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val image = painter.intrinsicSize
            if (image.isUnspecified || image.width <= 0f || image.height <= 0f) return@Canvas
            val imageAspect = image.width / image.height
            val viewAspect = size.width / size.height
            // Ken Burns move planned around the detected faces (random direction per photo).
            val shot = shotCache.getOrPut(photo) { ShotPlanner.plan(imageAspect, viewAspect, photo.faces) }
            val window = shot.window(progress, imageAspect, viewAspect)
            val scale = size.width / (window.width * image.width)
            translate(-window.left * image.width * scale, -window.top * image.height * scale) {
                with(painter) { draw(Size(image.width * scale, image.height * scale)) }
            }
        }
    }
}

private const val KEN_BURNS_FRAME_MS = 40L

/** One planned camera move per photo and display, so recomposition does not re-roll it. */
private val shotCache = object : LinkedHashMap<Photo, Shot>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Photo, Shot>?) = size > 4
}

@Composable
private fun EmptyBackground() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364))))
    )
}

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN)
private val secondsFormat = DateTimeFormatter.ofPattern(":ss", Locale.GERMAN)
private val dateFormat = DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMAN)

@Composable
private fun Clock(nowMillis: Long, showSeconds: Boolean, modifier: Modifier) {
    val now = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMillis), java.time.ZoneId.systemDefault())
    // Soft shadow keeps white text readable on bright photos.
    val readable = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 2f), blurRadius = 10f))
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                now.format(timeFormat),
                color = Color.White,
                fontSize = 128.ssp,
                fontWeight = FontWeight.ExtraLight,
                lineHeight = 128.ssp,
                style = readable,
            )
            if (showSeconds) {
                Text(
                    now.format(secondsFormat),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 48.ssp,
                    fontWeight = FontWeight.ExtraLight,
                    style = readable,
                    modifier = Modifier.padding(bottom = 18.sdp),
                )
            }
        }
        Text(
            now.format(dateFormat),
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 30.ssp,
            fontWeight = FontWeight.Normal,
            style = readable,
        )
    }
}

@Composable
private fun TimerChips(timers: List<TimerEntry>, nowMillis: Long, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.sdp), horizontalAlignment = Alignment.End) {
        for (t in timers) {
            val remaining = maxOf(0L, (t.endAtMillis - nowMillis + 999) / 1000)
            Row(
                Modifier
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
                    .padding(horizontal = 20.sdp, vertical = 10.sdp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    (t.label ?: "Timer") + "  ",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 22.ssp,
                )
                Text(formatDuration(remaining), color = Color.White, fontSize = 26.ssp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

internal fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
private fun StatusHint(status: AssistantStatus, setupHint: String?, photosEmpty: Boolean, modifier: Modifier) {
    val lines = buildList {
        setupHint?.let { add(it) }
        status.error?.takeIf { status.state == AssistantState.ERROR }?.let { add(it) }
        status.hotwordError?.let { add(it) }
        if (!status.running && status.error == null) add("Assistent gestoppt")
        if (photosEmpty) add("Fotos über die Einrichtung im Browser hochladen")
    }
    if (lines.isEmpty()) return
    Column(
        modifier
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(12.sdp))
            .padding(horizontal = 14.sdp, vertical = 8.sdp)
    ) {
        lines.forEach { Text(it, color = Color.White.copy(alpha = 0.85f), fontSize = 16.ssp) }
    }
}

@Composable
private fun Transcript(status: AssistantStatus, modifier: Modifier) {
    val active = status.state in setOf(AssistantState.CONNECTING, AssistantState.LISTENING, AssistantState.SPEAKING)
    AnimatedVisibility(visible = active && (status.userText.isNotBlank() || status.modelText.isNotBlank()),
        enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Column(
            Modifier
                .widthIn(max = 900.sdp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(24.sdp))
                .padding(horizontal = 32.sdp, vertical = 24.sdp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.sdp),
        ) {
            if (status.userText.isNotBlank()) {
                Text(
                    status.userText.trim().takeLast(200),
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 22.ssp,
                    textAlign = TextAlign.Center,
                )
            }
            if (status.modelText.isNotBlank()) {
                Text(
                    status.modelText.trim().takeLast(400),
                    color = Color.White,
                    fontSize = 30.ssp,
                    textAlign = TextAlign.Center,
                    lineHeight = 38.ssp,
                )
            }
        }
    }
}

/** Echo-style glowing bar at the bottom edge showing the assistant state. */
@Composable
private fun LightBar(state: AssistantState, modifier: Modifier) {
    // Return before creating the infinite transition, otherwise it animates every frame while idle.
    if (state == AssistantState.IDLE) return
    val color = when (state) {
        AssistantState.IDLE -> Color.Transparent
        AssistantState.CONNECTING -> Palette.Connecting
        AssistantState.LISTENING -> Palette.Listening
        AssistantState.SPEAKING -> Palette.Speaking
        AssistantState.ERROR -> Palette.Error
    }
    val period = when (state) {
        AssistantState.SPEAKING -> 500
        AssistantState.CONNECTING -> 700
        else -> 1400
    }
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(period), RepeatMode.Reverse),
        label = "pulseAlpha",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(56.sdp)
            .alpha(pulse)
            .background(Brush.verticalGradient(listOf(Color.Transparent, color.copy(alpha = 0.55f), color)))
    )
}

/** Where to configure the device; settings themselves are only available in the browser. */
@Composable
private fun SetupCard(url: String?, pin: String) {
    Column(
        Modifier
            .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(24.sdp))
            .padding(horizontal = 40.sdp, vertical = 28.sdp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.sdp),
    ) {
        Text("Einrichtung im Browser", color = Color.White.copy(alpha = 0.75f), fontSize = 22.ssp)
        Text(url ?: "Kein WLAN verbunden", color = Color.White, fontSize = 32.ssp, fontWeight = FontWeight.Medium)
        Text("PIN  $pin", color = Palette.Listening, fontSize = 36.ssp, fontWeight = FontWeight.Medium, letterSpacing = 4.ssp)
    }
}

@Composable
private fun AlarmOverlay(ringing: List<TimerEntry>) {
    val pulse by rememberInfiniteTransition(label = "alarm").animateFloat(
        initialValue = 0.55f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "alarmAlpha",
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Alarm.copy(alpha = pulse * 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Timer abgelaufen", color = Color.White, fontSize = 64.ssp, fontWeight = FontWeight.Light)
            val labels = ringing.mapNotNull { it.label }
            if (labels.isNotEmpty()) {
                Text(labels.joinToString(), color = Color.White, fontSize = 36.ssp, modifier = Modifier.padding(top = 8.sdp))
            }
            Text(
                "Tippen oder Hotword sagen zum Stoppen",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 22.ssp,
                modifier = Modifier.padding(top = 32.sdp),
            )
        }
    }
}

package de.homevoice.vision

import kotlin.random.Random

/** Visible part of the image, normalized to 0..1 of its width/height. */
data class Window(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right get() = left + width
    val bottom get() = top + height
    fun contains(f: Face, tolerance: Float = 1e-4f) =
        f.left >= left - tolerance && f.right <= right + tolerance && f.top >= top - tolerance && f.bottom <= bottom + tolerance
}

/**
 * A Ken Burns camera move: the window center (normalized image coordinates) and zoom
 * (1 = image just covers the screen) at the start and end of the photo's display time.
 */
data class Shot(
    val startX: Float, val startY: Float, val startZoom: Float,
    val endX: Float, val endY: Float, val endZoom: Float,
) {
    /** The visible window at progress [t] (0..1), clamped so the image always covers the screen. */
    fun window(t: Float, imageAspect: Float, viewAspect: Float): Window {
        val zoom = startZoom + (endZoom - startZoom) * t
        val (w, h) = ShotPlanner.visibleSize(imageAspect, viewAspect, zoom)
        val cx = (startX + (endX - startX) * t).coerceIn(w / 2, 1 - w / 2)
        val cy = (startY + (endY - startY) * t).coerceIn(h / 2, 1 - h / 2)
        return Window(cx - w / 2, cy - h / 2, w, h)
    }
}

/** Plans camera moves that keep every detected face in view for the whole display time. */
object ShotPlanner {
    private const val PLAIN_ZOOM = 1.08f
    private const val MAX_FACE_ZOOM = 1.4f
    /** Minimum zoom change so no photo stands completely still. */
    private const val MIN_ZOOM_CHANGE = 1.05f

    /** Size of the visible window (fractions of the image) for an image covering the view at [zoom]. */
    fun visibleSize(imageAspect: Float, viewAspect: Float, zoom: Float): Pair<Float, Float> =
        (minOf(1f, viewAspect / imageAspect) / zoom) to (minOf(1f, imageAspect / viewAspect) / zoom)

    /** Area that must stay visible: faces plus head room and a bit of shoulders, clamped to the image. */
    private fun subjectArea(faces: List<Face>): Face {
        val fh = faces.map { it.height }.average().toFloat()
        return Face(
            left = (faces.minOf { it.left } - 0.5f * fh).coerceAtLeast(0f),
            top = (faces.minOf { it.top } - 0.4f * fh).coerceAtLeast(0f),
            right = (faces.maxOf { it.right } + 0.5f * fh).coerceAtMost(1f),
            bottom = (faces.maxOf { it.bottom } + 0.8f * fh).coerceAtMost(1f),
            score = 1f,
        )
    }

    /** The faces to keep in view: all if they fit on screen, otherwise the largest that do. */
    fun mainFaces(imageAspect: Float, viewAspect: Float, faces: List<Face>): List<Face> {
        val (w, h) = visibleSize(imageAspect, viewAspect, 1f)
        var kept = faces.sortedByDescending { it.width * it.height }
        while (kept.size > 1) {
            val area = subjectArea(kept)
            if (area.width <= w + 1e-4f && area.height <= h + 1e-4f) break
            kept = kept.dropLast(1)
        }
        return kept
    }

    fun plan(imageAspect: Float, viewAspect: Float, allFaces: List<Face>, random: Random = Random): Shot {
        val forward = random.nextBoolean()
        val zoomIn = random.nextBoolean()
        val faces = if (allFaces.isEmpty()) allFaces else mainFaces(imageAspect, viewAspect, allFaces)
        if (faces.isEmpty()) {
            // Pan across whatever overflows, end to end.
            val (zs, ze) = if (zoomIn) 1f to PLAIN_ZOOM else PLAIN_ZOOM to 1f
            val (ws, hs) = visibleSize(imageAspect, viewAspect, zs)
            val (we, he) = visibleSize(imageAspect, viewAspect, ze)
            val sx = if (forward) ws / 2 else 1 - ws / 2
            val ex = if (forward) 1 - we / 2 else we / 2
            val sy = if (forward) hs / 2 else 1 - hs / 2
            val ey = if (forward) 1 - he / 2 else he / 2
            return Shot(sx, sy, zs, ex, ey, ze)
        }

        val area = subjectArea(faces)
        val left = area.left; val right = area.right; val top = area.top; val bottom = area.bottom

        // Zoom in only as far as the whole area still fits (but always a little, so it moves).
        val (w1, h1) = visibleSize(imageAspect, viewAspect, 1f)
        val fit = minOf(w1 / (right - left), h1 / (bottom - top))
        val maxZoom = fit.coerceIn(MIN_ZOOM_CHANGE, MAX_FACE_ZOOM)
        val (zs, ze) = if (zoomIn) 1f to maxZoom else maxZoom to 1f

        // Allowed centers keep the area inside the window (and the window inside the image).
        fun range(lo: Float, hi: Float, size: Float): Pair<Float, Float> {
            val a = maxOf(size / 2, hi - size / 2)
            val b = minOf(1 - size / 2, lo + size / 2)
            return if (a <= b) a to b else ((lo + hi) / 2).let { c -> c to c } // too wide: center it
        }
        val (ws, hs) = visibleSize(imageAspect, viewAspect, zs)
        val (we, he) = visibleSize(imageAspect, viewAspect, ze)
        val sxr = range(left, right, ws); val exr = range(left, right, we)
        val syr = range(top, bottom, hs); val eyr = range(top, bottom, he)
        return Shot(
            startX = if (forward) sxr.first else sxr.second,
            startY = if (forward) syr.first else syr.second,
            startZoom = zs,
            endX = if (forward) exr.second else exr.first,
            endY = if (forward) eyr.second else eyr.first,
            endZoom = ze,
        )
    }
}

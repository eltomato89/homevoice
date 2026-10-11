package de.homevoice.vision

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ShotPlannerTest {
    private val view = 2f // Echo Show 5: 960×480

    private fun assertFacesAlwaysVisible(aspect: Float, faces: List<Face>, mustSee: List<Face> = faces) {
        repeat(50) { seed ->
            val shot = ShotPlanner.plan(aspect, view, faces, Random(seed))
            for (i in 0..20) {
                val w = shot.window(i / 20f, aspect, view)
                assertTrue("window outside image: $w", w.left >= -1e-4f && w.top >= -1e-4f && w.right <= 1.0001f && w.bottom <= 1.0001f)
                mustSee.forEach { f -> assertTrue("face $f not in $w (seed $seed, t=${i / 20f}, $shot)", w.contains(f)) }
            }
        }
    }

    @Test
    fun portraitWithFaceKeepsFaceInViewInsteadOfPanningToFeet() {
        // Portrait 3:4, one face near the top: only a 0.375-high band fits on a 2:1 screen.
        val face = Face(0.4f, 0.12f, 0.6f, 0.27f, 1f)
        assertFacesAlwaysVisible(0.75f, listOf(face))
        val shot = ShotPlanner.plan(0.75f, view, listOf(face), Random(1))
        assertTrue("never shows the feet", maxOf(shot.startY, shot.endY) < 0.5f)
    }

    @Test
    fun groupPhotoKeepsEveryoneInView() {
        val faces = (0 until 6).map { i -> Face(0.1f + i * 0.13f, 0.30f, 0.17f + i * 0.13f, 0.40f, 1f) }
        assertFacesAlwaysVisible(4f / 3f, faces)
    }

    @Test
    fun zoomsInOnSmallFaceButNotTooFar() {
        val face = Face(0.45f, 0.4f, 0.5f, 0.47f, 1f)
        val shot = ShotPlanner.plan(1.5f, view, listOf(face), Random(3))
        assertTrue(maxOf(shot.startZoom, shot.endZoom) in 1.3f..1.4f)
        assertFacesAlwaysVisible(1.5f, listOf(face))
    }

    @Test
    fun whenNotAllFitKeepsTheLargestFaces() {
        // 4:3 family photo: big faces left/middle, a smaller face at the very top, one at the bottom.
        val mother = Face(0.10f, 0.15f, 0.30f, 0.40f, 1f)
        val child = Face(0.42f, 0.25f, 0.58f, 0.47f, 1f)
        val father = Face(0.62f, 0.00f, 0.75f, 0.10f, 1f)
        val baby = Face(0.60f, 0.85f, 0.72f, 1.00f, 1f)
        val all = listOf(mother, child, father, baby)
        val main = ShotPlanner.mainFaces(4f / 3f, view, all)
        assertTrue(mother in main && child in main)
        assertTrue(main.size < all.size)
        assertFacesAlwaysVisible(4f / 3f, all, mustSee = listOf(mother, child))
    }

    @Test
    fun alwaysMovesALittle() {
        // Faces fill the whole visible band: no pan possible, but the zoom still changes.
        val faces = listOf(Face(0.05f, 0.3f, 0.45f, 0.62f, 1f), Face(0.55f, 0.3f, 0.95f, 0.62f, 1f))
        repeat(10) { seed ->
            val shot = ShotPlanner.plan(1.5f, view, faces, Random(seed))
            assertTrue(shot.startZoom != shot.endZoom || shot.startX != shot.endX || shot.startY != shot.endY)
        }
    }

    @Test
    fun withoutFacesPansAcrossWholeOverflow() {
        val shot = ShotPlanner.plan(0.75f, view, emptyList(), Random(4))
        val a = shot.window(0f, 0.75f, view)
        val b = shot.window(1f, 0.75f, view)
        assertTrue(minOf(a.top, b.top) < 0.01f && maxOf(a.bottom, b.bottom) > 0.99f)
    }
}

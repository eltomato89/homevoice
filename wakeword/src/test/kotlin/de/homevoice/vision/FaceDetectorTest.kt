package de.homevoice.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/** Uses public-domain photos from Wikimedia Commons (test/resources/faces). */
class FaceDetectorTest {
    private val detector = FaceDetector(File(System.getProperty("models.dir"), "face_rfb640.onnx").readBytes())

    private fun detect(name: String): List<Face> {
        val img = ImageIO.read(javaClass.getResource("/faces/$name")!!)
        val px = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
        return detector.detect(px, img.width, img.height)
    }

    @Test
    fun findsSingleFaceInPortraitPhoto() {
        val faces = detect("einstein.jpg")
        println("einstein: $faces")
        assertEquals(1, faces.size)
        val f = faces.single()
        // The head fills the upper-middle part of the portrait.
        assertTrue(f.left > 0.1f && f.right < 0.9f && f.top > 0.05f && f.bottom < 0.8f)
        assertTrue(f.height > 0.2f)
    }

    @Test
    fun findsMostFacesInGroupPhoto() {
        val faces = detect("solvay1927.jpg") // 29 people
        println("solvay: ${faces.size} faces")
        assertTrue("only ${faces.size} faces", faces.size >= 20)
        assertTrue(faces.all { it.height < 0.15f })
    }
}

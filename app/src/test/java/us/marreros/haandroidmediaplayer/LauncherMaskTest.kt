package us.marreros.haandroidmediaplayer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import androidx.core.content.ContextCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherMaskTest {
    @Test fun artworkRemainsCenteredAndLegibleAcrossAdaptiveMasks() {
        val context = RuntimeEnvironment.getApplication()
        for (resource in listOf(R.drawable.ic_launcher_foreground, R.drawable.ic_launcher_monochrome)) {
            val drawable = ContextCompat.getDrawable(context, resource)!!
            val unmasked = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, 432, 432)
            drawable.draw(Canvas(unmasked))
            val total = count(unmasked)
            assertTrue(total > 5000)
            for (mask in listOf("circle", "rounded", "square")) {
                val bitmap = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val path = Path().apply {
                    when (mask) {
                        "circle" -> addCircle(216f, 216f, 144f, Path.Direction.CW)
                        "rounded" -> addRoundRect(RectF(72f, 72f, 360f, 360f), 64f, 64f, Path.Direction.CW)
                        else -> addRect(72f, 72f, 360f, 360f, Path.Direction.CW)
                    }
                }
                canvas.clipPath(path)
                drawable.draw(canvas)
                assertTrue("$mask must preserve at least 99% of the artwork", count(bitmap) >= total * .99)
                var minX = 432; var maxX = 0; var minY = 432; var maxY = 0
                for (y in 0 until 432) for (x in 0 until 432) if (Color.alpha(bitmap.getPixel(x, y)) > 0) {
                    minX = minOf(minX, x); maxX = maxOf(maxX, x)
                    minY = minOf(minY, y); maxY = maxOf(maxY, y)
                }
                assertTrue(kotlin.math.abs((minX + maxX) / 2 - 216) < 5)
                assertTrue(kotlin.math.abs((minY + maxY) / 2 - 216) < 5)
                assertTrue("Artwork should fill at least 90% of mask height", maxY - minY >= 260)
            }
        }
    }
    private fun count(bitmap: Bitmap): Int {
        var pixels = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) if (Color.alpha(bitmap.getPixel(x, y)) > 0) pixels++
        return pixels
    }
}

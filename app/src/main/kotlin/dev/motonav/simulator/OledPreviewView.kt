package dev.motonav.simulator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import dev.motonav.simulator.display.MonochromeFrame

class OledPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val bitmap = Bitmap.createBitmap(128, 160, Bitmap.Config.ARGB_8888)
    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private var frame = MonochromeFrame()

    init {
        setBackgroundColor(Color.rgb(3, 8, 6))
        contentDescription = "128 by 160 MotoNav TFT preview"
    }

    fun show(newFrame: MonochromeFrame) {
        frame = newFrame
        val pixels = IntArray(128 * 160) { index ->
            if (frame.isLit(index % 128, index / 128)) Color.WHITE else Color.BLACK
        }
        bitmap.setPixels(pixels, 0, 128, 0, 0, 128, 160)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = availableWidth * 160 / 128
        setMeasuredDimension(
            resolveSize(availableWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val integerScale = minOf(width / 128, height / 160).coerceAtLeast(1)
        val drawWidth = 128 * integerScale
        val drawHeight = 160 * integerScale
        val left = (width - drawWidth) / 2
        val top = (height - drawHeight) / 2
        canvas.drawBitmap(bitmap, null, Rect(left, top, left + drawWidth, top + drawHeight), paint)
    }
}

package com.example.helper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

class PatternDrawView(context: Context) : View(context) {
    
    // 힌트(빨간 네모/선)를 그릴 페인트
    private val hintPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
    }

    private var hasHint = false
    private var fx = 0f
    private var fy = 0f
    private var tx = 0f
    private var ty = 0f
    private var blockSize = 0f

    fun setHint(fromX: Float, fromY: Float, toX: Float, toY: Float, size: Float) {
        fx = fromX
        fy = fromY
        tx = toX
        ty = toY
        blockSize = size
        hasHint = true
        invalidate() // 화면 강제 갱신
    }

    fun clearHint() {
        hasHint = false
        invalidate() // 화면 강제 갱신
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 5개 매칭 힌트가 발견되면 화면에 사각형과 선 표시
        if (hasHint) {
            canvas.drawRect(fx - blockSize / 2, fy - blockSize / 2, fx + blockSize / 2, fy + blockSize / 2, hintPaint)
            canvas.drawRect(tx - blockSize / 2, ty - blockSize / 2, tx + blockSize / 2, ty + blockSize / 2, hintPaint)
            canvas.drawLine(fx, fy, tx, ty, hintPaint)
        }
    }
}

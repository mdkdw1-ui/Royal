package com.example.helper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

class PatternDrawView(context: Context) : View(context) {
    private var hasHint = false
    private var fromX = 0f
    private var fromY = 0f
    private var toX = 0f
    private var toY = 0f
    private var size = 0f

    private val paint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
    }

    private val dotPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun setHint(fx: Float, fy: Float, tx: Float, ty: Float, blockSize: Float) {
        fromX = fx
        fromY = fy
        toX = tx
        toY = ty
        size = blockSize
        hasHint = true
        invalidate()
    }

    fun clearHint() {
        hasHint = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!hasHint) return

        // 1. 조작할 두 블록 영역 사각형 표시
        canvas.drawRect(fromX - size/2, fromY - size/2, fromX + size/2, fromY + size/2, paint)
        canvas.drawRect(toX - size/2, toY - size/2, toX + size/2, toY + size/2, paint)

        // 2. 방향 가이드선 및 이동 중심점 그리기
        canvas.drawLine(fromX, fromY, toX, toY, paint)
        canvas.drawCircle(fromX, fromY, 15f, dotPaint)
    }
}

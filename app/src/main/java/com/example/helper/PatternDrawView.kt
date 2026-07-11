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
    private var blockSize = 0f

    private val linePaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 7f
        isAntiAlias = true
        // 💡 [업그레이드 포인트] 가이드라인의 모서리와 선 끝을 동그랗게 마감하여 가시성 업그레이드
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val rectPaint = Paint().apply {
        color = Color.argb(50, 255, 0, 0)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun setHint(fx: Float, fy: Float, tx: Float, ty: Float, size: Float) {
        this.fromX = fx
        this.fromY = fy
        this.toX = tx
        this.toY = ty
        this.blockSize = size
        this.hasHint = true
        invalidate() 
    }

    fun clearHint() {
        this.hasHint = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!hasHint) return

        val halfSize = blockSize / 2

        canvas.drawRect(
            fromX - halfSize, fromY - halfSize,
            fromX + halfSize, fromY + halfSize,
            rectPaint
        )

        canvas.drawLine(fromX, fromY, toX, toY, linePaint)

        canvas.drawRect(
            toX - halfSize, toY - halfSize,
            toX + halfSize, toY + halfSize,
            linePaint
        )
    }
}

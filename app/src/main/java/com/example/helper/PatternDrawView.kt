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

    // 빨간색 가이드 라인 펜 설정
    private val linePaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 7f
        isAntiAlias = true
    }

    // 타겟 격자 영역 반투명 하이라이트 펜 설정
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
        invalidate() // 메인 스레드 화면 강제 갱신
    }

    fun clearHint() {
        this.hasHint = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!hasHint) return

        val halfSize = blockSize / 2

        // 1. 드래그를 시작해야 하는 원본 블록에 반투명 빨간 네모 하이라이트
        canvas.drawRect(
            fromX - halfSize, fromY - halfSize,
            fromX + halfSize, fromY + halfSize,
            rectPaint
        )

        // 2. 스왑해서 움직여야 하는 경로에 직관적인 빨간색 이동 가이드 선 긋기
        canvas.drawLine(fromX, fromY, toX, toY, linePaint)

        // 3. 목적지 도착 지점에도 네모 박스를 그려 가시성 극대화
        canvas.drawRect(
            toX - halfSize, toY - halfSize,
            toX + halfSize, toY + halfSize,
            linePaint
        )
    }
}

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

    // 작동 중 표시등(초록색 점)을 그릴 페인트
    private val statusPaint = Paint().apply {
        color = Color.parseColor("#00FF66") // 밝은 네온 초록색
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // 작동 중 글씨를 그릴 페인트
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        isAntiAlias = true
        isFakeBoldText = true // 💡 [오타 수정] fakeBoldText를 isFakeBoldText로 변경했습니다!
    }

    // 글씨 뒤에 깔아줄 반투명 검은색 배경 페인트
    private val bgPaint = Paint().apply {
        color = Color.parseColor("#AA000000") // 약 66% 불투명도의 검은색
        style = Paint.Style.FILL
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

        // 좌측 상단에 실시간 작동 상태 표시기 그리기
        val widgetLeft = 40f
        val widgetTop = 130f
        val widgetRight = 360f
        val widgetBottom = 200f

        // 1. 둥근 반투명 검은색 배경 박스
        canvas.drawRoundRect(widgetLeft, widgetTop, widgetRight, widgetBottom, 15f, 15f, bgPaint)

        // 2. 초록색 불빛(원)
        canvas.drawCircle(widgetLeft + 40f, widgetTop + 35f, 10f, statusPaint)

        // 3. 텍스트 출력
        canvas.drawText("도우미 작동 중", widgetLeft + 75f, widgetTop + 47f, textPaint)

        // 5개 매칭 힌트가 발견되면 화면에 표시
        if (hasHint) {
            canvas.drawRect(fx - blockSize / 2, fy - blockSize / 2, fx + blockSize / 2, fy + blockSize / 2, hintPaint)
            canvas.drawRect(tx - blockSize / 2, ty - blockSize / 2, tx + blockSize / 2, ty + blockSize / 2, hintPaint)
            canvas.drawLine(fx, fy, tx, ty, hintPaint)
        }
    }
}

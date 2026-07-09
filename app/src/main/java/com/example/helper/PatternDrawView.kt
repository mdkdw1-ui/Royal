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

    // 💡 작동 중 표시등(초록색 점)을 그릴 페인트
    private val statusPaint = Paint().apply {
        color = Color.parseColor("#00FF66") // 밝은 네온 초록색
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // 💡 작동 중 글씨를 그릴 페인트
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        isAntiAlias = true
        fakeBoldText = true // 굵게
    }

    // 💡 글씨 뒤에 깔아줄 반투명 검은색 배경 페인트
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
        invalidate() // 화면 강제 갱신 (onDraw 호출)
    }

    fun clearHint() {
        hasHint = false
        invalidate() // 화면 강제 갱신 (onDraw 호출)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 💡 [기능 추가] 좌측 상단에 실시간 작동 상태 표시기 그리기
        // 상단 상태바(노티바)에 가려지지 않도록 대략 Y축 130 픽셀 지점에 배치합니다.
        val widgetLeft = 40f
        val widgetTop = 130f
        val widgetRight = 360f
        val widgetBottom = 200f

        // 1. 글씨가 잘 보이도록 둥근 반투명 검은색 배경 박스를 그립니다.
        canvas.drawRoundRect(widgetLeft, widgetTop, widgetRight, widgetBottom, 15f, 15f, bgPaint)

        // 2. 살아있음을 알리는 초록색 불빛(원)을 그립니다.
        canvas.drawCircle(widgetLeft + 40f, widgetTop + 35f, 10f, statusPaint)

        // 3. "도우미 작동 중" 텍스트를 그립니다.
        canvas.drawText("도우미 작동 중", widgetLeft + 75f, widgetTop + 47f, textPaint)


        // 기존 기능: 5개 매칭 힌트가 발견되면 화면에 빨간색 사각형과 연결선 표시
        if (hasHint) {
            // 출발 블록 테두리
            canvas.drawRect(fx - blockSize / 2, fy - blockSize / 2, fx + blockSize / 2, fy + blockSize / 2, hintPaint)
            // 도착 블록 테두리
            canvas.drawRect(tx - blockSize / 2, ty - blockSize / 2, tx + blockSize / 2, ty + blockSize / 2, hintPaint)
            // 두 블록을 이어주는 이동 가이드 선
            canvas.drawLine(fx, fy, tx, ty, hintPaint)
        }
    }
}

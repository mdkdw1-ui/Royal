package com.example.helper

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.app.NotificationCompat
import java.util.LinkedList

class OverlayService : Service() {
    private val TAG = "OverlayHelper"
    private var windowManager: WindowManager? = null
    private var overlayView: PatternDrawView? = null
    private var controlView: LinearLayout? = null 
    
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    
    private var screenWidth = 1080
    private var screenHeight = 2400
    private var pixelArray: IntArray? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "helper_channel")
            .setContentTitle("5라인 매칭 헬퍼 작동 중")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("helper_channel", "헬퍼 서비스", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_OK) ?: Activity.RESULT_OK
        val dataIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("DATA_INTENT", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<Intent>("DATA_INTENT")
        } ?: return START_NOT_STICKY

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager?.defaultDisplay?.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        pixelArray = IntArray(screenWidth * screenHeight)

        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, dataIntent)
        
        backgroundThread = HandlerThread("AnalyzerThread").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
        
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "Capture", screenWidth, screenHeight, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, backgroundHandler
        )
        
        backgroundHandler?.post(analyzeRunnable)
        showOverlayViews()

        return START_NOT_STICKY
    }

    private fun showOverlayViews() {
        if (overlayView == null) {
            overlayView = PatternDrawView(this)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            windowManager?.addView(overlayView, params)
        }
    }

    private val analyzeRunnable = object : Runnable {
        override fun run() {
            try { processScreenParsing() } catch (e: Exception) { Log.e(TAG, "분석 오류", e) }
            backgroundHandler?.postDelayed(this, 350) 
        }
    }

    private fun processScreenParsing() {
        val image = imageReader?.acquireLatestImage() ?: return
        val pixels = pixelArray ?: return
        
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenWidth
            val tmpWidth = screenWidth + rowPadding / pixelStride

            val bmp = Bitmap.createBitmap(tmpWidth, screenHeight, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buffer)
            // 비트맵 최적화: getPixels로 전체 화면 배열을 한 번에 추출
            bmp.getPixels(pixels, 0, screenWidth, 0, 0, screenWidth, screenHeight)
            bmp.recycle()

            // 1단계: 분석 영역 설정 (상단 UI 및 하단 아이템 바 제외 중앙부)
            val minY = (screenHeight * 0.25).toInt()
            val maxY = (screenHeight * 0.85).toInt()
            val minX = (screenWidth * 0.02).toInt()
            val maxX = (screenWidth * 0.98).toInt()

            // 2단계: 색상 변화 간격을 통한 정확한 [1칸 격자 크기] 추출 (히스토그램 방식)
            val distHistogram = IntArray(300)
            val sampleLines = 8
            val stepY = (maxY - minY) / sampleLines
            
            for (i in 0 until sampleLines) {
                val y = minY + i * stepY
                var lastX = -1
                var prevColor = -1
                for (x in minX..maxX) {
                    val color = getHsvColor(pixels[y * screenWidth + x])
                    if (color != prevColor) {
                        if (lastX != -1) {
                            val diff = x - lastX
                            if (diff in 95..195) distHistogram[diff]++ // 일반적인 1칸 크기 유효 범위
                        }
                        lastX = x
                        prevColor = color
                    }
                }
            }

            // 히스토그램에서 가장 빈도수가 높은 픽셀 거리를 격자 크기로 확정
            var cellSize = 0
            var maxVotes = 0
            for (d in 100..190) {
                val votes = distHistogram[d - 1] + distHistogram[d] + distHistogram[d + 1]
                if (votes > maxVotes) {
                    maxVotes = votes
                    cellSize = d
                }
            }
            if (cellSize == 0) cellSize = screenWidth / 8 // 탐색 실패시 기본값 보정

            // 3단계: 화면 안에서 실제 블록이 존재하는 절대 경계 영역(Bounding Box) 역산
            var firstX = maxX
            var lastX = minX
            var firstY = maxY
            var lastY = minY
            
            for (y in minY..maxY step 15) {
                for (x in minX..maxX step 15) {
                    if (getHsvColor(pixels[y * screenWidth + x]) in 1..5) {
                        if (x < firstX) firstX = x
                        if (x > lastX) lastX = x
                        if (y < firstY) firstY = y
                        if (y > lastY) lastY = y
                    }
                }
            }

            val totalW = lastX - firstX
            val totalH = lastY - firstY
            val cols = (totalW + cellSize / 2) / cellSize
            val rows = (totalH + cellSize / 2) / cellSize

            if (cols !in 4..11 || rows !in 4..14) return

            // 4단계: 동적 그리드 행렬(2D Array) 생성 및 픽셀 파싱
            val colorGrid = Array(rows) { r ->
                IntArray(cols) { c ->
                    val centerX = firstX + (c * cellSize) + (cellSize / 2)
                    val centerY = firstY + (r * cellSize) + (cellSize / 2)
                    if (centerX in 0 until screenWidth && centerY in 0 until screenHeight) {
                        getHsvColor(pixels[centerY * screenWidth + centerX])
                    } else 0
                }
            }

            // 5단계: 분석된 2D 구조를 바탕으로 가로/세로 5개 매칭 가능 무브 탐색
            val hint = findFiveMatchMove(colorGrid, rows, cols)
            if (hint != null) {
                val fx = (firstX + (hint.fromC * cellSize) + (cellSize / 2)).toFloat()
                val fy = (firstY + (hint.fromR * cellSize) + (cellSize / 2)).toFloat()
                val tx = (firstX + (hint.toC * cellSize) + (cellSize / 2)).toFloat()
                val ty = (firstY + (hint.toR * cellSize) + (cellSize / 2)).toFloat()
                overlayView?.post { overlayView?.setHint(fx, fy, tx, ty, cellSize.toFloat()) }
            } else {
                overlayView?.post { overlayView?.clearHint() }
            }

        } catch (e: Throwable) {
            Log.e(TAG, "Parsing fail", e)
        } finally {
            image.close()
        }
    }

    // HSV 기반 정밀 색상 스캐너 (그림자/하이라이트 방어용)
    private fun getHsvColor(pixel: Int): Int {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        if (r + g + b < 90 || r + g + b > 720) return 0 // 너무 어둡거나 밝은 무채색 패스

        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)
        val hue = hsv[0]
        val sat = hsv[1]
        val valF = hsv[2]

        if (sat < 0.22f || valF < 0.22f) return 0

        return when {
            (hue >= 345f || hue <= 15f) -> 1   // 빨강 (책)
            (hue in 195f..245f) -> 2           // 파랑 (방패)
            (hue in 38f..65f) -> 3             // 노랑 (왕관)
            (hue in 90f..150f) -> 4            // 초록 (나뭇잎)
            (hue in 265f..330f) -> 5           // 보라 (새/특수 분홍 타일)
            else -> 0
        }
    }

    // 가로/세로 5줄 연속 매칭 탐색기
    private fun findFiveMatchMove(grid: Array<IntArray>, rows: Int, cols: Int): MatchHint? {
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (grid[r][c] == 0) continue
                
                // 1. 오른쪽 스왑 검사
                if (c + 1 < cols && grid[r][c + 1] != 0 && grid[r][c] != grid[r][c + 1]) {
                    if (verifyFiveMatch(grid, r, c, r, c + 1, rows, cols)) return MatchHint(r, c, r, c + 1)
                }
                // 2. 아래쪽 스왑 검사
                if (r + 1 < rows && grid[r + 1][c] != 0 && grid[r][c] != grid[r + 1][c]) {
                    if (verifyFiveMatch(grid, r, c, r + 1, c, rows, cols)) return MatchHint(r, c, r + 1, c)
                }
            }
        }
        return null
    }

    // 실제로 5개가 일렬로 배치되는지 가상 스왑 후 검증
    private fun verifyFiveMatch(grid: Array<IntArray>, r1: Int, c1: Int, r2: Int, c2: Int, rows: Int, cols: Int): Boolean {
        val temp = grid[r1][c1]
        grid[r1][c1] = grid[r2][c2]
        grid[r2][c2] = temp

        val success = isLineOfFive(grid, r1, c1, rows, cols) || isLineOfFive(grid, r2, c2, rows, cols)

        // 원상 복구
        grid[r2][c2] = grid[r1][c1]
        grid[r1][c1] = temp
        return success
    }

    private fun isLineOfFive(grid: Array<IntArray>, r: Int, c: Int, rows: Int, cols: Int): Boolean {
        val color = grid[r][c]
        if (color == 0) return false

        // 가로 연속성 체크
        var hCount = 1
        var cc = c - 1
        while (cc >= 0 && grid[r][cc] == color) { hCount++; cc-- }
        cc = c + 1
        while (cc < cols && grid[r][cc] == color) { hCount++; cc++ }
        if (hCount >= 5) return true

        // 세로 연속성 체크
        var vCount = 1
        var rr = r - 1
        while (rr >= 0 && grid[rr][c] == color) { vCount++; rr-- }
        rr = r + 1
        while (rr < rows && grid[rr][c] == color) { vCount++; rr++ }
        if (vCount >= 5) return true

        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundHandler?.removeCallbacks(analyzeRunnable)
        backgroundThread?.quitSafely()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        if (overlayView != null) windowManager?.removeView(overlayView)
    }

    data class MatchHint(val fromR: Int, val fromC: Int, val toR: Int, val toC: Int)
}

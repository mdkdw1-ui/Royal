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
import kotlin.math.max
import kotlin.math.min

class OverlayService : Service() {
    private val TAG = "OverlayService"
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
    private val GRID_COLS = 8
    private val GRID_ROWS = 8
    private var reusableBitmap: Bitmap? = null
    private var pixelArray: IntArray? = null  // 픽셀 배열 재사용
    
    // 흔들림 방지 (Queue 기반 히스토리)
    private val gridHistory = LinkedList<Array<IntArray>>()
    private val MAX_HISTORY = 5

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
            val notification: Notification = NotificationCompat.Builder(this, "helper_channel")
                .setContentTitle("로얄매치 도우미 작동 중")
                .setContentText("백그라운드 스레드에서 초고속으로 매칭 패턴을 분석 중입니다.")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "onCreate failed", e)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "helper_channel", "로얄매치 서비스",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            windowManager?.defaultDisplay?.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels

            // 1. 투명 힌트 가이드 라인 뷰 생성
            overlayView = PatternDrawView(this)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            try { 
                windowManager?.addView(overlayView, params) 
            } catch (e: Exception) { 
                Log.e(TAG, "Failed to add overlay view", e)
            }

            // 2. 실행 상태 표시기 + 킬 스위치 생성 (위쪽, 작은 사이즈)
            showControlOverlay()

            val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_OK) ?: Activity.RESULT_OK
            
            val dataIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra("DATA_INTENT", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra<Intent>("DATA_INTENT")
            }
            
            if (dataIntent != null) {
                val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mpManager.getMediaProjection(resultCode, dataIntent)
                
                backgroundThread = HandlerThread("ScreenCaptureThread").apply { start() }
                backgroundHandler = Handler(backgroundThread!!.looper)

                imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "ScreenCapture", screenWidth, screenHeight, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, backgroundHandler
                )
                
                // 픽셀 배열 사전 할당
                pixelArray = IntArray(screenWidth * screenHeight)
                
                backgroundHandler?.post(analyzeRunnable)
                Log.d(TAG, "Service started successfully")
            } else {
                Log.e(TAG, "dataIntent is null")
                stopSelf()
            }
            START_NOT_STICKY
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand failed", e)
            stopSelf()
            START_NOT_STICKY
        }
    }

    private fun showControlOverlay() {
        try {
            val themedContext = ContextThemeWrapper(this, androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
            
            controlView = LinearLayout(themedContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(15, 8, 15, 8)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#AA000000")) 
                    cornerRadius = 15f
                }
            }

            val statusText = TextView(themedContext).apply {
                text = "● RUNNING"
                setTextColor(Color.GREEN)
                textSize = 10f
                setPadding(0, 0, 15, 0)
            }

            val stopButton = Button(themedContext).apply {
                text = "STOP"
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#FF3B30")) 
                textSize = 9f
                setPadding(8, 4, 8, 4)
                setOnClickListener {
                    stopSelf() 
                }
            }

            controlView?.addView(statusText)
            controlView?.addView(stopButton)

            val controlParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x = 0
                y = 30  // 화면 위쪽으로 더 이동
            }

            try {
                windowManager?.addView(controlView, controlParams)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add control view", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "showControlOverlay failed", e)
        }
    }

    private val analyzeRunnable = object : Runnable {
        override fun run() {
            try {
                analyzeScreenFast()
            } catch (e: Exception) {
                Log.e(TAG, "analyzeScreenFast error", e)
            }
            backgroundHandler?.postDelayed(this, 800)
        }
    }

    private fun analyzeScreenFast() {
        val image = imageReader?.acquireLatestImage() ?: return
        try {
            val planes = image.planes
            val buffer = planes[0].buffer          
            val pixelStride = planes[0].pixelStride  
            val rowStride = planes[0].rowStride      
            val rowPadding = rowStride - pixelStride * screenWidth

            val adjustedWidth = screenWidth + rowPadding / pixelStride

            if (reusableBitmap == null || reusableBitmap!!.width != adjustedWidth || reusableBitmap!!.height != screenHeight) {
                reusableBitmap = Bitmap.createBitmap(adjustedWidth, screenHeight, Bitmap.Config.ARGB_8888)
            }
            
            val bitmap = reusableBitmap!!
            buffer.rewind() 
            bitmap.copyPixelsFromBuffer(buffer)

            // 픽셀 배열에 한 번에 복사 (JNI 호출 1회만)
            if (pixelArray == null || pixelArray!!.size != screenWidth * screenHeight) {
                pixelArray = IntArray(screenWidth * screenHeight)
            }
            bitmap.getPixels(pixelArray, 0, screenWidth, 0, 0, screenWidth, screenHeight)

            // 동적 그리드 감지
            val boardBounds = detectDynamicGrid(pixelArray!!)
            val boardTop = boardBounds.top
            val boardBottom = boardBounds.bottom
            val boardLeft = boardBounds.left
            val boardRight = boardBounds.right

            val boardWidth = boardRight - boardLeft
            val blockSize = boardWidth / GRID_COLS
            val colorGrid = Array(GRID_ROWS) { IntArray(GRID_COLS) }

            // 블록 색상 추출 (HSV 기반)
            for (r in 0 until GRID_ROWS) {
                for (c in 0 until GRID_COLS) {
                    val pixelX = boardLeft + (c * blockSize) + (blockSize / 2)
                    val pixelY = boardTop + (r * blockSize) + (blockSize / 2)
                    
                    if (pixelX >= 0 && pixelX < screenWidth && pixelY >= 0 && pixelY < screenHeight) {
                        val pixelIndex = pixelY * screenWidth + pixelX
                        val pixel = pixelArray!![pixelIndex]
                        colorGrid[r][c] = getHsvColor(pixel)
                    }
                }
            }

            // 흔들림 방지: 그리드 히스토리에 추가
            addToGridHistory(colorGrid)
            val stableGrid = stableGrid()

            // 5개 연속 매칭 패턴 찾기 (강화된 로직)
            val hint = findFiveMatchPattern(stableGrid, GRID_ROWS, GRID_COLS)
            
            if (hint != null) {
                val fx = boardLeft + (hint.fromC * blockSize) + (blockSize / 2).toFloat()
                val fy = boardTop + (hint.fromR * blockSize) + (blockSize / 2).toFloat()
                val tx = boardLeft + (hint.toC * blockSize) + (blockSize / 2).toFloat()
                val ty = boardTop + (hint.toR * blockSize) + (blockSize / 2).toFloat()
                
                Log.d(TAG, "Match found: (${hint.fromR},${hint.fromC}) -> (${hint.toR},${hint.toC})")
                overlayView?.post {
                    overlayView?.setHint(fx, fy, tx, ty, blockSize.toFloat())
                }
            } else {
                overlayView?.post {
                    overlayView?.clearHint()
                }
            }

        } catch (e: Throwable) { 
            Log.e(TAG, "analyzeScreenFast exception", e)
        } finally {
            try {
                image.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing image", e)
            }
        }
    }

    // 동적 그리드 감지 (확장성 고려)
    private fun detectDynamicGrid(pixels: IntArray): GridBounds {
        val centerX = screenWidth / 2
        val startY = (screenHeight * 0.25).toInt()
        val endY = (screenHeight * 0.80).toInt()

        var boardTop = 0
        var boardBottom = 0

        // 세로 탐색 (중앙에서 검색)
        for (y in startY..endY) {
            val pixel = pixels[y * screenWidth + centerX]
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)

            // 검은색 배경 탐색 (보드 경계)
            if (r in 20..100 && g in 20..100 && b in 20..100) {
                if (boardTop == 0) boardTop = y
                boardBottom = y
            }
        }

        if (boardTop == 0 || boardBottom == 0 || (boardBottom - boardTop) < 300) {
            boardTop = (screenHeight * 0.35).toInt()
            boardBottom = (screenHeight * 0.75).toInt()
        }

        val targetMidY = boardTop + (boardBottom - boardTop) / 2
        var boardLeft = 0
        var boardRight = 0

        // 가로 탐색 (좌측)
        for (x in (screenWidth * 0.05).toInt() until centerX) {
            val pixel = pixels[targetMidY * screenWidth + x]
            if (Color.red(pixel) in 20..100) {
                boardLeft = x
                break
            }
        }

        // 가로 탐색 (우측)
        for (x in (screenWidth * 0.95).toInt() downTo centerX) {
            val pixel = pixels[targetMidY * screenWidth + x]
            if (Color.red(pixel) in 20..100) {
                boardRight = x
                break
            }
        }

        if (boardLeft == 0 || boardRight == 0 || (boardRight - boardLeft) < 500) {
            boardLeft = (screenWidth * 0.05).toInt()
            boardRight = (screenWidth * 0.95).toInt()
        }

        return GridBounds(boardLeft, boardTop, boardRight, boardBottom)
    }

    // HSV 기반 색상 필터링 (안정성 향상)
    private fun getHsvColor(pixel: Int): Int {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        
        if (r + g + b < 80) return 0  // 검은색 = 빈 칸
        
        // HSV 변환
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        
        val max = maxOf(rf, gf, bf)
        val min = minOf(rf, gf, bf)
        val delta = max - min
        
        // Hue 계산
        val hue = when {
            delta == 0f -> 0f
            max == rf -> (60f * (((gf - bf) / delta) % 6f) + 360f) % 360f
            max == gf -> (60f * (((bf - rf) / delta) + 2f)) % 360f
            else -> (60f * (((rf - gf) / delta) + 4f)) % 360f
        }
        
        // Saturation
        val saturation = if (max == 0f) 0f else (delta / max)
        
        // Value
        val value = max

        // Hue 범위로 색상 판별
        return when {
            // 빨강: 0-15, 345-360
            hue < 15 || hue > 345 -> 1
            // 노랑: 45-65
            hue in 45f..65f -> 3
            // 초록: 100-160
            hue in 100f..160f -> 4
            // 파랑: 200-260
            hue in 200f..260f -> 2
            // 보라: 270-310
            hue in 270f..310f -> 5
            else -> 0
        }
    }

    // 그리드 히스토리 관리
    private fun addToGridHistory(grid: Array<IntArray>) {
        gridHistory.add(grid.map { it.copyOf() }.toTypedArray())
        if (gridHistory.size > MAX_HISTORY) {
            gridHistory.removeFirst()
        }
    }

    // 안정적인 그리드 생성 (노이즈 제거)
    private fun stableGrid(): Array<IntArray> {
        if (gridHistory.isEmpty()) {
            return Array(GRID_ROWS) { IntArray(GRID_COLS) }
        }

        val stableGrid = Array(GRID_ROWS) { IntArray(GRID_COLS) }
        
        for (r in 0 until GRID_ROWS) {
            for (c in 0 until GRID_COLS) {
                val colorCounts = mutableMapOf<Int, Int>()
                
                // 히스토리 모든 프레임에서 색상 투표
                for (frame in gridHistory) {
                    val color = frame[r][c]
                    colorCounts[color] = (colorCounts[color] ?: 0) + 1
                }
                
                // 가장 빈도 높은 색상 선택
                stableGrid[r][c] = colorCounts.maxByOrNull { it.value }?.key ?: 0
            }
        }
        
        return stableGrid
    }

    data class MatchHint(val fromR: Int, val fromC: Int, val toR: Int, val toC: Int)
    data class GridBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

    // 5개 연속 매칭 패턴 찾기 (강화 버전)
    private fun findFiveMatchPattern(grid: Array<IntArray>, rows: Int, cols: Int): MatchHint? {
        val directions = arrayOf(Pair(0, 1), Pair(1, 0))
        
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (grid[r][c] == 0) continue
                
                for (dir in directions) {
                    val nr = r + dir.first
                    val nc = c + dir.second
                    
                    if (nr < rows && nc < cols && grid[nr][nc] != 0) {
                        // 스왑
                        val temp = grid[r][c]
                        grid[r][c] = grid[nr][nc]
                        grid[nr][nc] = temp

                        // 5개 연속 매칭 확인
                        if (checkGridMatch5(grid, rows, cols)) {
                            Log.d(TAG, "Found 5-match pattern at ($r,$c) -> ($nr,$nc)")
                            return MatchHint(r, c, nr, nc)
                        }
                        
                        // 복구
                        grid[nr][nc] = grid[r][c]
                        grid[r][c] = temp
                    }
                }
            }
        }
        return null
    }

    // 5개 연속 검증 (향상된 로직)
    private fun checkGridMatch5(grid: Array<IntArray>, rows: Int, cols: Int): Boolean {
        // 가로 5개
        for (r in 0 until rows) {
            for (c in 0..cols - 5) {
                val color = grid[r][c]
                if (color != 0 && 
                    color == grid[r][c+1] && 
                    color == grid[r][c+2] && 
                    color == grid[r][c+3] && 
                    color == grid[r][c+4]) {
                    Log.d(TAG, "Horizontal match at row $r cols $c-${c+4}")
                    return true
                }
            }
        }
        
        // 세로 5개
        for (c in 0 until cols) {
            for (r in 0..rows - 5) {
                val color = grid[r][c]
                if (color != 0 && 
                    color == grid[r+1][c] && 
                    color == grid[r+2][c] && 
                    color == grid[r+3][c] && 
                    color == grid[r+4][c]) {
                    Log.d(TAG, "Vertical match at col $c rows $r-${r+4}")
                    return true
                }
            }
        }
        
        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            backgroundHandler?.removeCallbacks(analyzeRunnable)
            backgroundThread?.quitSafely() 
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
            
            reusableBitmap?.recycle() 
            reusableBitmap = null
            pixelArray = null
            gridHistory.clear()

            if (overlayView != null && windowManager != null) {
                windowManager?.removeView(overlayView)
            }
            if (controlView != null && windowManager != null) {
                windowManager?.removeView(controlView)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy", e)
        }
    }
}

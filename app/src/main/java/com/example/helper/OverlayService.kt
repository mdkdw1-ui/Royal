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
    
    private var reusableBitmap: Bitmap? = null
    private var pixelArray: IntArray? = null
    
    // 💡 원칙 3: 격자 검출 안정화용 프레임 히스토리 버퍼
    private val gridHistory = LinkedList<Array<IntArray>>()
    private val MAX_HISTORY = 3

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
            val notification: Notification = NotificationCompat.Builder(this, "helper_channel")
                .setContentTitle("로얄매치 5매칭 헬퍼")
                .setContentText("오직 미러볼(5연속) 기회만 추적 중...")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "포그라운드 승격 실패", e)
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
        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_OK) ?: Activity.RESULT_OK
        val dataIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("DATA_INTENT", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<Intent>("DATA_INTENT")
        }
        
        if (dataIntent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            windowManager?.defaultDisplay?.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels

            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, dataIntent)
            
            backgroundThread = HandlerThread("ScreenCaptureThread").apply { start() }
            backgroundHandler = Handler(backgroundThread!!.looper)

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    stopSelf()
                }
            }, backgroundHandler)

            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture", screenWidth, screenHeight, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, backgroundHandler
            )
            
            pixelArray = IntArray(screenWidth * screenHeight)
            backgroundHandler?.post(analyzeRunnable)

            val finishIntent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("ACTION_FINISH", true)
            }
            startActivity(finishIntent)

        } catch (e: Exception) {
            Log.e(TAG, "미디어 프로젝션 연동 실패", e)
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            if (overlayView == null) {
                overlayView = PatternDrawView(this)
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                )
                windowManager?.addView(overlayView, params)
            }
            if (controlView == null) showControlOverlay()
        } catch (e: Exception) {
            Log.e(TAG, "오버레이 생성 실패", e)
        }

        return START_NOT_STICKY
    }

    private fun showControlOverlay() {
        try {
            val themedContext = ContextThemeWrapper(this, androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
            controlView = LinearLayout(themedContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(20, 10, 20, 10)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#AA000000")) 
                    cornerRadius = 20f
                }
            }

            val statusText = TextView(themedContext).apply {
                text = "● 5-MATCH ONLY"
                setTextColor(Color.CYAN)
                textSize = 11f
                setPadding(0, 0, 20, 0)
            }

            val stopButton = Button(themedContext).apply {
                text = "STOP"
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#FF3B30")) 
                textSize = 10f
                setOnClickListener { stopSelf() }
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
                gravity = Gravity.TOP or Gravity.END 
                x = 40
                y = 150 
            }
            windowManager?.addView(controlView, controlParams)
        } catch (e: Exception) {
            Log.e(TAG, "컨트롤바 구현 실패", e)
        }
    }

    private val analyzeRunnable = object : Runnable {
        override fun run() {
            try { analyzeScreenFast() } catch (e: Exception) { Log.e(TAG, "분석 에러", e) }
            backgroundHandler?.postDelayed(this, 400) 
        }
    }

    private fun analyzeScreenFast() {
        val image = imageReader?.acquireLatestImage() ?: return
        val currentPixels = pixelArray ?: IntArray(screenWidth * screenHeight).also { pixelArray = it }
        
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

            // 💡 원칙 2: 고속 1D IntArray 일괄 추출 적용
            bitmap.getPixels(currentPixels, 0, screenWidth, 0, 0, screenWidth, screenHeight)

            // 💡 원칙 4: 기기별 비율 기반 게임판 경계 영역 검출
            var boardTop = 0
            var boardBottom = 0
            var boardLeft = 0
            var boardRight = 0
            val centerX = screenWidth / 2
            val startY = (screenHeight * 0.25).toInt()
            val endY = (screenHeight * 0.85).toInt()

            for (y in startY..endY) {
                val pixel = currentPixels[y * screenWidth + centerX]
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                if (r in 30..110 && g in 25..95 && b in 20..90) {
                    if (boardTop == 0) boardTop = y
                    boardBottom = y
                }
            }

            if (boardTop == 0 || boardBottom == 0 || (boardBottom - boardTop) < 400) {
                boardTop = (screenHeight * 0.33).toInt()
                boardBottom = (screenHeight * 0.77).toInt()
            }

            val targetMidY = boardTop + (boardBottom - boardTop) / 2
            for (x in (screenWidth * 0.01).toInt() until centerX) {
                val r = Color.red(currentPixels[targetMidY * screenWidth + x])
                if (r in 30..110) { boardLeft = x; break }
            }
            for (x in (screenWidth * 0.99).toInt() downTo centerX) {
                val r = Color.red(currentPixels[targetMidY * screenWidth + x])
                if (r in 30..110) { boardRight = x; break }
            }

            if (boardLeft == 0 || boardRight == 0 || (boardRight - boardLeft) < 500) {
                boardLeft = (screenWidth * 0.05).toInt()
                boardRight = (screenWidth * 0.95).toInt()
            }

            // 💡 원칙 4: 검출된 격자의 평균 간격을 이용한 가변 Row/Col 크기 산출
            val avgBlockSizeX = estimateAverageSpacingX(currentPixels, boardLeft, boardRight, boardTop, boardBottom)
            val avgBlockSizeY = estimateAverageSpacingY(currentPixels, boardLeft, boardRight, boardTop, boardBottom)

            val cols = (boardRight - boardLeft) / avgBlockSizeX
            val rows = (boardBottom - boardTop) / avgBlockSizeY

            // 유효하지 않은 격자 배열 예외 처리
            if (cols !in 5..10 || rows !in 5..11) return

            val colorGrid = Array(rows) { r ->
                IntArray(cols) { c ->
                    val pixelX = boardLeft + (c * avgBlockSizeX) + (avgBlockSizeX / 2)
                    val pixelY = boardTop + (r * avgBlockSizeY) + (avgBlockSizeY / 2)
                    if (pixelX in 0 until screenWidth && pixelY in 0 until screenHeight) {
                        getHsvColor(currentPixels[pixelY * screenWidth + pixelX])
                    } else 0
                }
            }

            // 💡 원칙 3: 격자 크기가 실시간 변경되었을 경우 히스토리 초기화 및 데이터 보정 안정화
            if (gridHistory.isNotEmpty()) {
                val lastFrame = gridHistory.first
                if (lastFrame.size != rows || lastFrame[0].size != cols) {
                    gridHistory.clear()
                }
            }
            
            gridHistory.add(Array(rows) { colorGrid[it].copyOf() })
            if (gridHistory.size > MAX_HISTORY) gridHistory.removeFirst()

            // 다수결 필터로 안정적인 격자 확보
            val stableGrid = Array(rows) { r ->
                IntArray(cols) { c ->
                    val voteMap = mutableMapOf<Int, Int>()
                    for (frame in gridHistory) {
                        val color = frame[r][c]
                        voteMap[color] = (voteMap[color] ?: 0) + 1
                    }
                    voteMap.maxByOrNull { it.value }?.key ?: 0
                }
            }

            // 💡 미러볼 단 하나에만 타겟 집중 (2중, 3중 매칭 우선순위 반영 탐색)
            val hint = findBestFiveMatchPattern(stableGrid, rows, cols)
            if (hint != null) {
                val fx = (boardLeft + (hint.fromC * avgBlockSizeX) + (avgBlockSizeX / 2)).toFloat()
                val fy = (boardTop + (hint.fromR * avgBlockSizeY) + (avgBlockSizeY / 2)).toFloat()
                val tx = (boardLeft + (hint.toC * avgBlockSizeX) + (avgBlockSizeX / 2)).toFloat()
                val ty = (boardTop + (hint.toR * avgBlockSizeY) + (avgBlockSizeY / 2)).toFloat()
                overlayView?.post { overlayView?.setHint(fx, fy, tx, ty, avgBlockSizeX.toFloat()) }
            } else {
                overlayView?.post { overlayView?.clearHint() }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "analyzeScreenFast 실패", e)
        } finally {
            try { image.close() } catch (e: Exception) {}
        }
    }

    // 💡 원칙 1: 조명과 음영 억까에 강한 HSV 기반 정밀 컬러 스캐너
    private fun getHsvColor(pixel: Int): Int {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        if (r + g + b < 65) return 0 
        
        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)
        val hue = hsv[0]
        val sat = hsv[1]
        val value = hsv[2]

        if (value < 0.18f || sat < 0.15f) return 0 

        return when {
            (hue >= 342f || hue <= 18f) -> 1   // 빨강
            (hue in 190f..258f) -> 2           // 파랑
            (hue in 38f..68f) -> 3             // 노랑
            (hue in 85f..160f) -> 4            // 초록
            (hue in 260f..335f) -> 5           // 보라
            else -> 0
        }
    }

    // 💡 원칙 4 보정: 3줄 샘플링 교차 검증을 통한 평균 X축 간격 계산
    private fun estimateAverageSpacingX(pixels: IntArray, left: Int, right: Int, top: Int, bottom: Int): Int {
        val intervals = mutableListOf<Int>()
        val sampleY = intArrayOf(top + (bottom - top) / 3, top + (bottom - top) / 2, top + 2 * (bottom - top) / 3)
        
        for (y in sampleY) {
            var prevHsv = -1
            var lastIdx = left
            for (x in left..right) {
                val hsv = getHsvColor(pixels[y * screenWidth + x])
                if (hsv != prevHsv && prevHsv != -1) {
                    val dist = x - lastIdx
                    if (dist in (screenWidth / 16)..(screenWidth / 6)) {
                        intervals.add(dist)
                    }
                    lastIdx = x
                }
                prevHsv = hsv
            }
        }
        return if (intervals.isNotEmpty()) intervals.average().toInt() else (right - left) / 8
    }

    // 💡 원칙 4 보정: 3줄 샘플링 교차 검증을 통한 평균 Y축 간격 계산
    private fun estimateAverageSpacingY(pixels: IntArray, left: Int, right: Int, top: Int, bottom: Int): Int {
        val intervals = mutableListOf<Int>()
        val sampleX = intArrayOf(left + (right - left) / 3, left + (right - left) / 2, left + 2 * (right - left) / 3)
        
        for (x in sampleX) {
            var prevHsv = -1
            var lastIdx = top
            for (y in top..bottom) {
                val hsv = getHsvColor(pixels[y * screenWidth + x])
                if (hsv != prevHsv && prevHsv != -1) {
                    val dist = y - lastIdx
                    if (dist in (screenWidth / 16)..(screenWidth / 6)) {
                        intervals.add(dist)
                    }
                    lastIdx = y
                }
                prevHsv = hsv
            }
        }
        return if (intervals.isNotEmpty()) intervals.average().toInt() else (bottom - top) / 8
    }

    // 오직 5줄 매칭(미러볼)만 찾아내는 최선의 방향 유도 스왑 연산기
    private fun findBestFiveMatchPattern(grid: Array<IntArray>, rows: Int, cols: Int): MatchHint? {
        val directions = arrayOf(Pair(0, 1), Pair(1, 0))
        var bestMatchCount = 0
        var bestHint: MatchHint? = null

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (grid[r][c] == 0) continue
                for (dir in directions) {
                    val nr = r + dir.first
                    val nc = c + dir.second
                    if (nr < rows && nc < cols && grid[nr][nc] != 0 && grid[r][c] != grid[nr][nc]) {
                        
                        val temp = grid[r][c]
                        grid[r][c] = grid[nr][nc]
                        grid[nr][nc] = temp

                        // 💡 2중 3중으로 5매칭이 터지는 수가 있는지 카운트 측정
                        val matchCount = countFiveMatches(grid, rows, cols)

                        grid[nr][nc] = grid[r][c]
                        grid[r][c] = temp

                        if (matchCount > bestMatchCount) {
                            bestMatchCount = matchCount
                            bestHint = MatchHint(r, c, nr, nc)
                        }
                    }
                }
            }
        }
        return bestHint
    }

    // 5개가 일직선으로 완벽히 정렬되었는지 판단하는 특수 판독 필터
    private fun countFiveMatches(grid: Array<IntArray>, rows: Int, cols: Int): Int {
        var count = 0
        // 가로 방향 5매칭 카운트
        for (r in 0 until rows) {
            var c = 0
            while (c <= cols - 5) {
                val color = grid[r][c]
                if (color != 0 && color == grid[r][c+1] && color == grid[r][c+2] && color == grid[r][c+3] && color == grid[r][c+4]) {
                    count++
                    c += 5
                } else {
                    c++
                }
            }
        }
        // 세로 방향 5매칭 카운트
        for (c in 0 until cols) {
            var r = 0
            while (r <= rows - 5) {
                val color = grid[r][c]
                if (color != 0 && color == grid[r+1][c] && color == grid[r+2][c] && color == grid[r+3][c] && color == grid[r+4][c]) {
                    count++
                    r += 5
                } else {
                    r++
                }
            }
        }
        return count
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
            if (overlayView != null) windowManager?.removeView(overlayView)
            if (controlView != null) windowManager?.removeView(controlView)
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy 실패", e)
        }
    }

    data class MatchHint(val fromR: Int, val fromC: Int, val toR: Int, val toC: Int)
}

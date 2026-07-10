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
    private var reusableBitmap: Bitmap? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "helper_channel")
            .setContentTitle("5라인 미러볼 헬퍼 작동 중")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        // 안드로이드 10(Q) 이상 버전의 가이드라인에 맞춰 미디어 프로젝션 타입 선언
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
        // 💡 [핵심 개선] 재실행 시 기존에 돌고 있던 세션이 있다면 흔적도 없이 완전히 박살내고 새로 시작합니다.
        stopCurrentCapture()

        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_OK) ?: Activity.RESULT_OK
        val dataIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("DATA_INTENT", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<Intent>("DATA_INTENT")
        } 

        // 새로 받아온 인텐트 토큰이 없으면 무부하 종료
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
            
            if (pixelArray == null || pixelArray!!.size != screenWidth * screenHeight) {
                pixelArray = IntArray(screenWidth * screenHeight)
            }

            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            
            // 포그라운드 서비스타입이 지정된 상태(`onCreate`)에서 안전하게 새로 매핑
            mediaProjection = mpManager.getMediaProjection(resultCode, dataIntent)
            
            if (backgroundThread == null) {
                backgroundThread = HandlerThread("AnalyzerThread").apply { start() }
                backgroundHandler = Handler(backgroundThread!!.looper)
            }
            
            // 이미지리더 및 버추얼 디스플레이 완전 클린 재생성
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "Capture", screenWidth, screenHeight, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, backgroundHandler
            )
            
            backgroundHandler?.removeCallbacks(analyzeRunnable)
            backgroundHandler?.post(analyzeRunnable)
            
            showOverlayViews()

        } catch (e: Exception) {
            Log.e(TAG, "미디어 프로젝션 초기화 대실패", e)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    // 💡 기존 세션을 흔적도 없이 지워버리는 청소기 함수
    private fun stopCurrentCapture() {
        try {
            backgroundHandler?.removeCallbacks(analyzeRunnable)
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            Log.e(TAG, "기존 미디어 리소스 청소 중 예외 유연하게 패스", e)
        }
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

        if (controlView == null) {
            val themedContext = ContextThemeWrapper(this, androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
            controlView = LinearLayout(themedContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(12, 6, 12, 6)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#CC111111")) 
                    cornerRadius = 12f
                }
            }

            val statusText = TextView(themedContext).apply {
                text = "● 5-RD"
                setTextColor(Color.GREEN)
                textSize = 9f
                setPadding(0, 0, 12, 0)
            }

            val stopButton = Button(themedContext).apply {
                text = "X"
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#AA3333")) 
                textSize = 9f
                layoutParams = LinearLayout.LayoutParams(60, 50)
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
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL 
                x = 0
                y = 75 // 요청하신 대로 상단 바짝 밀착
            }
            windowManager?.addView(controlView, controlParams)
        }
    }

    private val analyzeRunnable = object : Runnable {
        override fun run() {
            try { processScreenParsing() } catch (e: Exception) { Log.e(TAG, "루프 에러", e) }
            backgroundHandler?.postDelayed(this, 300) 
        }
    }

    private fun processScreenParsing() {
        // 현재 미디어프로젝션 시스템이 비활성화 상태라면 즉시 탈출하여 에러 방지
        val reader = imageReader ?: return
        val image = try { reader.acquireLatestImage() } catch (e: Exception) { null } ?: return
        val pixels = pixelArray ?: return
        
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenWidth
            val tmpWidth = screenWidth + rowPadding / pixelStride

            if (reusableBitmap == null || reusableBitmap!!.width != tmpWidth || reusableBitmap!!.height != screenHeight) {
                reusableBitmap?.recycle()
                reusableBitmap = Bitmap.createBitmap(tmpWidth, screenHeight, Bitmap.Config.ARGB_8888)
            }
            
            reusableBitmap!!.copyPixelsFromBuffer(buffer)
            reusableBitmap!!.getPixels(pixels, 0, screenWidth, 0, 0, screenWidth, screenHeight)

            val minY = (screenHeight * 0.28).toInt()
            val maxY = (screenHeight * 0.82).toInt()
            val minX = (screenWidth * 0.04).toInt()
            val maxX = (screenWidth * 0.96).toInt()

            val distHistogram = IntArray(300)
            val sampleLines = 10
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
                            if (diff in 90..200) distHistogram[diff]++
                        }
                        lastX = x
                        prevColor = color
                    }
                }
            }

            var cellSize = 0
            var maxVotes = 0
            for (d in 95..195) {
                val votes = distHistogram[d - 1] + distHistogram[d] + distHistogram[d + 1]
                if (votes > maxVotes) {
                    maxVotes = votes
                    cellSize = d
                }
            }
            if (cellSize == 0) cellSize = screenWidth / 8

            var firstX = maxX; var lastX = minX
            var firstY = maxY; var lastY = minY
            
            for (y in minY..maxY step 20) {
                for (x in minX..maxX step 20) {
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

            if (cols !in 5..10 || rows !in 5..13) return

            val colorGrid = Array(rows) { r ->
                IntArray(cols) { c ->
                    val centerX = firstX + (c * cellSize) + (cellSize / 2)
                    val centerY = firstY + (r * cellSize) + (cellSize / 2)
                    if (centerX in 0 until screenWidth && centerY in 0 until screenHeight) {
                        getHsvColor(pixels[centerY * screenWidth + centerX])
                    } else 0
                }
            }

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
            Log.e(TAG, "파싱 예외 발생", e)
        } finally {
            try { image.close() } catch (e: Exception) {}
        }
    }

    private fun getHsvColor(pixel: Int): Int {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        if (r + g + b < 100 || r + g + b > 710) return 0 

        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)
        val hue = hsv[0]
        val sat = hsv[1]
        val valF = hsv[2]

        if (sat < 0.20f || valF < 0.20f) return 0

        return when {
            (hue >= 345f || hue <= 15f) -> 1   // 빨강
            (hue in 195f..245f) -> 2           // 파랑
            (hue in 40f..65f) -> 3             // 노랑
            (hue in 90f..145f) -> 4            // 초록
            (hue in 265f..330f) -> 5           // 보라
            else -> 0
        }
    }

    private fun findFiveMatchMove(grid: Array<IntArray>, rows: Int, cols: Int): MatchHint? {
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (grid[r][c] == 0) continue
                if (c + 1 < cols && grid[r][c + 1] != 0 && grid[r][c] != grid[r][c + 1]) {
                    if (verifyFiveMatch(grid, r, c, r, c + 1, rows, cols)) return MatchHint(r, c, r, c + 1)
                }
                if (r + 1 < rows && grid[r + 1][c] != 0 && grid[r][c] != grid[r + 1][c]) {
                    if (verifyFiveMatch(grid, r, c, r + 1, c, rows, cols)) return MatchHint(r, c, r + 1, c)
                }
            }
        }
        return null
    }

    private fun verifyFiveMatch(grid: Array<IntArray>, r1: Int, c1: Int, r2: Int, c2: Int, rows: Int, cols: Int): Boolean {
        val temp = grid[r1][c1]
        grid[r1][c1] = grid[r2][c2]
        grid[r2][c2] = temp

        val isFive = isLineOfFive(grid, r1, c1, rows, cols) || isLineOfFive(grid, r2, c2, rows, cols)

        grid[r2][c2] = grid[r1][c1]
        grid[r1][c1] = temp
        return isFive
    }

    private fun isLineOfFive(grid: Array<IntArray>, r: Int, c: Int, rows: Int, cols: Int): Boolean {
        val color = grid[r][c]
        if (color == 0) return false

        var hCount = 1
        var cc = c - 1
        while (cc >= 0 && grid[r][cc] == color) { hCount++; cc-- }
        cc = c + 1
        while (cc < cols && grid[r][cc] == color) { hCount++; cc++ }
        if (hCount >= 5) return true

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
        stopCurrentCapture()
        backgroundThread?.quitSafely()
        reusableBitmap?.recycle()
        reusableBitmap = null
        if (overlayView != null) windowManager?.removeView(overlayView)
        if (controlView != null) windowManager?.removeView(controlView)
    }

    data class MatchHint(val fromR: Int, val fromC: Int, val toR: Int, val toC: Int)
}

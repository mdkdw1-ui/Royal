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
import android.graphics.Point
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
    private var isScanningEnabled = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
            val notification: Notification = NotificationCompat.Builder(this, "helper_channel")
                .setContentTitle("로얄매치 AI 마스터")
                .setContentText("가변형 전체 격자 시뮬레이터 가동 중")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("helper_channel", "로얄매치 스캔 레이어", NotificationManager.IMPORTANCE_LOW)
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
            
            backgroundThread = HandlerThread("Grid_Scanner").apply { start() }
            backgroundHandler = Handler(backgroundThread!!.looper)

            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture", screenWidth, screenHeight, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, backgroundHandler
            )
            
            backgroundHandler?.post(analyzeRunnable)

            val finishIntent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("ACTION_FINISH", true)
            }
            startActivity(finishIntent)

        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

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
        showControlOverlay()

        return START_NOT_STICKY
    }

    private fun showControlOverlay() {
        if (controlView != null) return
        val themedContext = ContextThemeWrapper(this, androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        controlView = LinearLayout(themedContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 8, 16, 8)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#DD111111"))
                cornerRadius = 16f
            }
        }

        val statusText = TextView(themedContext).apply {
            text = "● DYNAMIC GRID v4 "
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 9.5f
            setPadding(0, 0, 12, 0)
        }

        val toggleButton = Button(themedContext).apply {
            text = "ON"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2ECC71"))
            textSize = 9.5f
            layoutParams = LinearLayout.LayoutParams(90, 55).apply { setMargins(0, 0, 10, 0) }
            setOnClickListener {
                isScanningEnabled = !isScanningEnabled
                text = if (isScanningEnabled) "ON" else "OFF"
                setBackgroundColor(Color.parseColor(if (isScanningEnabled) "#2ECC71" else "#95A5A6"))
                if (!isScanningEnabled) overlayView?.clearHint()
            }
        }

        val stopButton = Button(themedContext).apply {
            text = "X"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#E74C3C"))
            textSize = 9.5f
            layoutParams = LinearLayout.LayoutParams(60, 55)
            setOnClickListener { stopSelf() }
        }

        controlView?.addView(statusText)
        controlView?.addView(toggleButton)
        controlView?.addView(stopButton)

        val controlParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 90
        }
        windowManager?.addView(controlView, controlParams)
    }

    private val analyzeRunnable = object : Runnable {
        override fun run() {
            try { analyzeScreenFast() } catch (e: Exception) { Log.e(TAG, "Loop Skip", e) }
            backgroundHandler?.postDelayed(this, 220)
        }
    }

    private fun analyzeScreenFast() {
        if (!isScanningEnabled) return
        val reader = imageReader ?: return
        val image = try { reader.acquireLatestImage() } catch (e: Exception) { null } ?: return
        
        try {
            val planes = image.planes
            val buffer = planes[0].buffer          
            val pixelStride = planes[0].pixelStride  
            val rowStride = planes[0].rowStride      
            val rowPadding = rowStride - pixelStride * screenWidth
            val adjustedWidth = screenWidth + rowPadding / pixelStride

            if (reusableBitmap == null || reusableBitmap!!.width != adjustedWidth || reusableBitmap!!.height != screenHeight) {
                reusableBitmap?.recycle()
                reusableBitmap = Bitmap.createBitmap(adjustedWidth, screenHeight, Bitmap.Config.ARGB_8888)
            }
            
            val bitmap = reusableBitmap!!
            buffer.rewind() 
            bitmap.copyPixelsFromBuffer(buffer)

            val scanLeft = (screenWidth * 0.03f).toInt()
            val scanRight = (screenWidth * 0.97f).toInt()
            val scanTop = (screenHeight * 0.28f).toInt() // 상단 기믹 위쪽까지 수색선 확장
            val scanBottom = (screenHeight * 0.88f).toInt()

            // 🎯 [핵심 수정] 중간 공백 유무와 상관없이 화면에 존재하는 일반 블록들의 '절대 최외곽 경계선' 산출
            var minBlockX = screenWidth
            var maxBlockX = 0
            var minBlockY = screenHeight
            var maxBlockY = 0

            for (y in scanTop until scanBottom step 15) {
                for (x in scanLeft until scanRight step 15) {
                    if (identifyColorHSV(bitmap.getPixel(x, y)) in 1..5) {
                        if (x < minBlockX) minBlockX = x
                        if (x > maxBlockX) maxBlockX = x
                        if (y < minBlockY) minBlockY = y
                        if (y > maxBlockY) maxBlockY = y
                    }
                }
            }

            val boardWidth = maxBlockX - minBlockX
            val boardHeight = maxBlockY - minBlockY

            // 유효성 최소 컷 검증
            if (boardWidth < screenWidth * 0.5 || boardHeight < 150) {
                overlayView?.post { overlayView?.clearHint() }
                return
            }

            // 가변형 해상도 기반 단일 블록 스케일 산출
            val approxBlockSize = screenWidth / 9.3f
            val currentGridCols = Math.round(boardWidth.toFloat() / approxBlockSize).coerceIn(4, 11)
            val currentGridRows = Math.round(boardHeight.toFloat() / approxBlockSize).coerceIn(2, 13)

            val blockSizeX = boardWidth.toFloat() / currentGridCols
            val blockSizeY = boardHeight.toFloat() / currentGridRows

            // 전체 영역 단일 앵커 포인트 수립
            val topLeftAnchorX = minBlockX + (blockSizeX / 2f)
            val topLeftAnchorY = minBlockY + (blockSizeY / 2f)

            // 전체 통합 격자 데이터 샘플링 (중간 기믹 자리는 0으로 자동 매핑됨)
            val colorGrid = Array(currentGridRows) { IntArray(currentGridCols) }
            for (r in 0 until currentGridRows) {
                for (c in 0 until currentGridCols) {
                    val cx = (topLeftAnchorX + (c * blockSizeX)).toInt().coerceIn(0, screenWidth - 1)
                    val cy = (topLeftAnchorY + (r * blockSizeY)).toInt().coerceIn(0, screenHeight - 1)
                    colorGrid[r][c] = getPixelBlockColor(bitmap, cx, cy)
                }
            }

            // 5-Match 전수 시뮬레이션 매칭 엔진 작동
            val hint = findFiveMatchBySimulation(colorGrid, currentGridRows, currentGridCols)
            if (hint != null) {
                val fx = topLeftAnchorX + (hint.fromC * blockSizeX)
                val fy = topLeftAnchorY + (hint.fromR * blockSizeY)
                val tx = topLeftAnchorX + (hint.toC * blockSizeX)
                val ty = topLeftAnchorY + (hint.toR * blockSizeY)
                
                overlayView?.post { overlayView?.setHint(fx, fy, tx, ty, blockSizeX) }
            } else {
                overlayView?.post { overlayView?.clearHint() }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "분석 주기 예외", e)
        } finally {
            try { image.close() } catch (e: Exception) {}
        }
    }

    private fun getPixelBlockColor(bitmap: Bitmap, cx: Int, cy: Int): Int {
        val votes = IntArray(6)
        votes[identifyColorHSV(bitmap.getPixel(cx, cy))]++
        votes[identifyColorHSV(bitmap.getPixel((cx - 8).coerceIn(0, screenWidth - 1), cy))]++
        votes[identifyColorHSV(bitmap.getPixel((cx + 8).coerceIn(0, screenWidth - 1), cy))]++
        votes[identifyColorHSV(bitmap.getPixel(cx, (cy - 8).coerceIn(0, screenHeight - 1)))]++
        votes[identifyColorHSV(bitmap.getPixel(cx, (cy + 8).coerceIn(0, screenHeight - 1)))]++
        
        var maxVote = 0
        var winner = 0
        for (i in 1..5) {
            if (votes[i] > maxVote) { maxVote = votes[i]; winner = i }
        }
        return if (maxVote >= 2) winner else 0
    }

    private fun identifyColorHSV(pixel: Int): Int {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        if (r + g + b < 95 || r + g + b > 725) return 0

        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)
        val hue = hsv[0]
        val sat = hsv[1]
        val value = hsv[2]

        if (sat < 0.15f || value < 0.15f) return 0

        return when {
            (hue >= 345f || hue <= 15f) -> 1  // 빨강 (하트)
            (hue in 195f..245f) -> 2          // 파랑 (방패)
            (hue in 40f..65f) -> 3            // 노랑 (왕관)
            (hue in 90f..145f) -> 4           // 초록 (나뭇잎)
            (hue in 265f..330f) -> 5          // 보라 (새 깃털)
            else -> 0
        }
    }

    private fun findFiveMatchBySimulation(grid: Array<IntArray>, rows: Int, cols: Int): MatchHint? {
        fun checkFiveAt(g: Array<IntArray>, r: Int, c: Int): Boolean {
            val color = g[r][c]
            if (color == 0) return false
            
            for (startC in (c - 4)..c) {
                if (startC >= 0 && startC + 4 < cols) {
                    if (g[r][startC] == color && g[r][startC + 1] == color &&
                        g[r][startC + 2] == color && g[r][startC + 3] == color &&
                        g[r][startC + 4] == color) return true
                }
            }
            for (startR in (r - 4)..r) {
                if (startR >= 0 && startR + 4 < rows) {
                    if (g[startR][c] == color && g[startR + 1][c] == color &&
                        g[startR + 2][c] == color && g[startR + 3][c] == color &&
                        g[startR + 4][c] == color) return true
                }
            }
            return false
        }

        fun cloneGrid() = Array(rows) { r -> grid[r].clone() }

        // 가로 스왑 테스트
        for (r in 0 until rows) {
            for (c in 0 until cols - 1) {
                if (grid[r][c] == 0 && grid[r][c+1] == 0) continue
                val simGrid = cloneGrid()
                val temp = simGrid[r][c]
                simGrid[r][c] = simGrid[r][c+1]
                simGrid[r][c+1] = temp
                
                if (checkFiveAt(simGrid, r, c) || checkFiveAt(simGrid, r, c + 1)) {
                    return MatchHint(fromR = r, fromC = c, toR = r, toC = c + 1)
                }
            }
        }

        // 세로 스왑 테스트
        for (r in 0 until rows - 1) {
            for (c in 0 until cols) {
                if (grid[r][c] == 0 && grid[r+1][c] == 0) continue
                val simGrid = cloneGrid()
                val temp = simGrid[r][c]
                simGrid[r][c] = simGrid[r+1][c]
                simGrid[r+1][c] = temp
                
                if (checkFiveAt(simGrid, r, c) || checkFiveAt(simGrid, r + 1, c)) {
                    return MatchHint(fromR = r, fromC = c, toR = r + 1, toC = c)
                }
            }
        }
        return null
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
            if (overlayView != null) { windowManager?.removeView(overlayView); overlayView = null }
            if (controlView != null) { windowManager?.removeView(controlView); controlView = null }
        } catch (e: Exception) {
            Log.e(TAG, "해제 예외", e)
        }
    }

    data class MatchHint(val fromR: Int, val fromC: Int, val toR: Int, val toC: Int)
}

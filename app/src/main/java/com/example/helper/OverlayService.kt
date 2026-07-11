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
import kotlin.math.abs

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
                .setContentTitle("로얄매치 AI 헬퍼")
                .setContentText("앵커 포인트 정밀 좌표계 가동 중")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Foreground 서비스 승격 실패", e)
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
            
            backgroundHandler?.post(analyzeRunnable)

            val finishIntent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("ACTION_FINISH", true)
            }
            startActivity(finishIntent)

        } catch (e: Exception) {
            Log.e(TAG, "프로젝션 초기화 실패", e)
            stopSelf()
            return START_NOT_STICKY
        }

        try {
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
        } catch (e: Exception) {
            Log.e(TAG, "오버레이 안착 실패", e)
        }

        return START_NOT_STICKY
    }

    private fun showControlOverlay() {
        if (controlView != null) return
        try {
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
                text = "● ANCHOR SCAN "
                setTextColor(Color.CYAN)
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
                    if (isScanningEnabled) {
                        text = "ON"
                        setBackgroundColor(Color.parseColor("#2ECC71"))
                    } else {
                        text = "OFF"
                        setBackgroundColor(Color.parseColor("#95A5A6"))
                        overlayView?.clearHint()
                    }
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
        } catch (e: Exception) {
            Log.e(TAG, "컨트롤바 마운트 누락", e)
        }
    }

    private val analyzeRunnable = object : Runnable {
        override fun run() {
            try { analyzeScreenFast() } catch (e: Exception) { Log.e(TAG, "루프 스킵", e) }
            backgroundHandler?.postDelayed(this, 300)
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

            // 게임판 좌우 절대 마진선 확보
            val boardLeft = (screenWidth * 0.045f).toInt()
            val boardRight = (screenWidth * 0.955f).toInt()
            val boardWidth = boardRight - boardLeft

            val scanXStart = (screenWidth * 0.15).toInt()
            val scanXEnd = (screenWidth * 0.85).toInt()
            val scanYStart = (screenHeight * 0.35).toInt()
            val scanYEnd = (screenHeight * 0.84).toInt()

            val hProfile = IntArray(screenHeight)
            var profileSum = 0L
            var profileCount = 0

            for (y in scanYStart until scanYEnd) {
                var diff = 0
                for (x in scanXStart until scanXEnd step 12) {
                    val p1 = bitmap.getPixel(x, y)
                    val p2 = bitmap.getPixel(x, y + 2)
                    diff += abs(Color.red(p1) - Color.red(p2)) + abs(Color.green(p1) - Color.green(p2)) + abs(Color.blue(p1) - Color.blue(p2))
                }
                hProfile[y] = diff
                if (y > screenHeight * 0.65) {
                    profileSum += diff
                    profileCount++
                }
            }

            val baselineAvg = if (profileCount > 0) profileSum / profileCount else 1000
            val peakThreshold = baselineAvg * 1.35
            var boardBottom = 0

            for (y in (screenHeight * 0.83).toInt() downTo (screenHeight * 0.72).toInt()) {
                if (hProfile[y] > peakThreshold) {
                    if (hProfile[y] >= hProfile[y - 1] && hProfile[y] >= hProfile[y + 1]) {
                        boardBottom = y
                        break
                    }
                }
            }

            if (boardBottom == 0) {
                boardBottom = (screenHeight * 0.818f).toInt()
            }

            // 🎯 1️⃣ 가변 격자 자동 판별 및 앵커 포인트 수식 최적화 매칭 조율 단원
            var bestCols = 0
            var bestRows = 0
            var bestStrideX = 0f
            var bestStrideY = 0f
            var bestTopLeftAnchorX = 0f
            var bestTopLeftAnchorY = 0f
            var maxValidBlocks = -1

            // 7x7부터 10x10까지 가변 그리드를 실시간 대입 평가
            for (testCols in 7..10) {
                val blockSize = boardWidth.toFloat() / testCols
                val testRows = ((boardBottom - scanYStart) / blockSize).toInt()

                // 메일 제안 공식: [첫 블록 중심 Anchor]과 [끝 블록 중심 Anchor] 도출
                // 정확한 반 블록(blockSize / 2) 여백 마진을 사용해 5.5% 비율을 기하학적으로 완벽 연동
                val topLeftAnchorX = boardLeft + (blockSize / 2f)
                val bottomRightAnchorX = boardRight - (blockSize / 2f)
                
                val bottomRightAnchorY = boardBottom - (blockSize / 2f)
                val topLeftAnchorY = bottomRightAnchorY - (testRows - 1) * blockSize

                // ★ 메일의 핵심 공식: (격자수 - 1) 나누기로 축적 오차 제로화!
                val strideX = if (testCols > 1) (bottomRightAnchorX - topLeftAnchorX) / (testCols - 1) else blockSize
                val strideY = blockSize // 정사각형 성질 유지

                var validCount = 0
                for (r in 0 until testRows) {
                    for (c in 0 until testCols) {
                        // 상대 좌표계를 기반으로 각 블록의 자석 정중앙 픽셀 계산
                        val cx = (topLeftAnchorX + (c * strideX)).toInt()
                        val cy = (topLeftAnchorY + (r * strideY)).toInt()
                        
                        if (cx in 0 until bitmap.width && cy in 0 until bitmap.height) {
                            if (getPixelBlockColor(bitmap, cx, cy) in 1..5) {
                                validCount++
                            }
                        }
                    }
                }

                if (validCount > maxValidBlocks) {
                    maxValidBlocks = validCount
                    bestCols = testCols
                    bestRows = testRows
                    bestStrideX = strideX
                    bestStrideY = strideY
                    bestTopLeftAnchorX = topLeftAnchorX
                    bestTopLeftAnchorY = topLeftAnchorY
                }
            }

            if (maxValidBlocks < 8 || bestCols == 0 || bestRows == 0) {
                overlayView?.post { overlayView?.clearHint() }
                return
            }

            // 🎯 2️⃣ 확정된 최적의 앵커 매트릭스를 기반으로 컬러 배열 최종 빌드
            val colorGrid = Array(bestRows) { IntArray(bestCols) }
            for (r in 0 until bestRows) {
                for (c in 0 until bestCols) {
                    val cx = (bestTopLeftAnchorX + (c * bestStrideX)).toInt()
                    val cy = (bestTopLeftAnchorY + (r * bestStrideY)).toInt()
                    if (cx in 0 until bitmap.width && cy in 0 until bitmap.height) {
                        colorGrid[r][c] = getPixelBlockColor(bitmap, cx, cy)
                    }
                }
            }

            // ⚡ 3️⃣ 녹녹노녹녹(OOXOO) 5-Ball 정밀 추적기 작동 및 오버레이 드로우 호출
            val hint = findExactOOXOOMatch5(colorGrid, bestRows, bestCols)
            if (hint != null) {
                // 힌트 블록 좌표 역시 오차 없는 정밀 앵커 수식 기반 픽셀로 환산하여 뷰어에 토스
                val fx = bestTopLeftAnchorX + (hint.fromC * bestStrideX)
                val fy = bestTopLeftAnchorY + (hint.fromR * bestStrideY)
                val tx = bestTopLeftAnchorX + (hint.toC * bestStrideX)
                val ty = bestTopLeftAnchorY + (hint.toR * bestStrideY)
                
                val displayBlockSize = bestStrideX
                overlayView?.post { overlayView?.setHint(fx, fy, tx, ty, displayBlockSize) }
            } else {
                overlayView?.post { overlayView?.clearHint() }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "실시간 프레임 앵커 분석 예외", e)
        } finally {
            try { image.close() } catch (e: Exception) {}
        }
    }

    private fun getPixelBlockColor(bitmap: Bitmap, cx: Int, cy: Int): Int {
        val votes = IntArray(6)
        votes[identifyColorHSV(bitmap.getPixel(cx, cy))]++
        votes[identifyColorHSV(bitmap.getPixel(cx - 6, cy))]++
        votes[identifyColorHSV(bitmap.getPixel(cx + 6, cy))]++
        votes[identifyColorHSV(bitmap.getPixel(cx, cy - 6))]++
        votes[identifyColorHSV(bitmap.getPixel(cx, cy + 6))]++
        
        var maxVote = 0
        var winner = 0
        for (i in 1..5) {
            if (votes[i] > maxVote) {
                maxVote = votes[i]
                winner = i
            }
        }
        return if (maxVote >= 2) winner else 0
    }

    private fun identifyColorHSV(pixel: Int): Int {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        if (r + g + b < 90 || r + g + b > 720) return 0

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

    private fun findExactOOXOOMatch5(grid: Array<IntArray>, rows: Int, cols: Int): MatchHint? {
        // 1. 가로축 수색 [ O O X O O ]
        for (r in 0 until rows) {
            for (c in 0..cols - 5) {
                val t = grid[r][c]
                if (t == 0) continue

                if (grid[r][c+1] == t && grid[r][c+3] == t && grid[r][c+4] == t && grid[r][c+2] != t) {
                    val targetRow = r
                    val targetCol = c + 2 

                    if (targetRow - 1 >= 0 && grid[targetRow - 1][targetCol] == t) {
                        return MatchHint(fromR = targetRow - 1, fromC = targetCol, toR = targetRow, toC = targetCol)
                    }
                    if (targetRow + 1 < rows && grid[targetRow + 1][targetCol] == t) {
                        return MatchHint(fromR = targetRow + 1, fromC = targetCol, toR = targetRow, toC = targetCol)
                    }
                }
            }
        }

        // 2. 세로축 수색 [ O O X O O ]
        for (c in 0 until cols) {
            for (r in 0..rows - 5) {
                val t = grid[r][c]
                if (t == 0) continue

                if (grid[r+1][c] == t && grid[r+3][c] == t && grid[r+4][c] == t && grid[r+2][c] != t) {
                    val targetRow = r + 2 
                    val targetCol = c

                    if (targetCol - 1 >= 0 && grid[targetRow][targetCol - 1] == t) {
                        return MatchHint(fromR = targetRow, fromC = targetCol - 1, toR = targetRow, toC = targetCol)
                    }
                    if (targetCol + 1 < cols && grid[targetRow][targetCol + 1] == t) {
                        return MatchHint(fromR = targetRow, fromC = targetCol + 1, toR = targetRow, toC = targetCol)
                    }
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
            Log.e(TAG, "자원 해제 예외", e)
        }
    }

    data class MatchHint(val fromR: Int, val fromC: Int, val toR: Int, val toC: Int)
}

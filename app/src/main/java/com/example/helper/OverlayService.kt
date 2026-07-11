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
                .setContentText("가변 분리형 격자판을 정밀 분석 중입니다.")
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
            Log.e(TAG, "미디어 프로젝션 시스템 구동 실패", e)
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
            Log.e(TAG, "안내선 장착 실패", e)
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
                text = "● 5-BALL SCAN "
                setTextColor(Color.GREEN)
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
            try { analyzeScreenFast() } catch (e: Exception) { Log.e(TAG, "스캔 루프 스킵", e) }
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

            // 💡 1. 가로축 경계선은 모든 기기 비율(4.5% ~ 95.5%)에서 절대적으로 고정적입니다.
            val boardLeft = (screenWidth * 0.045f).toInt()
            val boardRight = (screenWidth * 0.955f).toInt()
            val boardWidth = boardRight - boardLeft

            // 💡 2. 수직 픽셀 프로필(hProfile) 분석법을 이용해 하단 부스터 바로 위 '격자 하단선' 검출
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
                    val p2 = bitmap.getPixel(x, y + 2) // 수직 미세 변화율 적산
                    diff += abs(Color.red(p1) - Color.red(p2)) + abs(Color.green(p1) - Color.green(p2)) + abs(Color.blue(p1) - Color.blue(p2))
                }
                hProfile[y] = diff
                if (y > screenHeight * 0.65) { // 하단 부근에서 베이스라인 추출
                    profileSum += diff
                    profileCount++
                }
            }

            val baselineAvg = if (profileCount > 0) profileSum / profileCount else 1000
            val peakThreshold = baselineAvg * 1.35
            var boardBottom = 0

            // 밑에서 위로 올라오며 장식장 선이 아닌 실제 블록 최하단 지지선(경계 피크)을 확보
            for (y in (screenHeight * 0.83).toInt() downTo (screenHeight * 0.72).toInt()) {
                if (hProfile[y] > peakThreshold) {
                    if (hProfile[y] >= hProfile[y - 1] && hProfile[y] >= hProfile[y + 1]) {
                        boardBottom = y
                        break
                    }
                }
            }

            if (boardBottom == 0) {
                boardBottom = (screenHeight * 0.818f).toInt() // 탐색 실패시 보장형 기본 하단선
            }

            // 💡 3. 블록 가변 열($7\times7$ ~ $10\times10$) 투표 및 매칭 블록 카운트 매핑
            var bestCols = 0
            var bestRows = 0
            var bestBlockSize = 0
            var maxValidBlocks = -1

            for (testCols in 7..10) {
                val testBlockSize = boardWidth / testCols
                val testRows = (boardBottom - scanYStart) / testBlockSize
                var validCount = 0

                for (r in 0 until testRows) {
                    for (c in 0 until testCols) {
                        val cx = boardLeft + (c * testBlockSize) + (testBlockSize / 2)
                        val cy = boardBottom - (r * testBlockSize) - (testBlockSize / 2) // 하단부터 위로 좌표 빌드
                        
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
                    bestBlockSize = testBlockSize
                }
            }

            // 안전장치: 화면 분실 혹은 메뉴 창 오픈 시 즉시 지우고 정지
            if (maxValidBlocks < 8 || bestCols == 0 || bestRows == 0) {
                overlayView?.post { overlayView?.clearHint() }
                return
            }

            // 💡 4. 표준 행렬 형식(0번 인덱스가 최고 상단 행)으로 변환 매트릭스 주입
            val colorGrid = Array(bestRows) { IntArray(bestCols) }
            for (r in 0 until bestRows) {
                for (c in 0 until bestCols) {
                    val cx = boardLeft + (c * bestBlockSize) + (bestBlockSize / 2)
                    // r=0이 최상단 행이 되도록 보정 연산
                    val cy = (boardBottom - (bestRows - 1 - r) * bestBlockSize) - (bestBlockSize / 2)
                    if (cx in 0 until bitmap.width && cy in 0 until bitmap.height) {
                        colorGrid[r][c] = getPixelBlockColor(bitmap, cx, cy)
                    }
                }
            }

            // ⚡ 5. 녹녹노녹녹(OOXOO) 전용 5-Ball 하이퍼 알고리즘 적용
            val hint = findExactOOXOOMatch5(colorGrid, bestRows, bestCols)
            if (hint != null) {
                val fx = boardLeft + (hint.fromC * bestBlockSize) + (bestBlockSize / 2).toFloat()
                val fy = (boardBottom - (bestRows - 1 - hint.fromR) * bestBlockSize) - (bestBlockSize / 2).toFloat()
                val tx = boardLeft + (hint.toC * bestBlockSize) + (bestBlockSize / 2).toFloat()
                val ty = (boardBottom - (bestRows - 1 - hint.toR) * bestBlockSize) - (bestBlockSize / 2).toFloat()
                
                overlayView?.post { overlayView?.setHint(fx, fy, tx, ty, bestBlockSize.toFloat()) }
            } else {
                overlayView?.post { overlayView?.clearHint() }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "실시간 프레임 정밀 분석 예외", e)
        } finally {
            try { image.close() } catch (e: Exception) {}
        }
    }

    // 십자 정밀 투표 방식을 통해 블록 내부 텍스처 데코 노이즈 제거
    private fun getPixelBlockColor(bitmap: Bitmap, cx: Int, cy: Int): Int {
        val votes = IntArray(6)
        votes[identifyColorHSV(bitmap.getPixel(cx, cy))]++
        votes[identifyColorHSV(bitmap.getPixel(cx - 5, cy))]++
        votes[identifyColorHSV(bitmap.getPixel(cx + 5, cy))]++
        votes[identifyColorHSV(bitmap.getPixel(cx, cy - 5))]++
        votes[identifyColorHSV(bitmap.getPixel(cx, cy + 5))]++
        
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
        // 1. 가로 방향 [ O O X O O ] 패턴 검사
        for (r in 0 until rows) {
            for (c in 0..cols - 5) {
                val t = grid[r][c]
                if (t == 0) continue

                if (grid[r][c+1] == t && grid[r][c+3] == t && grid[r][c+4] == t && grid[r][c+2] != t) {
                    val targetRow = r
                    val targetCol = c + 2 

                    // 수직축 위/아래에서 대조군 조각을 중앙(X)으로 끌어올 수 있는지 수색
                    if (targetRow - 1 >= 0 && grid[targetRow - 1][targetCol] == t) {
                        return MatchHint(fromR = targetRow - 1, fromC = targetCol, toR = targetRow, toC = targetCol)
                    }
                    if (targetRow + 1 < rows && grid[targetRow + 1][targetCol] == t) {
                        return MatchHint(fromR = targetRow + 1, fromC = targetCol, toR = targetRow, toC = targetCol)
                    }
                }
            }
        }

        // 2. 세로 방향 [ O O X O O ] 패턴 검사
        for (c in 0 until cols) {
            for (r in 0..rows - 5) {
                val t = grid[r][c]
                if (t == 0) continue

                if (grid[r+1][c] == t && grid[r+3][c] == t && grid[r+4][c] == t && grid[r+2][c] != t) {
                    val targetRow = r + 2 
                    val targetCol = c

                    // 수평축 좌/우에서 대조군 조각을 중앙(X)으로 밀어넣을 수 있는지 수색
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

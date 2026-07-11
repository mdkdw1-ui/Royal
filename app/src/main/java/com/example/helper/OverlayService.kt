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

    // 제어용 스캔 ON/OFF 플래그
    private var isScanningEnabled = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
            val notification: Notification = NotificationCompat.Builder(this, "helper_channel")
                .setContentTitle("로얄매치 AI 헬퍼")
                .setContentText("빛의 볼(OOXOO) 패턴을 감시 중입니다.")
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
            val channel = NotificationChannel("helper_channel", "로얄매치 서비스", NotificationManager.IMPORTANCE_LOW)
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
            
            backgroundThread = HandlerThread("OOXOO_Scanner").apply { start() }
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

            // 메인 액티비티 숨기기 및 타겟 게임 전환 유도
            val finishIntent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("ACTION_FINISH", true)
            }
            startActivity(finishIntent)

        } catch (e: Exception) {
            Log.e(TAG, "미디어 프로젝션 초기화 실패", e)
            stopSelf()
            return START_NOT_STICKY
        }

        // 오버레이 가이드 레이어 생성
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
            Log.e(TAG, "오버레이 뷰 표출 실패", e)
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
                    setColor(Color.parseColor("#CC111111"))
                    cornerRadius = 16f
                }
            }

            val statusText = TextView(themedContext).apply {
                text = "● 5-BALL "
                setTextColor(Color.YELLOW)
                textSize = 9.5f
                setPadding(0, 0, 12, 0)
            }

            // ON/OFF 컨트롤러 스위치
            val toggleButton = Button(themedContext).apply {
                text = "ON"
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#4CD964")) 
                textSize = 9.5f
                layoutParams = LinearLayout.LayoutParams(90, 55).apply { setMargins(0, 0, 10, 0) }
                setOnClickListener {
                    isScanningEnabled = !isScanningEnabled
                    if (isScanningEnabled) {
                        text = "ON"
                        setBackgroundColor(Color.parseColor("#4CD964"))
                    } else {
                        text = "OFF"
                        setBackgroundColor(Color.parseColor("#8E8E93")) 
                        overlayView?.clearHint() 
                    }
                }
            }

            // 앱 종료 킬스위치
            val stopButton = Button(themedContext).apply {
                text = "X"
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#FF3B30")) 
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
                y = 80 
            }
            windowManager?.addView(controlView, controlParams)
        } catch (e: Exception) {
            Log.e(TAG, "상단 제어 바 생성 에러", e)
        }
    }

    private val analyzeRunnable = object : Runnable {
        override fun run() {
            try { analyzeScreenFast() } catch (e: Exception) { Log.e(TAG, "스캔 주기 에러", e) }
            backgroundHandler?.postDelayed(this, 300) // 0.3초마다 쾌적하게 갱신
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

            var boardTop = 0; var boardBottom = 0
            var boardLeft = 0; var boardRight = 0
            val centerX = screenWidth / 2
            val startY = (screenHeight * 0.25).toInt()
            val endY = (screenHeight * 0.80).toInt()

            // 1. 다이나믹 게임 보드판 탐색 (로얄매치 고유 브라운 톤 마스킹)
            for (y in startY..endY) {
                val pixel = bitmap.getPixel(centerX, y)
                if (Color.red(pixel) in 30..95 && Color.green(pixel) in 25..85 && Color.blue(pixel) in 20..80) {
                    if (boardTop == 0) boardTop = y
                    boardBottom = y
                }
            }

            // [타이틀/로딩 감지 방어막] 보드가 안 잡히거나 너무 작으면 무조건 연산 스킵
            if (boardTop == 0 || boardBottom == 0 || (boardBottom - boardTop) < 300) {
                overlayView?.post { overlayView?.clearHint() }
                return
            }

            val targetMidY = boardTop + (boardBottom - boardTop) / 2
            for (x in (screenWidth * 0.02).toInt() until centerX) {
                if (Color.red(bitmap.getPixel(x, targetMidY)) in 30..95) { boardLeft = x; break }
            }
            for (x in (screenWidth * 0.98).toInt() downTo centerX) {
                if (Color.red(bitmap.getPixel(x, targetMidY)) in 30..95) { boardRight = x; break }
            }

            if (boardLeft == 0 || boardRight == 0) {
                boardLeft = (screenWidth * 0.05).toInt()
                boardRight = (screenWidth * 0.95).toInt()
            }

            val boardWidth = boardRight - boardLeft
            val boardHeight = boardBottom - boardTop

            // 2. 가변 행렬 구조 투표 매핑 ($7\times7$ ~ $10\times10$)
            var bestCols = 0
            var bestRows = 0
            var bestBlockSize = 0
            var maxValidCount = -1

            for (testCols in 7..10) {
                val testBlockSize = boardWidth / testCols
                val testRows = ((boardHeight + testBlockSize / 2) / testBlockSize).coerceIn(5, 12)
                var validCount = 0

                for (r in 0 until testRows) {
                    for (c in 0 until testCols) {
                        val px = boardLeft + (c * testBlockSize) + (testBlockSize / 2)
                        val py = boardTop + (r * testBlockSize) + (testBlockSize / 2)
                        if (px in 0 until bitmap.width && py in 0 until bitmap.height) {
                            val color = identifyColorHSV(bitmap.getPixel(px, py))
                            if (color in 1..5) validCount++
                        }
                    }
                }

                if (validCount > maxValidCount) {
                    maxValidCount = validCount
                    bestCols = testCols
                    bestRows = testRows
                    bestBlockSize = testBlockSize
                }
            }

            // [메뉴 창 오픈 시 스킵 방어막] 활성 블록 개수가 너무 적으면 게임 중이 아닌 것으로 판정
            if (maxValidCount < 12 || bestCols == 0 || bestRows == 0) {
                overlayView?.post { overlayView?.clearHint() }
                return
            }

            // 3. 확정된 가변 보드판에 맞춤형 컬러 매트릭스 추출
            val colorGrid = Array(bestRows) { IntArray(bestCols) }
            for (r in 0 until bestRows) {
                for (c in 0 until bestCols) {
                    val px = boardLeft + (c * bestBlockSize) + (bestBlockSize / 2)
                    val py = boardTop + (r * bestBlockSize) + (bestBlockSize / 2)
                    if (px in 0 until bitmap.width && py in 0 until bitmap.height) {
                        colorGrid[r][c] = identifyColorHSV(bitmap.getPixel(px, py))
                    }
                }
            }

            // ⚡ 4. [주문형 핵심 기능] 오직 O O X O O 일자 패턴만 콕 집어 서칭
            val hint = findExactOOXOOMatch5(colorGrid, bestRows, bestCols)
            if (hint != null) {
                val fx = boardLeft + (hint.fromC * bestBlockSize) + (bestBlockSize / 2).toFloat()
                val fy = boardTop + (hint.fromR * bestBlockSize) + (bestBlockSize / 2).toFloat()
                val tx = boardLeft + (hint.toC * bestBlockSize) + (bestBlockSize / 2).toFloat()
                val ty = boardTop + (hint.toR * bestBlockSize) + (bestBlockSize / 2).toFloat()
                overlayView?.post { overlayView?.setHint(fx, fy, tx, ty, bestBlockSize.toFloat()) }
            } else {
                overlayView?.post { overlayView?.clearHint() }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "시스템 실시간 분석 실패", e)
        } finally

 {
            try { image.close() } catch (e: Exception) {}
        }
    }

    // HSV 정밀 색상 범위 매핑 (로얄매치 매칭 5색 스펙)
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
            (hue in 90f..145f) -> 4           // 초록 (수박/잎)
            (hue in 265f..330f) -> 5          // 보라 (새/깃털)
            else -> 0
        }
    }

    /**
     * ⚡ [요청하신 알고리즘 공식]
     * 오직 'O O X O O' 가로 혹은 세로 형태의 특수 매칭 기회만 찾아서 반환합니다.
     */
    private fun findExactOOXOOMatch5(grid: Array<IntArray>, rows: Int, cols: Int): MatchHint? {
        
        // 1. 가로 방향 [ O O X O O ] 찾아내기
        for (r in 0 until rows) {
            for (c in 0..cols - 5) {
                val t = grid[r][c] // 기준 컬러 마스크
                if (t == 0) continue

                // 0, 1, 3, 4번째 칸이 전부 같고, 정중앙 2번째(X) 자리만 색이 다를 때
                if (grid[r][c+1] == t && grid[r][c+3] == t && grid[r][c+4] == t && grid[r][c+2] != t) {
                    val targetRow = r
                    val targetCol = c + 2 // 우리가 무조건 채워넣어야 하는 정가운데 조각 위치

                    // 가로가 고정 완성 세트이므로 수직축(위/아래)에서만 같은 색 조각을 끌어올 수 있음!
                    // 위에서 정가운데로 당겨 내리기
                    if (targetRow - 1 >= 0 && grid[targetRow - 1][targetCol] == t) {
                        return MatchHint(fromR = targetRow - 1, fromC = targetCol, toR = targetRow, toC = targetCol)
                    }
                    // 아래에서 정가운데로 당겨 올리기
                    if (targetRow + 1 < rows && grid[targetRow + 1][targetCol] == t) {
                        return MatchHint(fromR = targetRow + 1, fromC = targetCol, toR = targetRow, toC = targetCol)
                    }
                }
            }
        }

        // 2. 세로 방향 [ O O X O O ] 찾아내기
        for (c in 0 until cols) {
            for (r in 0..rows - 5) {
                val t = grid[r][c]
                if (t == 0) continue

                // 세로축으로 0, 1, 3, 4번째가 일치하고 가운데 2번째(X)만 다를 때
                if (grid[r+1][c] == t && grid[r+3][c] == t && grid[r+4][c] == t && grid[r+2][c] != t) {
                    val targetRow = r + 2 // 채워넣어야 하는 정가운데 조각 위치
                    val targetCol = c

                    // 세로가 고정 완성 세트이므로 수평축(좌/우)에서만 같은 색 조각을 밀어 넣을 수 있음!
                    // 왼쪽에서 정가운데로 밀어 넣기
                    if (targetCol - 1 >= 0 && grid[targetRow][targetCol - 1] == t) {
                        return MatchHint(fromR = targetRow, fromC = targetCol - 1, toR = targetRow, toC = targetCol)
                    }
                    // 오른쪽에서 정가운데로 밀어 넣기
                    if (targetCol + 1 < cols && grid[targetRow][targetCol + 1] == t) {
                        return MatchHint(fromR = targetRow, fromC = targetCol + 1, toR = targetRow, toC = targetCol)
                    }
                }
            }
        }
        return null // 찬스 없음
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
            Log.e(TAG, "자원 정리 실패", e)
        }
    }

    data class MatchHint(val fromR: Int, val fromC: Int, val toR: Int, val toC: Int)
}

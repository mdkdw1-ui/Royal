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
                .setContentTitle("로얄매치 앵커 고도화 헬퍼")
                .setContentText("실시간 컬러 밀도 앵커 트래킹 가동 중")
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
                text = "● SMART ANCHOR v2 "
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
            try { analyzeScreenFast() } catch (e: Exception) { Log.e(TAG, "루프 스킵", e) }
            backgroundHandler?.postDelayed(this, 250) // 반응 속도 상향 조절
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

            // 🎯 [핵심 개선안] 컬러 밀도 매핑을 통한 실제 게임 퍼즐판 영역 정밀 추출
            var minBlockX = screenWidth
            var maxBlockX = 0
            var minBlockY = screenHeight
            var maxBlockY = 0
            var detectedBlockCount = 0

            // 보드판이 위치할 수 있는 최적 추적 윈도우 스캔 (가로/세로 촘촘한 그리드 샘플링)
            val stepX = 25
            val stepY = 25
            val scanLeft = (screenWidth * 0.03f).toInt()
            val scanRight = (screenWidth * 0.97f).toInt()
            val scanTop = (screenHeight * 0.30f).toInt()
            val scanBottom = (screenHeight * 0.88f).toInt()

            for (y in scanTop until scanBottom step stepY) {
                for (x in scanLeft until scanRight step stepX) {
                    val colorId = identifyColorHSV(bitmap.getPixel(x, y))
                    if (colorId in 1..5) { // 유효한 블록 컬러가 발견된 경우에만 바운더리 확장
                        if (x < minBlockX) minBlockX = x
                        if (x > maxBlockX) maxBlockX = x
                        if (y < minBlockY) minBlockY = y
                        if (y > maxBlockY) maxBlockY = y
                        detectedBlockCount++
                    }
                }
            }

            // 노이즈 방지 검증: 발견된 유효 블록 노드가 너무 적으면 보드판 미형성으로 판단하고 스킵
            if (detectedBlockCount < 15 || (maxBlockX - minBlockX) < screenWidth * 0.5) {
                overlayView?.post { overlayView?.clearHint() }
                return
            }

            // 마진을 주어 실제 첫 번째 블록과 마지막 블록의 중심선 안착 유도
            val boardLeft = minBlockX
            val boardRight = maxBlockX
            val boardTop = minBlockY
            val boardBottom = maxBlockY
            
            val boardWidth = boardRight - boardLeft
            val boardHeight = boardBottom - boardTop

            // 🎯 가변 격자 행/열 동적 추정 연산 (8x8 스탠다드 및 4x10 등의 변형 하단 패널 모두 대응)
            // 보통 블록의 한 칸당 크기는 대략 100~135픽셀 사이입니다. 이를 기반으로 유동적 나눗셈 계산
            val approxBlockSize = screenWidth / 8.5f
            val currentGridCols = Math.round(boardWidth.toFloat() / approxBlockSize + 0.3f).coerceIn(5, 11)
            val currentGridRows = Math.round(boardHeight.toFloat() / approxBlockSize + 0.3f).coerceIn(3, 11)

            // ★ 메일 제안 완벽 구현: 오차 누적을 원천 차단하는 동적 앵커 간격 계산법 적용
            val strideX = if (currentGridCols > 1) boardWidth.toFloat() / (currentGridCols - 1) else approxBlockSize
            val strideY = if (currentGridRows > 1) boardHeight.toFloat() / (currentGridRows - 1) else approxBlockSize

            val topLeftAnchorX = boardLeft.toFloat()
            val topLeftAnchorY = boardTop.toFloat()

            // 🎯 확정된 동적 앵커 매트릭스를 기반으로 컬러 격자(Grid) 배열 빌드
            val colorGrid = Array(currentGridRows) { IntArray(currentGridCols) }
            for (r in 0 until currentGridRows) {
                for (c in 0 until currentGridCols) {
                    val cx = (topLeftAnchorX + (c * strideX)).toInt().coerceIn(0, screenWidth - 1)
                    val cy = (topLeftAnchorY + (r * strideY)).toInt().coerceIn(0, screenHeight - 1)
                    colorGrid[r][c] = getPixelBlockColor(bitmap, cx, cy)
                }
            }

            // ⚡ 5-Ball 매칭 연산 엔진 가동
            val hint = findExactOOXOOMatch5(colorGrid, currentGridRows, currentGridCols)
            if (hint != null) {
                val fx = topLeftAnchorX + (hint.fromC * strideX)
                val fy = topLeftAnchorY + (hint.fromR * strideY)
                val tx = topLeftAnchorX + (hint.toC * strideX)
                val ty = topLeftAnchorY + (hint.toR * strideY)
                
                val displayBlockSize = strideX
                overlayView?.post { overlayView?.setHint(fx, fy, tx, ty, displayBlockSize) }
            } else {
                overlayView?.post { overlayView?.clearHint() }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "프레임 분석 루프 예외", e)
        } finally {
            try { image.close() } catch (e: Exception) {}
        }
    }

    private fun getPixelBlockColor(bitmap: Bitmap, cx: Int, cy: Int): Int {
        val votes = IntArray(6)
        // 중심부 주변 5점 샘플링 표결 처리로 판독 정밀도 상향
        votes[identifyColorHSV(bitmap.getPixel(cx, cy))]++
        votes[identifyColorHSV(bitmap.getPixel((cx - 8).coerceIn(0, screenWidth - 1), cy))]++
        votes[identifyColorHSV(bitmap.getPixel((cx + 8).coerceIn(0, screenWidth - 1), cy))]++
        votes[identifyColorHSV(bitmap.getPixel(cx, (cy - 8).coerceIn(0, screenHeight - 1)))]++
        votes[identifyColorHSV(bitmap.getPixel(cx, (cy + 8).coerceIn(0, screenHeight - 1)))]++
        
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
        if (r + g + b < 100 || r + g + b > 710) return 0

        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)
        val hue = hsv[0]
        val sat = hsv[1]
        val value = hsv[2]

        if (sat < 0.16f || value < 0.16f) return 0

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
        // 1. 가로축 5개 일렬 매칭 수색 [ O O X O O ]
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

        // 2. 세로축 5개 일렬 매칭 수색 [ O O X O O ]
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

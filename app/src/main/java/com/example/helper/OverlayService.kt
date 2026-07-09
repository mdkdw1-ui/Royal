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
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

class OverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var overlayView: PatternDrawView? = null
    private var controlView: LinearLayout? = null // 💡 실행 상태 및 킬 스위치를 담을 오버레이 박스
    
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    
    private var screenWidth = 1080
    private var screenHeight = 2400
    private val GRID_COLS = 8
    private val GRID_ROWS = 8

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification: Notification = NotificationCompat.Builder(this, "helper_channel")
            .setContentTitle("로얄매치 도우미 작동 중")
            .setContentText("백그라운드 스레드에서 초고속으로 매칭 패턴을 분석 중입니다.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        // 💡 [튕김 해결 핵심] Android 10(Q) 이상 버전에서는 미디어 프로젝션 타입을 반드시 명시해야 튕기지 않습니다.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "helper_channel", "로얄매치 서비스",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager?.defaultDisplay?.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        // 1. 투명 힌트 라인 뷰 생성 및 윈도우 추가
        overlayView = PatternDrawView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        try { windowManager?.addView(overlayView, params) } catch (e: Exception) { e.printStackTrace() }

        // 2. 💡 [신규 기능] 실행 상태 표시기 + 킬 스위치 컨트롤 레이아웃 동적 생성
        showControlOverlay()

        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_OK) ?: Activity.RESULT_OK
        val dataIntent = intent?.getParcelableExtra<Intent>("DATA_INTENT")
        
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
            
            backgroundHandler?.post(analyzeRunnable)
        }
        return START_NOT_STICKY
    }

    // 💡 [신규 기능] 화면 우상단에 띄울 조그만 컨트롤러 박스 (작동 표시등 + STOP 버튼)
    private fun showControlOverlay() {
        val context = this
        controlView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(30, 15, 30, 15)
            // 반투명한 검은색 라운드 배경 만들기
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#AA000000")) 
                cornerRadius = 30f
            }
        }

        // "● RUNNING" 초록색 텍스트
        val statusText = TextView(context).apply {
            text = "● RUNNING"
            setTextColor(Color.GREEN)
            textSize = 12f
            setPadding(0, 0, 25, 0)
        }

        // "STOP" 킬 스위치 버튼
        val stopButton = Button(context).apply {
            text = "STOP"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#FF3B30")) // 아이폰 스타일 레드
            textSize = 11f
            // 버튼을 클릭하면 서비스 자체가 안전하게 파괴(종료)됩니다.
            setOnClickListener {
                stopSelf() 
            }
        }

        controlView?.addView(statusText)
        controlView?.addView(stopButton)

        // 클릭이 먹혀야 하므로 FLAG_NOT_TOUCHABLE은 넣지 않습니다. 대신 포커스만 안 뺏기게 설정.
        val controlParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END // 우측 상단 배치
            x = 40
            y = 120 // 상단 바 겹침 방지 여백
        }

        try {
            windowManager?.addView(controlView, controlParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val analyzeRunnable = object : Runnable {
        override fun run() {
            analyzeScreenFast()
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

            val bitmap = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)

            var boardTop = 0
            var boardBottom = 0
            var boardLeft = 0
            var boardRight = 0

            val centerX = screenWidth / 2
            val startY = (screenHeight * 0.25).toInt()
            val endY = (screenHeight * 0.80).toInt()

            for (y in startY..endY) {
                val pixel = bitmap.getPixel(centerX, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                if (r in 30..95 && g in 25..85 && b in 20..80) {
                    if (boardTop == 0) boardTop = y
                    boardBottom = y
                }
            }

            if (boardTop == 0 || boardBottom == 0 || (boardBottom - boardTop) < 300) {
                boardTop = (screenHeight * 0.35).toInt()
                boardBottom = (screenHeight * 0.75).toInt()
            }

            val targetMidY = boardTop + (boardBottom - boardTop) / 2
            for (x in (screenWidth * 0.02).toInt() until centerX) {
                val pixel = bitmap.getPixel(x, targetMidY)
                if (Color.red(pixel) in 30..95) {
                    boardLeft = x
                    break
                }
            }
            for (x in (screenWidth * 0.98).toInt() downTo centerX) {
                val pixel = bitmap.getPixel(x, targetMidY)
                if (Color.red(pixel) in 30..95) {
                    boardRight = x
                    break
                }
            }

            if (boardLeft == 0 || boardRight == 0 || (boardRight - boardLeft) < 500) {
                boardLeft = (screenWidth * 0.05).toInt()
                boardRight = (screenWidth * 0.95).toInt()
            }

            val boardWidth = boardRight - boardLeft
            val blockSize = boardWidth / GRID_COLS
            val colorGrid = Array(GRID_ROWS) { IntArray(GRID_COLS) }

            for (r in 0 until GRID_ROWS) {
                for (c in 0 until GRID_COLS) {
                    val pixelX = boardLeft + (c * blockSize) + (blockSize / 2)
                    val pixelY = boardTop + (r * blockSize) + (blockSize / 2)
                    
                    if (pixelX < bitmap.width && pixelY < bitmap.height) {
                        val pixel = bitmap.getPixel(pixelX, pixelY)
                        colorGrid[r][c] = identifyColorSpec(pixel)
                    }
                }
            }

            val hint = findFiveMatchPattern(colorGrid, GRID_ROWS, GRID_COLS)
            
            if (hint != null) {
                val fx = boardLeft + (hint.fromC * blockSize) + (blockSize / 2).toFloat()
                val fy = boardTop + (hint.fromR * blockSize) + (blockSize / 2).toFloat()
                val tx = boardLeft + (hint.toC * blockSize) + (blockSize / 2).toFloat()
                val ty = boardTop + (hint.toR * blockSize) + (blockSize / 2).toFloat()
                
                overlayView?.post {
                    overlayView?.setHint(fx, fy, tx, ty, blockSize.toFloat())
                }
            } else {
                overlayView?.post {
                    overlayView?.clearHint()
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            image.close() 
        }
    }

    private fun identifyColorSpec(pixel: Int): Int {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        
        if (r + g + b < 100) return 0 

        return when {
            r > 150 && g > 140 && b < 130 -> 3   // 💛 노랑
            r > 140 && g < 90 && b < 90   -> 1   // ❤️ 빨강
            b > 140 && r < 100 && g < 130 -> 2   // 💙 파랑
            g > 130 && r < 100 && b < 100 -> 4   // 💚 초록
            r > 130 && b > 140 && g < 100 -> 5   // 💜 보라
            else -> 0
        }
    }

    data class MatchHint(val fromR: Int, val fromC: Int, val toR: Int, val toC: Int)

    private fun findFiveMatchPattern(grid: Array<IntArray>, rows: Int, cols: Int): MatchHint? {
        val directions = arrayOf(Pair(0, 1), Pair(1, 0))
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (grid[r][c] == 0) continue
                for (dir in directions) {
                    val nr = r + dir.first
                    val nc = c + dir.second
                    if (nr < rows && nc < cols && grid[nr][nc] != 0) {
                        val temp = grid[r][c]
                        grid[r][c] = grid[nr][nc]
                        grid[nr][nc] = temp

                        if (checkGridMatch5(grid, rows, cols)) {
                            return MatchHint(r, c, nr, nc)
                        }
                        
                        grid[nr][nc] = grid[r][c]
                        grid[r][c] = temp
                    }
                }
            }
        }
        return null
    }

    private fun checkGridMatch5(grid: Array<IntArray>, rows: Int, cols: Int): Boolean {
        for (r in 0 until rows) {
            for (c in 0..cols - 5) {
                val color = grid[r][c]
                if (color != 0 && color == grid[r][c+1] && color == grid[r][c+2] && color == grid[r][c+3] && color == grid[r][c+4]) return true
            }
        }
        for (c in 0 until cols) {
            for (r in 0..rows - 5) {
                val color = grid[r][c]
                if (color != 0 && color == grid[r+1][c] && color == grid[r+2][c] && color == grid[r+3][c] && color == grid[r+4][c]) return true
            }
        }
        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundHandler?.removeCallbacks(analyzeRunnable)
        backgroundThread?.quitSafely() // 💡 오타 수정 완료됨
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        
        // 서비스가 꺼질 때 띄워둔 모든 오버레이 창을 안전하게 철거합니다.
        if (overlayView != null) {
            windowManager?.removeView(overlayView)
        }
        if (controlView != null) {
            windowManager?.removeView(controlView)
        }
    }
}

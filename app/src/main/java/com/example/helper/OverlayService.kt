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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

class OverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var overlayView: PatternDrawView? = null
    private var widgetView: LinearLayout? = null // 💡 킬 스위치를 포함할 독립형 소형 위젯 레이아웃
    
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    private val handler = Handler(Looper.getMainLooper())
    private var screenWidth = 1080
    private var screenHeight = 2400

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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
        val notification: Notification = NotificationCompat.Builder(this, "helper_channel")
            .setContentTitle("로얄매치 도우미 작동 중")
            .setContentText("실시간으로 5개 매칭 패턴을 감지하고 있습니다.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager?.defaultDisplay?.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        // 1. 투명 힌트 레이어 설치 (전체화면, 클릭 관통)
        if (overlayView == null) {
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
                e.printStackTrace()
            }
        }

        // 2. 💡 [기능 추가] 작동 상태창 및 킬 스위치 전용 터치 가능 레이어 설치
        if (widgetView == null) {
            val density = resources.displayMetrics.density
            
            widgetView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#AA000000")) // 반투명 검정 배경
                    cornerRadius = 12f * density // 둥근 모서리
                }
                setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
            }

            // 초록색 불빛 점
            val dotView = TextView(this).apply {
                text = "● "
                setTextColor(Color.parseColor("#00FF66"))
                textSize = 13f
            }

            // 안내 문구
            val textView = TextView(this).apply {
                text = "도우미 작동 중 "
                setTextColor(Color.WHITE)
                textSize = 13f
                paint.isFakeBoldText = true
            }

            // 🔥 킬 스위치 버튼 [종료]
            val killButton = TextView(this).apply {
                text = " [종료]"
                setTextColor(Color.parseColor("#FF3B30")) // 선명한 빨간색
                textSize = 13f
                paint.isFakeBoldText = true
                setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
                
                // 클릭 시 도우미 즉시 완전 종료
                setOnClickListener {
                    stopSelf() 
                }
            }

            widgetView?.addView(dotView)
            widgetView?.addView(textView)
            widgetView?.addView(killButton)

            // 버튼 클릭이 먹혀야 하므로 FLAG_NOT_TOUCHABLE을 넣지 않습니다.
            val widgetParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 40
                y = 130
            }

            try {
                windowManager?.addView(widgetView, widgetParams)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_OK) ?: Activity.RESULT_OK
        val dataIntent = intent?.getParcelableExtra<Intent>("DATA_INTENT")
        if (dataIntent != null && mediaProjection == null) {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            try {
                mediaProjection = mpManager.getMediaProjection(resultCode, dataIntent)
                imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "ScreenCapture", screenWidth, screenHeight, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, null
                )
                handler.removeCallbacks(analyzeRunnable)
                handler.post(analyzeRunnable)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return START_NOT_STICKY
    }

    private val analyzeRunnable = object : Runnable {
        override fun run() {
            analyzeScreen()
            handler.postDelayed(this, 1000)
        }
    }

    private fun analyzeScreen() {
        val image = try { imageReader?.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            val planes = image.planes ?: return
            if (planes.isEmpty()) return
            val buffer = planes[0].buffer ?: return
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmapWidth = screenWidth + if (pixelStride > 0) rowPadding / pixelStride else 0
            if (bitmapWidth <= 0 || screenHeight <= 0) return
            val bitmap = Bitmap.createBitmap(bitmapWidth, screenHeight, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)

            var boardTop = 0; var boardBottom = 0; var boardLeft = 0; var boardRight = 0
            val centerX = screenWidth / 2
            val startY = (screenHeight * 0.25).toInt()
            val endY = (screenHeight * 0.80).toInt()

            for (y in startY until minOf(endY, bitmap.height)) {
                if (centerX >= bitmap.width) break
                val pixel = bitmap.getPixel(centerX, y)
                val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)
                if (r in 30..95 && g in 25..85 && b in 20..80) {
                    if (boardTop == 0) boardTop = y
                    boardBottom = y
                }
            }
            if (boardTop == 0 || boardBottom == 0 || (boardBottom - boardTop) < 300) {
                boardTop = (screenHeight * 0.35).toInt()
                boardBottom = (screenHeight * 0.75).toInt()
            }

            val targetMidY = minOf(boardTop + (boardBottom - boardTop) / 2, bitmap.height - 1)
            for (x in (screenWidth * 0.02).toInt() until minOf(centerX, bitmap.width)) {
                if (Color.red(bitmap.getPixel(x, targetMidY)) in 30..95) { boardLeft = x; break }
            }
            for (x in minOf((screenWidth * 0.98).toInt(), bitmap.width - 1) downTo centerX) {
                if (x < 0) break
                if (Color.red(bitmap.getPixel(x, targetMidY)) in 30..95) { boardRight = x; break }
            }
            if (boardLeft == 0 || boardRight == 0 || (boardRight - boardLeft) < 500) {
                boardLeft = (screenWidth * 0.05).toInt(); boardRight = (screenWidth * 0.95).toInt()
            }

            val totalWidth = boardRight - boardLeft
            val currentCols = if (totalWidth / 8 > screenWidth * 0.105) 8 else 9
            val currentRows = currentCols
            val blockSize = totalWidth / currentCols
            val colorGrid = Array(currentRows) { IntArray(currentCols) }

            for (r in 0 until currentRows) {
                for (c in 0 until currentCols) {
                    val pixelX = boardLeft + (c * blockSize) + (blockSize / 2)
                    val pixelY = boardTop + (r * blockSize) + (blockSize / 2)
                    if (pixelX in 0 until bitmap.width && pixelY in 0 until bitmap.height) {
                        colorGrid[r][c] = simplifyColor(bitmap.getPixel(pixelX, pixelY))
                    }
                }
            }
            bitmap.recycle()

            val hint = findFiveMatch(colorGrid, currentRows, currentCols)
            if (hint != null) {
                val fx = boardLeft + (hint.fromC * blockSize) + (blockSize / 2).toFloat()
                val fy = boardTop + (hint.fromR * blockSize) + (blockSize / 2).toFloat()
                val tx = boardLeft + (hint.toC * blockSize) + (blockSize / 2).toFloat()
                val ty = boardTop + (hint.toR * blockSize) + (blockSize / 2).toFloat()
                overlayView?.setHint(fx, fy, tx, ty, blockSize.toFloat())
            } else {
                overlayView?.clearHint()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { image.close() } catch (e: Exception) {}
        }
    }

    private fun simplifyColor(pixel: Int): Int {
        val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)
        if (r + g + b < 120) return 0
        return when {
            r > 130 && r > g * 1.3 && r > b * 1.3 -> 1
            b > 120 && b > r * 1.15 && b > g * 1.05 -> 2
            r > 140 && g > 130 && b < 120 -> 3
            g > 110 && g > r * 1.2 && g > b * 1.2 -> 4
            r > 110 && b > 120 && g < 100 -> 5
            else -> 0
        }
    }

    data class MatchHint(val fromR: Int, val fromC: Int, val toR: Int, val toC: Int)

    private fun findFiveMatch(grid: Array<IntArray>, rows: Int, cols: Int): MatchHint? {
        val directions = arrayOf(Pair(0, 1), Pair(1, 0))
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (grid[r][c] == 0) continue
                for (dir in directions) {
                    val nr = r + dir.first; val nc = c + dir.second
                    if (nr < rows && nc < cols && grid[nr][nc] != 0) {
                        val temp = grid[r][c]; grid[r][c] = grid[nr][nc]; grid[nr][nc] = temp
                        if (checkBoardMatch5(grid, rows, cols)) return MatchHint(r, c, nr, nc)
                        grid[nr][nc] = grid[r][c]; grid[r][c] = temp
                    }
                }
            }
        }
        return null
    }

    private fun checkBoardMatch5(grid: Array<IntArray>, rows: Int, cols: Int): Boolean {
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
        handler.removeCallbacks(analyzeRunnable)
        try {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
            
            // 💡 종료 시 모든 창 제거
            if (overlayView != null) { windowManager?.removeView(overlayView); overlayView = null }
            if (widgetView != null) { windowManager?.removeView(widgetView); widgetView = null }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

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
    private var widgetView: LinearLayout? = null
    
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

        if (overlayView == null) {
            overlayView = PatternDrawView(this)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            try { windowManager?.addView(overlayView, params) } catch (e: Exception) { e.printStackTrace() }
        }

        if (widgetView == null) {
            val density = resources.displayMetrics.density
            widgetView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#AA000000"))
                    cornerRadius = 12f * density
                }
                setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
            }

            val dotView = TextView(this).apply { text = "● "; setTextColor(Color.parseColor("#00FF66")); textSize = 13f }
            val textView = TextView(this).apply { text = "도우미 작동 중 "; setTextColor(Color.WHITE); textSize = 13f; paint.isFakeBoldText = true }
            val killButton = TextView(this).apply {
                text = " [종료]"
                setTextColor(Color.parseColor("#FF3B30"))
                textSize = 13f
                paint.isFakeBoldText = true
                setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
                setOnClickListener { stopSelf() }
            }

            widgetView?.addView(dotView)
            widgetView?.addView(textView)
            widgetView?.addView(killButton)

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
            try { windowManager?.addView(widgetView, widgetParams) } catch (e: Exception) { e.printStackTrace() }
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
            } catch (e: Exception) { e.printStackTrace() }
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

            // 💡 [핵심 브레인 개조] 유효한 블록 색상들이 감지되는 영역의 경계를 찾아 자동으로 보드판을 설정합니다.
            var minX = screenWidth; var maxX = 0
            var minY = screenHeight; var maxY = 0
            
            // 성능 최적화를 위해 15픽셀 단위로 빠르게 화면을 훑습니다.
            val scanStep = 15
            val scanStartY = (screenHeight * 0.25).toInt()
            val scanEndY = (screenHeight * 0.85).toInt()
            val scanStartX = (screenWidth * 0.05).toInt()
            val scanEndX = (screenWidth * 0.95).toInt()

            for (y in scanStartY until minOf(scanEndY, bitmap.height) step scanStep) {
                for (x in scanStartX until minOf(scanEndX, bitmap.width) step scanStep) {
                    val colorType = simplifyColor(bitmap.getPixel(x, y))
                    if (colorType != 0) { // 어떤 종류든 유효한 게임 블록이 발견되면 좌표 수집
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }

            val boardLeft: Int
            val boardRight: Int
            val boardTop: Int
            val boardBottom: Int

            // 블록이 정상적으로 밀집 수집된 경우 자동 경계 설정
            if (maxX - minX > 300 && maxY - minY > 300) {
                val blockEstimate = (maxX - minX) / 16 // 마진 버퍼 계산
                boardLeft = maxOf(0, minX - blockEstimate)
                boardRight = minOf(screenWidth - 1, maxX + blockEstimate)
                boardTop = maxOf(0, minY - blockEstimate)
                boardBottom = minOf(screenHeight - 1, maxY + blockEstimate)
            } else {
                // 탐색 실패 시 가장 안전한 기본 하드코딩 비율 백업 작동
                boardLeft = (screenWidth * 0.05).toInt()
                boardRight = (screenWidth * 0.95).toInt()
                boardTop = (screenHeight * 0.35).toInt()
                boardBottom = (screenHeight * 0.75).toInt()
            }

            val totalWidth = boardRight - boardLeft
            val currentCols = if (totalWidth / 8 > screenWidth * 0.105) 8 else 9
            val currentRows = currentCols
            val blockSize = totalWidth / currentCols
            val colorGrid = Array(currentRows) { IntArray(currentCols) }

            // 획득한 보드판 경계를 기준으로 완벽하게 정렬된 격자 세포 샘플링 진행
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

    // 💡 [노란색 및 주요 색상 감도 대폭 완화 수정]
    private fun simplifyColor(pixel: Int): Int {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        
        if (r + g + b < 100) return 0 // 너무 어두운 암흑 픽셀 제외
        
        return when {
            // 💛 노랑 (왕관): R과 G가 동시에 타 채널 대비 압도적으로 높고 B가 확실히 낮을 때 (감도 하향 조정 완료)
            r > 140 && g > 130 && b < 140 -> 3
            
            // ❤️ 빨강 (책)
            r > 130 && r > g * 1.25 && r > b * 1.25 -> 1
            
            // 💙 파랑 (방패)
            b > 120 && b > r * 1.15 && b > g * 1.05 -> 2
            
            // 💚 초록 (클로버)
            g > 110 && g > r * 1.2 && g > b * 1.2 -> 4
            
            // 💜 보라 (모자)
            r > 110 && b > 120 && g < r * 0.85 -> 5
            
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
            if (overlayView != null) { windowManager?.removeView(overlayView); overlayView = null }
            if (widgetView != null) { windowManager?.removeView(widgetView); widgetView = null }
        } catch (e: Exception) { e.printStackTrace() }
    }
}

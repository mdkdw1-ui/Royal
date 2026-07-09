package com.example.helper

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat

class OverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var overlayView: PatternDrawView? = null
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
        val notification: Notification = NotificationCompat.Builder(this, "helper_channel")
            .setContentTitle("로얄매치 도우미 작동 중")
            .setContentText("실시간으로 5개 매칭 패턴을 감지하고 있습니다.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(1, notification)
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

        overlayView = PatternDrawView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        windowManager?.addView(overlayView, params)

        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_OK) ?: Activity.RESULT_OK
        val dataIntent = intent?.getParcelableExtra<Intent>("DATA_INTENT")
        if (dataIntent != null) {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, dataIntent)
            
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture", screenWidth, screenHeight, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, null
            )
            handler.post(analyzeRunnable)
        }
        return START_NOT_STICKY
    }

    private val analyzeRunnable = object : Runnable {
        override fun run() {
            analyzeScreen()
            handler.postDelayed(this, 800) // 0.8초마다 반복 분석
        }
    }

    private fun analyzeScreen() {
        val image = imageReader?.acquireLatestImage() ?: return
        try {
            val planes = image.planes
            val buffer = planes.buffer
            val pixelStride = planes.pixelStride
            val rowStride = planes.rowStride
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

            // 1. 보드판의 상하 경계 탐색
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

            // 2. 보드판의 좌우 경계 탐색
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

            val totalWidth = boardRight - boardLeft
            val currentCols = if (totalWidth / 8 > screenWidth * 0.105) 8 else 9
            val currentRows = currentCols
            val blockSize = totalWidth / currentCols

            val colorGrid = Array(currentRows) { IntArray(currentCols) }

            // 3. 각 칸의 픽셀 색상 채취 및 2차원 배열 매핑
            for (r in 0 until currentRows) {
                for (c in 0 until currentCols) {
                    val pixelX = boardLeft + (c * blockSize) + (blockSize / 2)
                    val pixelY = boardTop + (r * blockSize) + (blockSize / 2)
                    if (pixelX < bitmap.width && pixelY < bitmap.height) {
                        val pixel = bitmap.getPixel(pixelX, pixelY)
                        colorGrid[r][c] = simplifyColor(pixel)
                    }
                }
            }

            // 4. 5개 매칭 탐색 후 결과 반영
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
            image.close()
        }
    }

    // 로얄 매치 전용 블록 색상 스펙트럼 보정
    private fun simplifyColor(pixel: Int): Int {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        
        return when {
            r > 200 && g < 60 && b < 60 -> 1    // 책 (빨강)
            r < 60 && g > 100 && b > 200 -> 2   // 왕관 (파랑)
            r > 210 && g > 180 && b < 50 -> 3   // 동전 (노랑)
            r < 60 && g > 180 && b < 90 -> 4    // 클로버 (초록)
            r > 140 && g < 60 && b > 180 -> 5   // 모자 (보라)
            else -> 0
        }
    }

    data class MatchHint(val fromR: Int, val fromC: Int, val toR: Int, val toC: Int)

    // 한 칸 스왑 시뮬레이션을 통한 Match-5 탐색
    private fun findFiveMatch(grid: Array<IntArray>, rows: Int, cols: Int): MatchHint? {
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

                        if (checkBoardMatch5(grid, rows, cols)) {
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
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        if (overlayView != null) windowManager?.removeView(overlayView)
    }
}

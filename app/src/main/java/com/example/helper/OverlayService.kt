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
        // 알림창 생성
        val notification: Notification = NotificationCompat.Builder(this, "helper_channel")
            .setContentTitle("로얄매치 도우미 작동 중")
            .setContentText("실시간으로 5개 매칭 패턴을 감지하고 있습니다.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        // 💡 [안드로이드 14 대응] 서비스 시작 시 미디어 프로젝션 타입을 명시하여 크래시를 방지합니다.
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

        // 오버레이 뷰가 중복으로 생성되지 않도록 안전장치 마련
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
            handler.postDelayed(this, 1000) // 분석 주기를 1초로 소폭 늘려 과부하 방지
        }
    }

    private fun analyzeScreen() {
        // 이미지 래더가 없거나 이미지를 가져오지 못하면 크래시 없이 리턴
        val image = try { imageReader?.acquireLatestImage() } catch (e: Exception) { null } ?: return
        
        // 💡 전체 로직을 대형 try-catch로 감싸서, 내부 연산 오류로 인해 앱이 종료되는 문제를 원천 차단합니다.
        try {
            val planes = image.planes
            if (planes == null || planes.isEmpty()) return
            
            val buffer = planes[0].buffer ?: return
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            // 안전하게 비트맵 생성 시도
            val bitmapWidth = screenWidth + if (pixelStride > 0) rowPadding / pixelStride else 0
            if (bitmapWidth <= 0 || screenHeight <= 0) return
            
            val bitmap = Bitmap.createBitmap(bitmapWidth, screenHeight, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)

            var boardTop = 0
            var boardBottom = 0
            var boardLeft = 0
            var boardRight = 0

            val centerX = screenWidth / 2
            val startY = (screenHeight * 0.25).toInt()
            val endY = (screenHeight * 0.80).toInt()

            // 1. 보드판의 상하 경계 탐색 (비트맵 범위 초과 방지 조치)
            for (y in startY until minOf(endY, bitmap.height)) {
                if (centerX >= bitmap.width) break
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
            val targetMidY = minOf(boardTop + (boardBottom - boardTop) / 2, bitmap.height - 1)
            for (x in (screenWidth * 0.02).toInt() until minOf(centerX, bitmap.width)) {
                val pixel = bitmap.getPixel(x, targetMidY)
                if (Color.red(pixel) in 30..95) {
                    boardLeft = x
                    break
                }
            }
            for (x in minOf((screenWidth * 0.98).toInt(), bitmap.width - 1) downTo centerX) {
                if (x < 0) break
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
                    if (pixelX >= 0 && pixelX < bitmap.width && pixelY >= 0 && pixelY < bitmap.height) {
                        val pixel = bitmap.getPixel(pixelX, pixelY)
                        colorGrid[r][c] = simplifyColor(pixel)
                    }
                }
            }

            // 비트맵 자원 즉시 해제하여 메모리 부족(OOM) 방지
            bitmap.recycle()

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
            e.printStackTrace() // 어떤 에러가 나든 기록만 하고 앱이 꺼지진 않음
        } finally {
            try { image.close() } catch (e: Exception) {}
        }
    }

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
        try {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
            if (overlayView != null) windowManager?.removeView(overlayView)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

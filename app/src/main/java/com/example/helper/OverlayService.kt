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
    private val GRID_COLS = 8
    private val GRID_ROWS = 8
    private var reusableBitmap: Bitmap? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "helper_channel", "로얄매치 서비스",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            createNotificationChannel()
            val notification: Notification = NotificationCompat.Builder(this, "helper_channel")
                .setContentTitle("로얄매치 도우미 작동 중")
                .setContentText("백그라운드 스레드에서 매칭 패턴을 분석 중입니다.")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground 승격 실패", e)
            stopSelf()
            return START_NOT_STICKY
        }

        return try {
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
            try { 
                windowManager?.addView(overlayView, params) 
            } catch (e: Exception) { 
                Log.e(TAG, "Failed to add overlay view", e)
            }

            showControlOverlay()

            val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_OK) ?: Activity.RESULT_OK
            
            val dataIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra("DATA_INTENT", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra<Intent>("DATA_INTENT")
            }
            
            if (dataIntent != null) {
                val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mpManager.getMediaProjection(resultCode, dataIntent)
                
                backgroundThread = HandlerThread("ScreenCaptureThread").apply { start() }
                backgroundHandler = Handler(backgroundThread!!.looper)

                imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
                
                // 💡 액티비티가 전면에 살아있는 상태이므로 가상 디스플레이가 에러 없이 완벽하게 생성됩니다.
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "ScreenCapture", screenWidth, screenHeight, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, backgroundHandler
                )
                
                backgroundHandler?.post(analyzeRunnable)
                Log.d(TAG, "서비스가 성공적으로 루프를 시작했습니다.")

                // 💡 [안드로이드 14 핵심 수정] 모든 캡처 연동이 끝났으니, 메인 액티비티에게 안전하게 숨으라고 신호를 보냅니다.
                val minimizeIntent = Intent(this, MainActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("ACTION_MINIMIZE", true)
                }
                startActivity(minimizeIntent)

            } else {
                Log.e(TAG, "dataIntent가 누락되었습니다.")
                stopSelf()
            }
            START_NOT_STICKY
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand 내부 실패", e)
            stopSelf()
            START_NOT_STICKY
        }
    }

    private fun showControlOverlay() {
        try {
            val themedContext = ContextThemeWrapper(this, androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
            
            controlView = LinearLayout(themedContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(30, 15, 30, 15)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#AA000000")) 
                    cornerRadius = 30f
                }
            }

            val statusText = TextView(themedContext).apply {
                text = "● RUNNING"
                setTextColor(Color.GREEN)
                textSize = 12f
                setPadding(0, 0, 25, 0)
            }

            val stopButton = Button(themedContext).apply {
                text = "STOP"
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#FF3B30")) 
                textSize = 11f
                setOnClickListener {
                    stopSelf() 
                }
            }

            controlView?.addView(statusText)
            controlView?.addView(stopButton)

            val controlParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END 
                x = 40
                y = 150 
            }

            try {
                windowManager?.addView(controlView, controlParams)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add control view", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "showControlOverlay failed", e)
        }
    }

    private val analyzeRunnable = object : Runnable {
        override fun run() {
            try {
                analyzeScreenFast()
            } catch (e: Exception) {
                Log.e(TAG, "analyzeScreenFast error", e)
            }
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

            val adjustedWidth = screenWidth + rowPadding / pixelStride

            if (reusableBitmap == null || reusableBitmap!!.width != adjustedWidth || reusableBitmap!!.height != screenHeight) {
                reusableBitmap = Bitmap.createBitmap(adjustedWidth, screenHeight, Bitmap.Config.ARGB_8888)
            }
            
            val bitmap = reusableBitmap!!
            buffer.rewind() 
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
                    
                    if (pixelX >= 0 && pixelX < bitmap.width && pixelY >= 0 && pixelY < bitmap.height) {
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

        } catch (e: Throwable) { 
            Log.e(TAG, "analyzeScreenFast exception", e)
        } finally {
            try {
                image.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing image", e)
            }
        }
    }

    private fun identifyColorSpec(pixel: Int): Int {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        
        if (r + g + b < 100) return 0 

        return when {
            r > 150 && g > 140 && b < 130 -> 3
            r > 140 && g < 90 && b < 90   -> 1
            b > 140 && r < 100 && g < 130 -> 2
            g > 130 && r < 100 && b < 100 -> 4
            r > 130 && b > 140 && g < 100 -> 5
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
        try {
            backgroundHandler?.removeCallbacks(analyzeRunnable)
            backgroundThread?.quitSafely() 
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
            
            reusableBitmap?.recycle() 
            reusableBitmap = null

            if (overlayView != null && windowManager != null) {
                windowManager?.removeView(overlayView)
            }
            if (controlView != null && windowManager != null) {
                windowManager?.removeView(controlView)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy", e)
        }
    }
}

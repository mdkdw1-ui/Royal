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
import java.util.LinkedList

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
    private var pixelArray: IntArray? = null
    
    private val gridHistory = LinkedList<Array<IntArray>>()
    private val MAX_HISTORY = 3

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
            val notification: Notification = NotificationCompat.Builder(this, "helper_channel")
                .setContentTitle("로얄매치 도우미 작동 중")
                .setContentText("고성능 HSV 기반 탐색 알고리즘 구동 중")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "onCreate failed", e)
            stopSelf()
        }
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
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager?.defaultDisplay?.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        // 💡 [해결 책] 중복 뷰 추가 에러 차단 가드문 설정
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
                Log.e(TAG, "overlayView add 실패 무시", e)
            }
        }
        
        if (controlView == null) {
            showControlOverlay()
        }

        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_OK) ?: Activity.RESULT_OK
        val dataIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("DATA_INTENT", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<Intent>("DATA_INTENT")
        }
        
        // 미디어 프로젝션 셋업은 권한 데이터가 들어오는 두 번째 호출 때 단 한 번만 실행되도록 격리
        if (dataIntent != null && mediaProjection == null) {
            try {
                val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mpManager.getMediaProjection(resultCode, dataIntent)
                
                backgroundThread = HandlerThread("ScreenCaptureThread").apply { start() }
                backgroundHandler = Handler(backgroundThread!!.looper)

                imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "ScreenCapture", screenWidth, screenHeight, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, backgroundHandler
                )
                
                pixelArray = IntArray(screenWidth * screenHeight)
                backgroundHandler?.post(analyzeRunnable)
                Log.d(TAG, "MediaProjection 활성화 및 분석 스레드 가동 성공")
            } catch (e: Exception) {
                Log.e(TAG, "MediaProjection 셋업 치명적 실패", e)
                stopSelf()
            }
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
                setPadding(15, 8, 15, 8)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#AA000000")) 
                    cornerRadius = 15f
                }
            }
            val statusText = TextView(themedContext).apply {
                text = "● HSV SMART RUN"
                setTextColor(Color.CYAN)
                textSize = 10f
                setPadding(0, 0, 15, 0)
            }
            val stopButton = Button(themedContext).apply {
                text = "STOP"
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#FF3B30")) 
                textSize = 9f
                setPadding(8, 4, 8, 4)
                setOnClickListener { stopSelf() }
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
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x = 0
                y = 30
            }
            windowManager?.addView(controlView, controlParams)
        } catch (e: Exception) {
            Log.e(TAG, "showControlOverlay failed", e)
        }
    }

    private val analyzeRunnable = object : Runnable {
        override fun run() {
            try { analyzeScreenFast() } catch (e: Exception) { Log.e(TAG, "analyze error", e) }
            backgroundHandler?.postDelayed(this, 500)
        }
    }

    private fun analyzeScreenFast() {
        val image = imageReader?.acquireLatestImage() ?: return
        val currentPixels = pixelArray ?: IntArray(screenWidth * screenHeight).also { pixelArray = it }
        
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

            // 스트라이드를 screenWidth로 맞춰 패딩 픽셀을 날리고 순수 알맹이만 고속 추출
            bitmap.getPixels(currentPixels, 0, screenWidth, 0, 0, screenWidth, screenHeight)

            val startY = (screenHeight * 0.30).toInt()
            val endY = (screenHeight * 0.82).toInt()
            val startX = (screenWidth * 0.05).toInt()
            val endX = (screenWidth * 0.95).toInt()
            val baseUnit = (screenWidth * 0.11).toInt()
            val minClusterGap = (baseUnit * 0.75).toInt()

            val rawXList = mutableListOf<Int>()
            val rawYList = mutableListOf<Int>()

            for (x in startX until endX step 5) {
                var validCount = 0
                for (y in startY until endY step 15) {
                    if (getHsvColor(currentPixels[y * screenWidth + x]) > 0) validCount++
                }
                if (validCount > 4) rawXList.add(x)
            }

            for (y in startY until endY step 5) {
                var validCount = 0
                for (x in startX until endX step 15) {
                    if (getHsvColor(currentPixels[y * screenWidth + x]) > 0) validCount++
                }
                if (validCount > 4) rawYList.add(y)
            }

            val xPeaks = processClusterPeaks(rawXList, minClusterGap)
            val yPeaks = processClusterPeaks(rawYList, minClusterGap)

            if (xPeaks.size < 3 || yPeaks.size < 3) {
                overlayView?.post { overlayView?.clearHint() }
                return
            }

            val dynamicCols = xPeaks.size
            val dynamicRows = yPeaks.size

            val colorGrid = Array(dynamicRows) { r ->
                IntArray(dynamicCols) { c ->
                    val px = xPeaks[c]
                    val py = yPeaks[r]
                    getHsvColor(currentPixels[py * screenWidth + px])
                }
            }

            addToGridHistory(colorGrid, dynamicRows, dynamicCols)
            val stableGrid = getVotedStableGrid(dynamicRows, dynamicCols)

            val hint = findFiveMatchPattern(stableGrid, dynamicRows, dynamicCols)
            
            if (hint != null) {
                val fx = xPeaks[hint.fromC].toFloat()
                val fy = yPeaks[hint.fromR].toFloat()
                val tx = xPeaks[hint.toC].toFloat()
                val ty = yPeaks[hint.toR].toFloat()
                
                overlayView?.post {
                    overlayView?.setHint(fx, fy, tx, ty, baseUnit.toFloat())
                }
            } else {
                overlayView?.post { overlayView?.clearHint() }
            }

        } catch (e: Throwable) { 
            Log.e(TAG, "analyze exception", e)
        } finally {
            try { image.close() } catch (e: Exception) {}
        }
    }

    private fun processClusterPeaks(rawPeaks: List<Int>, minGap: Int): List<Int> {
        if (rawPeaks.isEmpty()) return emptyList()
        val result = mutableListOf<Int>()
        var cluster = mutableListOf<Int>()
        
        cluster.add(rawPeaks[0])
        for (i in 1 until rawPeaks.size) {
            if (rawPeaks[i] - rawPeaks[i - 1] <= 15) {
                cluster.add(rawPeaks[i])
            } else {
                result.add(cluster.sum() / cluster.size)
                cluster = mutableListOf()
                cluster.add(rawPeaks[i])
            }
        }
        if (cluster.isNotEmpty()) result.add(cluster.sum() / cluster.size)

        val finalPeaks = mutableListOf<Int>()
        for (p in result) {
            if (finalPeaks.isEmpty() || p - finalPeaks.last() >= minGap) {
                finalPeaks.add(p)
            }
        }
        return finalPeaks
    }

    private fun getHsvColor(pixel: Int): Int {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        if (r + g + b < 85) return 0
        
        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)
        val hue = hsv[0]
        val sat = hsv[1]
        val value = hsv[2]

        if (value < 0.25f || sat < 0.20f) return 0 

        return when {
            hue < 16f || hue > 345f -> 1    // 빨강
            hue in 200f..255f -> 2          // 파랑
            hue in 44f..66f -> 3            // 노랑
            hue in 100f..155f -> 4          // 초록
            hue in 270f..315f -> 5          // 보라
            else -> 0
        }
    }

    private fun addToGridHistory(grid: Array<IntArray>, rows: Int, cols: Int) {
        gridHistory.add(Array(rows) { grid[it].copyOf() })
        if (gridHistory.size > MAX_HISTORY) gridHistory.removeFirst()
    }

    private fun getVotedStableGrid(rows: Int, cols: Int): Array<IntArray> {
        val stable = Array(rows) { IntArray(cols) }
        if (gridHistory.isEmpty()) return stable

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val voteMap = mutableMapOf<Int, Int>()
                for (frame in gridHistory) {
                    if (r < frame.size && c < frame[r].size) {
                        val color = frame[r][c]
                        voteMap[color] = (voteMap[color] ?: 0) + 1
                    }
                }
                stable[r][c] = voteMap.maxByOrNull { it.value }?.key ?: 0
            }
        }
        return stable
    }

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
            for (c in 0 until cols) {
                val color = grid[r][c]
                if (color == 0) continue
                
                if (c <= cols - 4) {
                    var matchCount = 1
                    var gapCount = 0
                    for (i in 1 until 6) {
                        if (c + i >= cols) break
                        val nextColor = grid[r][c + i]
                        if (nextColor == color) {
                            matchCount++
                        } else if (nextColor == 0 && gapCount < 2) { 
                            gapCount++
                        } else break
                    }
                    if (matchCount >= 5) return true
                }

                if (r <= rows - 4) {
                    var matchCount = 1
                    var gapCount = 0
                    for (i in 1 until 6) {
                        if (r + i >= rows) break
                        val nextColor = grid[r + i][c]
                        if (nextColor == color) {
                            matchCount++
                        } else if (nextColor == 0 && gapCount < 2) { 
                            gapCount++
                        } else break
                    }
                    if (matchCount >= 5) return true
                }
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
            pixelArray = null
            gridHistory.clear()

            if (overlayView != null) windowManager?.removeView(overlayView)
            if (controlView != null) windowManager?.removeView(controlView)
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy error", e)
        }
    }

    data class MatchHint(val fromR: Int, val fromC: Int, val toR: Int, val toC: Int)
}

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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
            val notification: Notification = NotificationCompat.Builder(this, "helper_channel")
                .setContentTitle("로얄매치 도우미 작동 중")
                .setContentText("백그라운드 분석 스레드가 가동 중입니다.")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "포그라운드 서비스 승격 실패", e)
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
            
            backgroundThread = HandlerThread("ScreenCaptureThread").apply { start() }
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
            Log.e(TAG, "미디어 프로젝션 연동 실패", e)
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            overlayView = PatternDrawView(this)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            windowManager?.addView(overlayView, params)
            showControlOverlay()
        } catch (e: Exception) {
            Log.e(TAG, "오버레이 생성 실패", e)
        }

        return START_NOT_STICKY
    }

    // 💡 [개선] 사용중(RUNNING) 표시창 크기를 최소화하고 상단으로 이동 시킴
    private fun showControlOverlay() {
        try {
            val themedContext = ContextThemeWrapper(this, androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
            controlView = LinearLayout(themedContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(15, 8, 15, 8) // 패딩 대폭 축소 (기존 30, 15)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#AA000000")) 
                    cornerRadius = 15f
                }
            }

            val statusText = TextView(themedContext).apply {
                text = "● RUNNING"
                setTextColor(Color.GREEN)
                textSize = 10f // 폰트 축소 (기존 12f)
                setPadding(0, 0, 12, 0)
            }

            val stopButton = Button(themedContext).apply {
                text = "STOP"
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#FF3B30")) 
                textSize = 9f // 버튼 폰트 축소 (기존 11f)
                setPadding(10, 5, 10, 5)
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
                gravity = Gravity.TOP or Gravity.END 
                x = 20
                y = 70 // 화면 최상단 상태바 밑으로 포지션 이동 (기존 150)
            }
            windowManager?.addView(controlView, controlParams)
        } catch (e: Exception) {
            Log.e(TAG, "컨트롤바 출력 실패", e)
        }
    }

    private val analyzeRunnable = object : Runnable {
        override fun run() {
            try { analyzeScreenFast() } catch (e: Exception) { Log.e(TAG, "분석 에러", e) }
            backgroundHandler?.postDelayed(this, 500)
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

            var minX = screenWidth; var maxX = 0; var minY = screenHeight; var maxY = 0
            var validPixelCount = 0

            for (y in (screenHeight * 0.30).toInt() until (screenHeight * 0.85).toInt() step 6) {
                for (x in (screenWidth * 0.05).toInt() until (screenWidth * 0.95).toInt() step 6) {
                    if (identifyColorSpec(bitmap.getPixel(x, y)) > 0) {
                        if (x < minX) minX = x; if (x > maxX) maxX = x
                        if (y < minY) minY = y; if (y > maxY) maxY = y
                        validPixelCount++
                    }
                }
            }

            if (validPixelCount < 40) {
                overlayView?.post { overlayView?.clearHint() }
                return
            }

            val padding = 15
            val sX = maxOf(0, minX - padding); val eX = minOf(screenWidth - 1, maxX + padding)
            val sY = maxOf(0, minY - padding); val eY = minOf(screenHeight - 1, maxY + padding)

            val xCount = IntArray(screenWidth)
            val yCount = IntArray(screenHeight)

            for (y in sY until eY step 3) {
                for (x in sX until eX step 3) {
                    if (identifyColorSpec(bitmap.getPixel(x, y)) > 0) {
                        xCount[x]++; yCount[y]++
                    }
                }
            }

            val xPeaks = mutableListOf<Int>()
            val yPeaks = mutableListOf<Int>()
            val windowSize = 15 

            for (x in sX + windowSize until eX - windowSize) {
                val v = xCount[x]
                if (v > 6) {
                    var isPeak = true
                    for (w in -windowSize..windowSize) { if (xCount[x + w] > v) { isPeak = false; break } }
                    if (isPeak && (xPeaks.isEmpty() || x - xPeaks.last() > 55)) xPeaks.add(x)
                }
            }

            for (y in sY + windowSize until eY - windowSize) {
                val v = yCount[y]
                if (v > 6) {
                    var isPeak = true
                    for (w in -windowSize..windowSize) { if (yCount[y + w] > v) { isPeak = false; break } }
                    if (isPeak && (yPeaks.isEmpty() || y - yPeaks.last() > 55)) yPeaks.add(y)
                }
            }

            if (xPeaks.isEmpty() || yPeaks.isEmpty()) return

            // 1차 임시 블록 사이즈 도출
            val diffs = mutableListOf<Int>()
            for (i in 0 until xPeaks.size - 1) { diffs.add(xPeaks[i+1] - xPeaks[i]) }
            for (i in 0 until yPeaks.size - 1) { diffs.add(yPeaks[i+1] - yPeaks[i]) }
            val baseBlockSize = if (diffs.isNotEmpty()) diffs.sorted()[diffs.size / 2] else (screenWidth * 0.105).toInt()

            // 💡 [개선] 노란색 황금 보드 테두리 선(Frame Line) 강제 여과 기능 추가
            val cleanXPeaks = mutableListOf<Int>()
            if (xPeaks.size >= 2 && (xPeaks[1] - xPeaks[0]) < baseBlockSize * 0.7) {
                cleanXPeaks.addAll(xPeaks.subList(1, xPeaks.size))
            } else { cleanXPeaks.addAll(xPeaks) }
            if (cleanXPeaks.size >= 2 && (cleanXPeaks.last() - cleanXPeaks[cleanXPeaks.size - 2]) < baseBlockSize * 0.7) {
                cleanXPeaks.removeAt(cleanXPeaks.size - 1)
            }

            val cleanYPeaks = mutableListOf<Int>()
            if (yPeaks.size >= 2 && (yPeaks[1] - yPeaks[0]) < baseBlockSize * 0.7) {
                cleanYPeaks.addAll(yPeaks.subList(1, yPeaks.size)) // 테두리 가짜 피크 탈락
            } else { cleanYPeaks.addAll(yPeaks) }
            if (cleanYPeaks.size >= 2 && (cleanYPeaks.last() - cleanYPeaks[cleanYPeaks.size - 2]) < baseBlockSize * 0.7) {
                cleanYPeaks.removeAt(cleanYPeaks.size - 1)
            }

            if (cleanXPeaks.isEmpty() || cleanYPeaks.isEmpty()) return

            // 격자 자석 스냅 필터링 재실행
            val validXPeaks = cleanXPeaks.filter { p ->
                cleanXPeaks.any { q -> p != q && Math.abs(Math.abs(p - q) - Math.round(Math.abs(p - q).toDouble() / baseBlockSize) * baseBlockSize) < (baseBlockSize * 0.18) }
            }
            val validYPeaks = cleanYPeaks.filter { p ->
                cleanYPeaks.any { q -> p != q && Math.abs(Math.abs(p - q) - Math.round(Math.abs(p - q).toDouble() / baseBlockSize) * baseBlockSize) < (baseBlockSize * 0.18) }
            }

            val finalXPeaks = if (validXPeaks.isNotEmpty()) validXPeaks else cleanXPeaks
            val finalYPeaks = if (validYPeaks.isNotEmpty()) validYPeaks else cleanYPeaks

            // 보드판 테두리가 완벽히 제거된 최종 리얼 오리진 좌표 산출
            val originX = finalXPeaks.minOrNull()!!
            val originY = finalYPeaks.minOrNull()!!
            
            val finalDiffs = mutableListOf<Int>()
            for (i in 0 until finalXPeaks.size - 1) { finalDiffs.add(finalXPeaks[i+1] - finalXPeaks[i]) }
            for (i in 0 until finalYPeaks.size - 1) { finalDiffs.add(finalYPeaks[i+1] - finalYPeaks[i]) }
            val blockSize = if (finalDiffs.isNotEmpty()) finalDiffs.sorted()[finalDiffs.size / 2] else baseBlockSize

            val maxColIdx = finalXPeaks.map { Math.round((it - originX).toDouble() / blockSize).toInt() }.maxOrNull() ?: 0
            val maxRowIdx = finalYPeaks.map { Math.round((it - originY).toDouble() / blockSize).toInt() }.maxOrNull() ?: 0
            
            val numCols = maxColIdx + 1
            val numRows = maxRowIdx + 1

            val colorGrid = Array(numRows) { IntArray(numCols) }
            val sampleOffset = (blockSize * 0.16).toInt()

            for (r in 0 until numRows) {
                for (c in 0 until numCols) {
                    val pixelX = originX + (c * blockSize)
                    val pixelY = originY + (r * blockSize)

                    if (pixelX >= sampleOffset && pixelX < bitmap.width - sampleOffset &&
                        pixelY >= sampleOffset && pixelY < bitmap.height - sampleOffset) {

                        val points = intArrayOf(
                            bitmap.getPixel(pixelX, pixelY),
                            bitmap.getPixel(pixelX - sampleOffset, pixelY),
                            bitmap.getPixel(pixelX + sampleOffset, pixelY),
                            bitmap.getPixel(pixelX, pixelY - sampleOffset),
                            bitmap.getPixel(pixelX, pixelY + sampleOffset)
                        )

                        val scoreMap = IntArray(6)
                        for (p in points) { scoreMap[identifyColorSpec(p)]++ }

                        var finalColor = 0; var maxCount = 0
                        for (i in 1..5) { if (scoreMap[i] > maxCount) { maxCount = scoreMap[i]; finalColor = i } }
                        colorGrid[r][c] = if (maxCount >= 2) finalColor else 0
                    }
                }
            }

            val hint = findBestMatchPattern(colorGrid, numRows, numCols)
            if (hint != null) {
                val fx = (originX + hint.fromC * blockSize).toFloat()
                val fy = (originY + hint.fromR * blockSize).toFloat()
                val tx = (originX + hint.toC * blockSize).toFloat()
                val ty = (originY + hint.toR * blockSize).toFloat()
                overlayView?.post { overlayView?.setHint(fx, fy, tx, ty, blockSize.toFloat()) }
            } else {
                overlayView?.post { overlayView?.clearHint() }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "analyzeScreenFast 실패", e)
        } finally {
            try { image.close() } catch (e: Exception) {}
        }
    }

    private fun identifyColorSpec(pixel: Int): Int {
        val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)
        if (r + g + b < 100) return 0 
        return when {
            r > 130 && r > g * 1.4 && r > b * 1.4 -> 1 // 빨간색 (책)
            b > 130 && b > r * 1.4 && b > g * 1.4 -> 2 // 파란색 (방패)
            r > 140 && g > 130 && b < r * 0.6 && Math.abs(r - g) < 40 -> 3 // 노란색 (왕관/매칭템)
            g > 110 && g > r * 1.4 && g > b * 1.4 -> 4 // 녹색 (잎새)
            r > 120 && b > 130 && g < r * 0.6 && g < b * 0.6 -> 5 // 보라색 (새)
            else -> 0
        }
    }

    data class MatchHint(val fromR: Int, val fromC: Int, val toR: Int, val toC: Int)

    private fun findBestMatchPattern(grid: Array<IntArray>, rows: Int, cols: Int): MatchHint? {
        val directions = arrayOf(Pair(0, 1), Pair(1, 0))
        
        // 5개 매칭(특수 블록 무조건 생성 조합)을 최우선 순위로 강제 탐색하도록 순서 고정
        for (targetSize in arrayOf(5, 4, 3)) {
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    if (grid[r][c] == 0) continue
                    for (dir in directions) {
                        val nr = r + dir.first; val nc = c + dir.second
                        if (nr < rows && nc < cols && grid[nr][nc] != 0) {
                            
                            val temp = grid[r][c]
                            grid[r][c] = grid[nr][nc]
                            grid[nr][nc] = temp
                            
                            val m1 = checkMatchAt(grid, r, c, rows, cols)
                            val m2 = checkMatchAt(grid, nr, nc, rows, cols)
                            
                            grid[nr][nc] = grid[r][c]
                            grid[r][c] = temp
                            
                            if (m1 >= targetSize || m2 >= targetSize) {
                                return MatchHint(r, c, nr, nc)
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun checkMatchAt(grid: Array<IntArray>, r: Int, c: Int, rows: Int, cols: Int): Int {
        val color = grid[r][c]
        if (color == 0) return 0
        
        var hCount = 1
        var cc = c - 1
        while (cc >= 0 && grid[r][cc] == color) { hCount++; cc-- }
        cc = c + 1
        while (cc < cols && grid[r][cc] == color) { hCount++; cc++ }
        
        var vCount = 1
        var rr = r - 1
        while (rr >= 0 && grid[rr][c] == color) { vCount++; rr-- }
        rr = r + 1
        while (rr < rows && grid[rr][c] == color) { vCount++; rr++ }
        
        return maxOf(hCount, vCount)
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
            if (overlayView != null) windowManager?.removeView(overlayView)
            if (controlView != null) windowManager?.removeView(controlView)
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy 에러", e)
        }
    }
}

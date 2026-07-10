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
                .setContentText("정밀 5인라인 스캔 모드 가동 중")
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

    private fun showControlOverlay() {
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
                text = "● 5-LINE MATCH"
                setTextColor(Color.YELLOW)
                textSize = 10f 
                setPadding(0, 0, 12, 0)
            }

            val stopButton = Button(themedContext).apply {
                text = "STOP"
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#FF3B30")) 
                textSize = 9f 
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
                y = 70 
            }
            windowManager?.addView(controlView, controlParams)
        } catch (e: Exception) {
            Log.e(TAG, "컨트롤바 출력 실패", e)
        }
    }

    private val analyzeRunnable = object : Runnable {
        override fun run() {
            try { analyzeScreenFast() } catch (e: Exception) { Log.e(TAG, "분석 에러", e) }
            backgroundHandler?.postDelayed(this, 350)
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

            val baseBlockSize = (screenWidth * 0.105).toInt()
            val xPeaks = mutableListOf<Int>()
            val yPeaks = mutableListOf<Int>()
            
            // 💡 [개선] 경계선 오류를 타파하는 세그먼트 스캔 방식 (블록 중심점 정밀 포착)
            var inPeak = false
            var peakStart = 0
            for (x in sX until eX) {
                if (xCount[x] > 4) {
                    if (!inPeak) { peakStart = x; inPeak = true }
                } else {
                    if (inPeak) {
                        val center = (peakStart + x - 1) / 2
                        if (xPeaks.isEmpty() || center - xPeaks.last() > baseBlockSize * 0.75) xPeaks.add(center)
                        inPeak = false
                    }
                }
            }

            inPeak = false
            for (y in sY until eY) {
                if (yCount[y] > 4) {
                    if (!inPeak) { peakStart = y; inPeak = true }
                } else {
                    if (inPeak) {
                        val center = (peakStart + y - 1) / 2
                        if (yPeaks.isEmpty() || center - yPeaks.last() > baseBlockSize * 0.75) yPeaks.add(center)
                        inPeak = false
                    }
                }
            }

            if (xPeaks.isEmpty() || yPeaks.isEmpty()) return

            val originX = xPeaks.minOrNull()!!
            val originY = yPeaks.minOrNull()!!
            
            val finalDiffs = mutableListOf<Int>()
            for (i in 0 until xPeaks.size - 1) { finalDiffs.add(xPeaks[i+1] - xPeaks[i]) }
            for (i in 0 until yPeaks.size - 1) { finalDiffs.add(yPeaks[i+1] - yPeaks[i]) }
            val blockSize = if (finalDiffs.isNotEmpty()) finalDiffs.sorted()[finalDiffs.size / 2] else baseBlockSize

            val maxColIdx = xPeaks.map { Math.round((it - originX).toDouble() / blockSize).toInt() }.maxOrNull() ?: 0
            val maxRowIdx = yPeaks.map { Math.round((it - originY).toDouble() / blockSize).toInt() }.maxOrNull() ?: 0
            
            val numCols = maxColIdx + 1
            val numRows = maxRowIdx + 1

            val colToX = IntArray(numCols) { originX + (it * blockSize) }
            for (p in xPeaks) {
                val c = Math.round((p - originX).toDouble() / blockSize).toInt()
                if (c in 0 until numCols) colToX[c] = p
            }

            val rowToY = IntArray(numRows) { originY + (it * blockSize) }
            for (p in yPeaks) {
                val r = Math.round((p - originY).toDouble() / blockSize).toInt()
                if (r in 0 until numRows) rowToY[r] = p
            }

            val colorGrid = Array(numRows) { IntArray(numCols) }
            val offsetShort = (blockSize * 0.15).toInt()
            val offsetLong = (blockSize * 0.28).toInt()

            for (r in 0 until numRows) {
                for (c in 0 until numCols) {
                    val pixelX = colToX[c]
                    val pixelY = rowToY[r]

                    if (pixelX >= offsetLong && pixelX < bitmap.width - offsetLong &&
                        pixelY >= offsetLong && pixelY < bitmap.height - offsetLong) {

                        val points = intArrayOf(
                            bitmap.getPixel(pixelX, pixelY),
                            bitmap.getPixel(pixelX - offsetShort, pixelY),
                            bitmap.getPixel(pixelX + offsetShort, pixelY),
                            bitmap.getPixel(pixelX, pixelY - offsetShort),
                            bitmap.getPixel(pixelX, pixelY + offsetShort),
                            bitmap.getPixel(pixelX - offsetLong, pixelY - offsetLong),
                            bitmap.getPixel(pixelX + offsetLong, pixelY - offsetLong),
                            bitmap.getPixel(pixelX - offsetLong, pixelY + offsetLong),
                            bitmap.getPixel(pixelX + offsetLong, pixelY + offsetLong)
                        )

                        val scoreMap = IntArray(6)
                        for (p in points) { scoreMap[identifyColorSpec(p)]++ }

                        var finalColor = 0; var maxCount = 0
                        for (i in 1..5) { if (scoreMap[i] > maxCount) { maxCount = scoreMap[i]; finalColor = i } }
                        
                        colorGrid[r][c] = if (maxCount >= 2) finalColor else identifyColorSpec(bitmap.getPixel(pixelX, pixelY))
                    }
                }
            }

            val hint = findBestMatchPattern(colorGrid, numRows, numCols)
            if (hint != null) {
                val fx = colToX[hint.fromC].toFloat()
                val fy = rowToY[hint.fromR].toFloat()
                val tx = colToX[hint.toC].toFloat()
                val ty = rowToY[hint.toR].toFloat()
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
            r > 115 && r > g * 1.3 && r > b * 1.3 -> 1 
            b > 115 && b > r * 1.2 && b > g * 1.2 -> 2 
            r > 125 && g > 115 && b < r * 0.75 -> 3    
            g > 100 && g > r * 1.3 && g > b * 1.3 -> 4 
            r > 100 && b > 115 && g < r * 0.7 && g < b * 0.7 -> 5 
            else -> 0
        }
    }

    data class MatchHint(val fromR: Int, val fromC: Int, val toR: Int, val toC: Int)

    private fun findBestMatchPattern(grid: Array<IntArray>, rows: Int, cols: Int): MatchHint? {
        val directions = arrayOf(Pair(0, 1), Pair(1, 0))
        
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                for (dir in directions) {
                    val nr = r + dir.first; val nc = c + dir.second
                    if (nr < rows && nc < cols) {
                        if (grid[r][c] == 0 && grid[nr][nc] == 0) continue
                        
                        val temp = grid[r][c]
                        grid[r][c] = grid[nr][nc]
                        grid[nr][nc] = temp
                        
                        val is5InRow1 = checkStrict5InRow(grid, r, c, rows, cols)
                        val is5InRow2 = checkStrict5InRow(grid, nr, nc, rows, cols)
                        
                        grid[nr][nc] = grid[r][c]
                        grid[r][c] = temp
                        
                        if (is5InRow1 || is5InRow2) {
                            return MatchHint(r, c, nr, nc)
                        }
                    }
                }
            }
        }
        return null
    }

    // 💡 [개선] 이펙트/문양 간섭으로 인한 미인식 공백(0)을 1개까지 유연하게 메워 5매칭 성공률 극대화
    private fun checkStrict5InRow(grid: Array<IntArray>, r: Int, c: Int, rows: Int, cols: Int): Boolean {
        val color = grid[r][c]
        if (color == 0) return false
        
        // 가로 스캔 (미인식 보정 적용)
        var hCount = 1
        var hZeros = 0
        var cc = c - 1
        while (cc >= 0) {
            if (grid[r][cc] == color) { hCount++ }
            else if (grid[r][cc] == 0 && hZeros == 0) {
                if (cc - 1 >= 0 && grid[r][cc - 1] == color) { hCount++; hZeros++ } else break
            } else break
            cc--
        }
        cc = c + 1
        while (cc < cols) {
            if (grid[r][cc] == color) { hCount++ }
            else if (grid[r][cc] == 0 && hZeros == 0) {
                if (cc + 1 < cols && grid[r][cc + 1] == color) { hCount++; hZeros++ } else break
            } else break
            cc++
        }
        if (hCount >= 5) return true

        // 세로 스캔 (미인식 보정 적용)
        var vCount = 1
        var vZeros = 0
        var rr = r - 1
        while (rr >= 0) {
            if (grid[rr][c] == color) { vCount++ }
            else if (grid[rr][c] == 0 && vZeros == 0) {
                if (rr - 1 >= 0 && grid[rr - 1][c] == color) { vCount++; vZeros++ } else break
            } else break
            rr--
        }
        rr = r + 1
        while (rr < rows) {
            if (grid[rr][c] == color) { vCount++ }
            else if (grid[rr][c] == 0 && vZeros == 0) {
                if (rr + 1 < rows && grid[rr + 1][c] == color) { vCount++; vZeros++ } else break
            } else break
            rr++
        }
        return vCount >= 5
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

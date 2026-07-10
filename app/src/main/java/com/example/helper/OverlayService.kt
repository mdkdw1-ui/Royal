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
import kotlin.math.abs
import kotlin.math.roundToInt

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
                .setContentText("가변 동적 그리드 매칭 모드 가동 중")
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
                text = "● DYNAMIC MATCH"
                setTextColor(Color.CYAN)
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
            backgroundHandler?.postDelayed(this, 300)
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

            // 유효 영역 탐색 분할 스캔
            val startY = (screenHeight * 0.30).toInt()
            val endY = (screenHeight * 0.85).toInt()
            val startX = (screenWidth * 0.05).toInt()
            val endX = (screenWidth * 0.95).toInt()

            for (y in startY until endY step 6) {
                for (x in startX until endX step 6) {
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

            val padding = 10
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

            // 💡 [개선 핵심] 동적 픽셀 중심축 탐색 알고리즘 (가변 판 구조 대응)
            val xPeaks = mutableListOf<Int>()
            val yPeaks = mutableListOf<Int>()
            val approxBlockSize = (screenWidth * 0.11).toInt()

            var inPeak = false
            var peakStart = 0
            for (x in sX until eX) {
                if (xCount[x] > 4) {
                    if (!inPeak) { peakStart = x; inPeak = true }
                } else {
                    if (inPeak) {
                        val center = (peakStart + x - 1) / 2
                        if (xPeaks.isEmpty() || center - xPeaks.last() > approxBlockSize * 0.7) xPeaks.add(center)
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
                        if (yPeaks.isEmpty() || center - yPeaks.last() > approxBlockSize * 0.7) yPeaks.add(center)
                        inPeak = false
                    }
                }
            }

            if (xPeaks.isEmpty() || yPeaks.isEmpty()) return

            // 중심 축들 간의 중간 격자 간격(blockSize) 동적 계산
            val gaps = mutableListOf<Int>()
            for (i in 0 until xPeaks.size - 1) gaps.add(xPeaks[i+1] - xPeaks[i])
            for (i in 0 until yPeaks.size - 1) gaps.add(yPeaks[i+1] - yPeaks[i])
            val blockSize = if (gaps.isNotEmpty()) gaps.sorted()[gaps.size / 2] else approxBlockSize

            val originX = xPeaks.minOrNull()!!
            val originY = yPeaks.minOrNull()!!

            // 유동적인 가로/세로 전체 인덱스 칸 수 계산
            val numCols = (((maxX - originX).toDouble() / blockSize).roundToInt() + 1).coerceAtLeast(1)
            val numRows = (((maxY - originY).toDouble() / blockSize).roundToInt() + 1).coerceAtLeast(1)

            // 가변 유동 그리드 생성 및 실제 물리 중심점 맵핑
            val colToX = IntArray(numCols) { originX + (it * blockSize) }
            val rowToY = IntArray(numRows) { originY + (it * blockSize) }

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
            r > 120 && r > g * 1.3 && r > b * 1.3 -> 1 // 빨강
            b > 120 && b > r * 1.2 && b > g * 1.2 -> 2 // 파랑
            r > 130 && g > 115 && b < r * 0.75 -> 3    // 노랑
            g > 105 && g > r * 1.3 && g > b * 1.3 -> 4 // 초록
            r > 105 && b > 120 && g < r * 0.7 && g < b * 0.7 -> 5 // 보라
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

    private fun checkStrict5InRow(grid: Array<IntArray>, r: Int, c: Int, rows: Int, cols: Int): Boolean {
        val color = grid[r][c]
        if (color == 0) return false
        
        // 가로 탐색 (문양/이펙트 공백 1칸 유연 보정)
        var hCount = 1
        var hGap = 0
        var cc = c - 1
        while (cc >= 0) {
            if (grid[r][cc] == color) { hCount++ }
            else if (grid[r][cc] == 0 && hGap == 0) {
                if (cc - 1 >= 0 && grid[r][cc - 1] == color) { hCount++; hGap++ } else break
            } else break
            cc--
        }
        cc = c + 1
        while (cc < cols) {
            if (grid[r][cc] == color) { hCount++ }
            else if (grid[r][cc] == 0 && hGap == 0) {
                if (cc + 1 < cols && grid[r][cc + 1] == color) { hCount++; hGap++ } else break
            } else break
            cc++
        }
        if (hCount >= 5) return true

        // 세로 탐색 (문양/이펙트 공백 1칸 유연 보정)
        var vCount = 1
        var vGap = 0
        var rr = r - 1
        while (rr >= 0) {
            if (grid[rr][c] == color) { vCount++ }
            else if (grid[rr][c] == 0 && vGap == 0) {
                if (rr - 1 >= 0 && grid[rr - 1][c] == color) { vCount++; vGap++ } else break
            } else break
            rr--
        }
        rr = r + 1
        while (rr < rows) {
            if (grid[rr][c] == color) { vCount++ }
            else if (grid[rr][c] == 0 && vGap == 0) {
                if (rr + 1 < rows && grid[rr + 1][c] == color) { vCount++; vGap++ } else break
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

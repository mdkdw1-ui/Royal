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
                .setContentText("클러스터 정밀 5인라인 분석 중")
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
                text = "● TARGET 5-COMB"
                setTextColor(Color.parseColor("#FFD700"))
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

            val baseBlockSize = (screenWidth * 0.11).toInt()
            val minGap = (baseBlockSize * 0.75).toInt()

            // 💡 [개선 핵심] 1단계: 선 단위 누적이 아닌, 낱개 블록의 물리 독립 좌표(Centroid) 추출
            val rawXPeaks = mutableListOf<Int>()
            val rawYPeaks = mutableListOf<Int>()

            val startY = (screenHeight * 0.32).toInt()
            val endY = (screenHeight * 0.82).toInt()
            val startX = (screenWidth * 0.06).toInt()
            val endX = (screenWidth * 0.94).toInt()

            // 가로축 대표 샘플링 스캔
            for (x in startX until endX step 4) {
                var matchCount = 0
                for (y in startY until endY step 15) {
                    if (identifyColorSpec(bitmap.getPixel(x, y)) > 0) matchCount++
                }
                if (matchCount > 4) rawXPeaks.add(x)
            }

            // 세로축 대표 샘플링 스캔
            for (y in startY until endY step 4) {
                var matchCount = 0
                for (x in startX until endX step 15) {
                    if (identifyColorSpec(bitmap.getPixel(x, y)) > 0) matchCount++
                }
                if (matchCount > 4) rawYPeaks.add(y)
            }

            // 근접한 좌표 집합들을 그룹화하여 정중앙 축 1개로 압축 (클러스터 필터링)
            val xPeaks = clusterPeaks(rawXPeaks, minGap)
            val yPeaks = clusterPeaks(rawYPeaks, minGap)

            if (xPeaks.size < 3 || yPeaks.size < 3) {
                overlayView?.post { overlayView?.clearHint() }
                return
            }

            val numCols = xPeaks.size
            val numRows = yPeaks.size

            // 💡 [개선 핵심] 2단계: 유동적으로 쪼개진 비대칭 격자에 정확히 동적 인덱스 부여
            val colorGrid = Array(numRows) { IntArray(numCols) }
            val sampleRadius = (baseBlockSize * 0.15).toInt()

            for (r in 0 until numRows) {
                for (c in 0 until numCols) {
                    val pixelX = xPeaks[c]
                    val pixelY = yPeaks[r]

                    if (pixelX in sampleRadius until bitmap.width - sampleRadius &&
                        pixelY in sampleRadius until bitmap.height - sampleRadius) {

                        // 블록 중심점 내부 5개 스팟 정밀 멀티 서칭
                        val points = intArrayOf(
                            bitmap.getPixel(pixelX, pixelY),
                            bitmap.getPixel(pixelX - sampleRadius, pixelY),
                            bitmap.getPixel(pixelX + sampleRadius, pixelY),
                            bitmap.getPixel(pixelX, pixelY - sampleRadius),
                            bitmap.getPixel(pixelX, pixelY + sampleRadius)
                        )

                        val scoreMap = IntArray(6)
                        for (p in points) { scoreMap[identifyColorSpec(p)]++ }

                        var finalColor = 0; var maxCount = 0
                        for (i in 1..5) { if (scoreMap[i] > maxCount) { maxCount = scoreMap[i]; finalColor = i } }
                        
                        // 확실한 매칭 결과가 없으면 빈 공간(0) 취급
                        colorGrid[r][c] = if (maxCount >= 2) finalColor else 0
                    }
                }
            }

            // 3단계: 가변 배열 패턴 분석 및 최적화된 힌트 출력
            val hint = findBestMatchPattern(colorGrid, numRows, numCols)
            if (hint != null) {
                val fx = xPeaks[hint.fromC].toFloat()
                val fy = yPeaks[hint.fromR].toFloat()
                val tx = xPeaks[hint.toC].toFloat()
                val ty = yPeaks[hint.toR].toFloat()
                overlayView?.post { overlayView?.setHint(fx, fy, tx, ty, baseBlockSize.toFloat()) }
            } else {
                overlayView?.post { overlayView?.clearHint() }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "analyzeScreenFast 실패", e)
        } finally {
            try { image.close() } catch (e: Exception) {}
        }
    }

    // 인접 픽셀 좌표 스크럼을 짜서 정중앙 센터값 하나만 추출하는 헬퍼 함수
    private fun clusterPeaks(peaks: List<Int>, minGap: Int): List<Int> {
        if (peaks.isEmpty()) return emptyList()
        val clustered = mutableListOf<Int>()
        var currentCluster = mutableListOf<Int>()
        
        currentCluster.add(peaks[0])
        for (i in 1 until peaks.size) {
            if (peaks[i] - peaks[i - 1] <= 12) { // 연속된 선 픽셀들 묶기
                currentCluster.add(peaks[i])
            } else {
                clustered.add(currentCluster.sum() / currentCluster.size)
                currentCluster = mutableListOf()
                currentCluster.add(peaks[i])
            }
        }
        if (currentCluster.isNotEmpty()) {
            clustered.add(currentCluster.sum() / currentCluster.size)
        }

        // 너무 촘촘하게 붙은 가짜 피크(노이즈) 2차 제거
        val finalPeaks = mutableListOf<Int>()
        for (p in clustered) {
            if (finalPeaks.isEmpty() || p - finalPeaks.last() >= minGap) {
                finalPeaks.add(p)
            }
        }
        return finalPeaks
    }

    private fun identifyColorSpec(pixel: Int): Int {
        val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)
        if (r + g + b < 90) return 0 
        return when {
            r > 125 && r > g * 1.35 && r > b * 1.35 -> 1 // 빨강 (타겟)
            b > 125 && b > r * 1.25 && b > g * 1.25 -> 2 // 파랑
            r > 135 && g > 120 && b < r * 0.70 -> 3    // 노랑
            g > 110 && g > r * 1.35 && g > b * 1.35 -> 4 // 초록
            r > 110 && b > 125 && g < r * 0.65 && g < b * 0.65 -> 5 // 보라
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
                        // 둘 다 공백 칸(격자 외부 혹은 특수 장애물)이면 연산 스킵
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

    // 💡 [개선 핵심] 가변 빈 공간(0) 예외 차단형 5개 스트레이트 매칭 판정 알고리즘
    private fun checkStrict5InRow(grid: Array<IntArray>, r: Int, c: Int, rows: Int, cols: Int): Boolean {
        val color = grid[r][c]
        if (color == 0) return false
        
        // 가로 연속성 체크 (문양 간섭으로 인한 누락은 1칸만 보정 허용)
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

        // 세로 연속성 체크
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

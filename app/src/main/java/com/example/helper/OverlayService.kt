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
    
    // 억까 방지용 프레임 투표 이력 관리
    private val gridHistory = LinkedList<Array<IntArray>>()
    private val MAX_HISTORY = 3

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "OverlayService onCreate 호출됨")
        try {
            createNotificationChannel()
            val notification: Notification = NotificationCompat.Builder(this, "helper_channel")
                .setContentTitle("로얄매치 도우미 작동 중")
                .setContentText("고성능 HSV 기반 탐색 알고리즘 구동 중")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(1, notification)
            }
            Log.d(TAG, "Foreground 서비스 승격 성공")
        } catch (e: Exception) {
            Log.e(TAG, "onCreate 단계에서 포그라운드 승격 실패", e)
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
        Log.d(TAG, "OverlayService onStartCommand 호출됨")
        
        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_OK) ?: Activity.RESULT_OK
        val dataIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("DATA_INTENT", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<Intent>("DATA_INTENT")
        }
        
        if (dataIntent == null) {
            Log.e(TAG, "dataIntent가 누락되어 서비스를 종료합니다.")
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
            Log.d(TAG, "mediaProjection 객체 획득 성공")
            
            backgroundThread = HandlerThread("ScreenCaptureThread").apply { start() }
            backgroundHandler = Handler(backgroundThread!!.looper)

            // [안드로이드 14 대응] 필수 콜백 등록
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    Log.d(TAG, "MediaProjection이 시스템에 의해 중지되었습니다.")
                    stopSelf()
                }
            }, backgroundHandler)
            Log.d(TAG, "MediaProjection 필수 콜백 등록 완료")

            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture", screenWidth, screenHeight, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, backgroundHandler
            )
            Log.d(TAG, "VirtualDisplay 가상 디스플레이 연결 완벽 성공!")
            
            // 고속 추출용 픽셀 배열 미리 할당
            pixelArray = IntArray(screenWidth * screenHeight)
            backgroundHandler?.post(analyzeRunnable)

            // 💡 [핵심] 성공 신호를 MainActivity로 보내 백그라운드로 안전하게 내림
            val finishIntent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("ACTION_FINISH", true)
            }
            startActivity(finishIntent)

        } catch (e: Exception) {
            Log.e(TAG, "미디어 프로젝션 연동 실패!!", e)
            stopSelf()
            return START_NOT_STICKY
        }

        // 오버레이 뷰 구성 (중복 생성 방지 가드 포함)
        try {
            if (overlayView == null) {
                overlayView = PatternDrawView(this)
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                )
                windowManager?.addView(overlayView, params)
            }
            if (controlView == null) {
                showControlOverlay()
            }
        } catch (e: Exception) {
            Log.e(TAG, "오버레이 그리기 실패", e)
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
            Log.e(TAG, "컨트롤바 띄우기 실패", e)
        }
    }

    private val analyzeRunnable = object : Runnable {
        override fun run() {
            try { analyzeScreenFast() } catch (e: Exception) { Log.e(TAG, "분석 에러", e) }
            backgroundHandler?.postDelayed(this, 500) // 0.5초 간격 정밀 분석
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

            // 스트라이드 패딩 제거 후 순수 알맹이 영역만 고속 추출
            bitmap.getPixels(currentPixels, 0, screenWidth, 0, 0, screenWidth, screenHeight)

            // 스캔 최적화 범위 설정
            val startY = (screenHeight * 0.30).toInt()
            val endY = (screenHeight * 0.82).toInt()
            val startX = (screenWidth * 0.05).toInt()
            val endX = (screenWidth * 0.95).toInt()
            val baseUnit = (screenWidth * 0.11).toInt()
            val minClusterGap = (baseUnit * 0.75).toInt()

            val rawXList = mutableListOf<Int>()
            val rawYList = mutableListOf<Int>()

            // 1단계: 수직/수평 스캔을 통한 유효 색상 밀집축 탐색
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

            // 2단계: 피크 클러스터링을 통한 가변 그리드 중심선 산출
            val xPeaks = processClusterPeaks(rawXList, minClusterGap)
            val yPeaks = processClusterPeaks(rawYList, minClusterGap)

            if (xPeaks.size < 3 || yPeaks.size < 3) {
                overlayView?.post { overlayView?.clearHint() }
                return
            }

            val dynamicCols = xPeaks.size
            val dynamicRows = yPeaks.size

            // 3단계: 동적 매트릭스 생성 및 HSV 색상 매핑
            val colorGrid = Array(dynamicRows) { r ->
                IntArray(dynamicCols) { c ->
                    val px = xPeaks[c]
                    val py = yPeaks[r]
                    getHsvColor(currentPixels[py * screenWidth + px])
                }
            }

            // 4단계: 다수결 보정을 통한 화면 깜빡임/이펙트 억까 제어
            addToGridHistory(colorGrid, dynamicRows, dynamicCols)
            val stableGrid = getVotedStableGrid(dynamicRows, dynamicCols)

            // 5단계: 5매칭 패턴 연산 수행
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
            Log.e(TAG, "analyzeScreenFast 알고리즘 예외 발생", e)
        } calendar {
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
            Log.d(TAG, "OverlayService 자원 해제 및 종료 완료")
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy 실패", e)
        }
    }

    data class MatchHint(val fromR: Int, val fromC: Int, val toR: Int, val toC: Int)
}

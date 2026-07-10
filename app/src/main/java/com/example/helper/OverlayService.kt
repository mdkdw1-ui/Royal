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
        try {
            createNotificationChannel()
            val notification: Notification = NotificationCompat.Builder(this, "helper_channel")
                .setContentTitle("로얄매치 도우미 작동 중")
                .setContentText("백그라운드 스레드에서 매칭 패턴을 분석 중입니다.")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(1, notification)
            }
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

        // 오버레이 뷰 표출
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
                x = 40
                y = 150 
            }
            windowManager?.addView(controlView, controlParams)
        } catch (e: Exception) {
            Log.e(TAG, "컨트롤바 띄우기 실패", e)
        }
    }

    private val analyzeRunnable = object : Runnable {
        override fun run() {
            try { analyzeScreenFast() } catch (e: Exception) { Log.e(TAG, "분석 에러", e) }
            backgroundHandler?.postDelayed(this, 600) // 분석 주기를 0.6초로 조금 더 기민하게 변경
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

            // 변형 격자 맵 대응을 위한 보정용 감지 패널 범위 설정
            var boardTop = (screenHeight * 0.28).toInt()
            var boardBottom = (screenHeight * 0.82).toInt()
            var boardLeft = (screenWidth * 0.03).toInt()
            var boardRight = (screenWidth * 0.97).toInt()

            val boardWidth = boardRight - boardLeft
            val blockSize = boardWidth / GRID_COLS
            val colorGrid = Array(GRID_ROWS) { IntArray(GRID_COLS) }

            // 💡 [개선] 십자형 다중 포인트 샘플링을 통한 오탐 방지 및 격자 틀어짐 방어
            val sampleOffset = (blockSize * 0.15).toInt() 

            for (r in 0 until GRID_ROWS) {
                for (c in 0 until GRID_COLS) {
                    val pixelX = boardLeft + (c * blockSize) + (blockSize / 2)
                    val pixelY = boardTop + (r * blockSize) + (blockSize / 2)
                    
                    if (pixelX >= sampleOffset && pixelX < bitmap.width - sampleOffset &&
                        pixelY >= sampleOffset && pixelY < bitmap.height - sampleOffset) {
                        
                        // 중심점 및 상하좌우 4포인트 추가 대조
                        val points = intArrayOf(
                            bitmap.getPixel(pixelX, pixelY),
                            bitmap.getPixel(pixelX - sampleOffset, pixelY),
                            bitmap.getPixel(pixelX + sampleOffset, pixelY),
                            bitmap.getPixel(pixelX, pixelY - sampleOffset),
                            bitmap.getPixel(pixelX, pixelY + sampleOffset)
                        )

                        val scoreMap = IntArray(6)
                        for (p in points) {
                            scoreMap[identifyColorSpec(p)]++
                        }

                        // 빈 공간(0)을 제외하고 가장 많이 검출된 색상 스펙을 최종 선택
                        var finalColor = 0
                        var maxCount = 0
                        for (i in 1..5) {
                            if (scoreMap[i] > maxCount) {
                                maxCount = scoreMap[i]
                                finalColor = i
                            }
                        }
                        colorGrid[r][c] = if (maxCount >= 2) finalColor else 0
                    }
                }
            }

            // 💡 [핵심 개선] 5매칭 -> 4매칭 -> 3매칭 순으로 유연하게 서칭하도록 로직 전환
            val hint = findBestMatchPattern(colorGrid, GRID_ROWS, GRID_COLS)
            if (hint != null) {
                val fx = boardLeft + (hint.fromC * blockSize) + (blockSize / 2).toFloat()
                val fy = boardTop + (hint.fromR * blockSize) + (blockSize / 2).toFloat()
                val tx = boardLeft + (hint.toC * blockSize) + (blockSize / 2).toFloat()
                val ty = boardTop + (hint.toR * blockSize) + (blockSize / 2).toFloat()
                overlayView?.post { overlayView?.setHint(fx, fy, tx, ty, blockSize.toFloat()) }
            } else {
                overlayView?.post { overlayView?.clearHint() }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "analyzeScreenFast 실패", e)
        } finaly {
            try { image.close() } catch (e: Exception) {}
        }
    }

    // 로얄매치 주요 블록 색상 범위 정밀 고도화
    private fun identifyColorSpec(pixel: Int): Int {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        
        if (r + g + b < 90) return 0 
        return when {
            r > 145 && g > 135 && b < 100 -> 3 // 노란색 (왕관)
            r > 150 && g < 95 && b < 95   -> 1 // 빨간색 (책)
            b > 145 && r < 105 && g < 135 -> 2 // 파란색 (방패)
            g > 115 && r < 125 && b < 125 -> 4 // 녹색 (나뭇잎 - 유연하게 범위 확장)
            r > 125 && b > 140 && g < 105 -> 5 // 보라색 (새)
            else -> 0
        }
    }

    data class MatchHint(val fromR: Int, val fromC: Int, val toR: Int, val toC: Int)

    // 💡 [알고리즘 업그레이드] 다중 타겟 매칭 서처
    private fun findBestMatchPattern(grid: Array<IntArray>, rows: Int, cols: Int): MatchHint? {
        val directions = arrayOf(Pair(0, 1), Pair(1, 0))
        
        // 5매칭 우선 탐색 -> 없으면 4매칭 -> 없으면 3매칭 순으로 역순 스캔 실행
        for (targetSize in arrayOf(5, 4, 3)) {
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    if (grid[r][c] == 0) continue
                    for (dir in directions) {
                        val nr = r + dir.first
                        val nc = c + dir.second
                        if (nr < rows && nc < cols && grid[nr][nc] != 0) {
                            // 임시 스와이프 변경
                            val temp = grid[r][c]
                            grid[r][c] = grid[nr][nc]
                            grid[nr][nc] = temp
                            
                            if (checkGridMatchSize(grid, rows, cols, targetSize)) {
                                return MatchHint(r, c, nr, nc)
                            }
                            // 원복
                            grid[nr][nc] = grid[r][c]
                            grid[r][c] = temp
                        }
                    }
                }
            }
        }
        return null
    }

    private fun checkGridMatchSize(grid: Array<IntArray>, rows: Int, cols: Int, size: Int): Boolean {
        // 가로축 검사
        for (r in 0 until rows) {
            for (c in 0..cols - size) {
                val color = grid[r][c]
                if (color == 0) continue
                var isMatch = true
                for (i in 1 until size) {
                    if (grid[r][c + i] != color) { isMatch = false; break }
                }
                if (isMatch) return true
            }
        }
        // 세로축 검사
        for (c in 0 until cols) {
            for (r in 0..rows - size) {
                val color = grid[r][c]
                if (color == 0) continue
                var isMatch = true
                for (i in 1 until size) {
                    if (grid[r + i][c] != color) { isMatch = false; break }
                }
                if (isMatch) return true
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
            if (overlayView != null) windowManager?.removeView(overlayView)
            if (controlView != null) windowManager?.removeView(controlView)
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy 에러", e)
        }
    }
}

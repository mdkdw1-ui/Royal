package com.example.helper

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat

class OverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var overlayView: PatternDrawView? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    // 💡 코파일럿의 핵심 아이디어: 전용 백그라운드 스레드 도입
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    
    private var screenWidth = 1080
    private var screenHeight = 2400

    // 로얄매치 퍼즐판 격자 기준 (기본 8x8 설정)
    private val GRID_COLS = 8
    private val GRID_ROWS = 8

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification: Notification = NotificationCompat.Builder(this, "helper_channel")
            .setContentTitle("로얄매치 도우미 작동 중")
            .setContentText("백그라운드 스레드에서 초고속으로 매칭 패턴을 분석 중입니다.")
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

        // 오버레이 뷰(화면에 빨간 선 그릴 뷰) 생성 및 윈도우 추가
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
            
            // 💡 1. 백그라운드 루프를 돌릴 스레드 기동
            backgroundThread = HandlerThread("ScreenCaptureThread").apply { start() }
            backgroundHandler = Handler(backgroundThread!!.looper)

            // 💡 2. ImageReader 연산 처리를 메인이 아닌 백그라운드 핸들러로 지정
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture", screenWidth, screenHeight, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, backgroundHandler
            )
            
            // 💡 3. 분석 루프 시작 (0.8초 주기 제어)
            backgroundHandler?.post(analyzeRunnable)
        }
        return START_NOT_STICKY
    }

    private val analyzeRunnable = object : Runnable {
        override fun run() {
            analyzeScreenFast()
            backgroundHandler?.postDelayed(this, 800) // 0.8초마다 백그라운드에서 반복 실행 (배터리 세이브)
        }
    }

    // 💡 수정 및 최적화된 초고속 분석 엔진
    private fun analyzeScreenFast() {
        val image = imageReader?.acquireLatestImage() ?: return
        try {
            val planes = image.planes
            val buffer = planes[0].buffer          
            val pixelStride = planes[0].pixelStride  
            val rowStride = planes[0].rowStride      
            val rowPadding = rowStride - pixelStride * screenWidth

            // 전체 화면을 담는 단 한 장의 비트맵 생성 (루프 외부이므로 안전함)
            val bitmap = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)

            var boardTop = 0
            var boardBottom = 0
            var boardLeft = 0
            var boardRight = 0

            val centerX = screenWidth / 2
            val startY = (screenHeight * 0.25).toInt()
            val endY = (screenHeight * 0.80).toInt()

            // 1. 보드판의 상하 경계 스캔
            for (y in startY..endY) {
                val pixel = bitmap.getPixel(centerX, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                // 로얄매치 특유의 갈색/베이지색 테두리 감지 필터
                if (r in 30..95 && g in 25..85 && b in 20..80) {
                    if (boardTop == 0) boardTop = y
                    boardBottom = y
                }
            }

            // 감지 실패 시 기기별 해상도 기준 하드코딩 방어막
            if (boardTop == 0 || boardBottom == 0 || (boardBottom - boardTop) < 300) {
                boardTop = (screenHeight * 0.35).toInt()
                boardBottom = (screenHeight * 0.75).toInt()
            }

            // 2. 보드판의 좌우 경계 스캔
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

            // 💡 3. 하이브리드 최적화 핵심: 새로운 미니 비트맵을 자르지 않고 원본 좌표에서 바로 샘플링하여 64배 속도 향상
            for (r in 0 until GRID_ROWS) {
                for (c in 0 until GRID_COLS) {
                    val pixelX = boardLeft + (c * blockSize) + (blockSize / 2)
                    val pixelY = boardTop + (r * blockSize) + (blockSize / 2)
                    
                    if (pixelX < bitmap.width && pixelY < bitmap.height) {
                        val pixel = bitmap.getPixel(pixelX, pixelY)
                        colorGrid[r][c] = identifyColorSpec(pixel)
                    }
                }
            }

            // 4. 알고리즘 연산을 통해 5개 매칭 스왑 패턴 탐색
            val hint = findFiveMatchPattern(colorGrid, GRID_ROWS, GRID_COLS)
            
            // 💡 5. UI 드로잉 업데이트는 안전하게 메인(UI) 핸들러를 통해서만 전달
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

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            image.close() // ⚠️ 리소스 누수 방지를 위한 필수 클로즈
        }
    }

    // 로얄 매치 전용 블록 색상 스펙트럼 필터 (감도 튜닝 최적화 버전)
    private fun identifyColorSpec(pixel: Int): Int {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        
        if (r + g + b < 100) return 0 

        return when {
            r > 150 && g > 140 && b < 130 -> 3   // 💛 동전/왕관 (노랑)
            r > 140 && g < 90 && b < 90   -> 1   // ❤️ 책 (빨강)
            b > 140 && r < 100 && g < 130 -> 2   // 💙 방패 (파랑)
            g > 130 && r < 100 && b < 100 -> 4   // 💚 클로버 (초록)
            r > 130 && b > 140 && g < 100 -> 5   // 💜 모자 (보라)
            else -> 0
        }
    }

    data class MatchHint(val fromR: Int, val fromC: Int, val toR: Int, val toC: Int)

    // Match-5 패턴 탐색 가상 시뮬레이션
    private fun findFiveMatchPattern(grid: Array<IntArray>, rows: Int, cols: Int): MatchHint? {
        val directions = arrayOf(Pair(0, 1), Pair(1, 0)) // 우측, 하단 스왑 검사
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (grid[r][c] == 0) continue
                for (dir in directions) {
                    val nr = r + dir.first
                    val nc = c + dir.second
                    if (nr < rows && nc < cols && grid[nr][nc] != 0) {
                        // 가상으로 두 격자의 블록 위치를 바꿈
                        val temp = grid[r][c]
                        grid[r][c] = grid[nr][nc]
                        grid[nr][nc] = temp

                        // 5개 정렬이 완성되는지 검증
                        if (checkGridMatch5(grid, rows, cols)) {
                            return MatchHint(r, c, nr, nc) // 힌트 좌표 즉시 반환
                        }
                        
                        // 원상 복구
                        grid[nr][nc] = grid[r][c]
                        grid[r][c] = temp
                    }
                }
            }
        }
        return null
    }

    private fun checkGridMatch5(grid: Array<IntArray>, rows: Int, cols: Int): Boolean {
        // 가로 5개 연속 매칭 검사
        for (r in 0 until rows) {
            for (c in 0..cols - 5) {
                val color = grid[r][c]
                if (color != 0 && color == grid[r][c+1] && color == grid[r][c+2] && color == grid[r][c+3] && color == grid[r][c+4]) return true
            }
        }
        // 세로 5개 연속 매칭 검사
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
        backgroundHandler?.removeCallbacks(analyzeRunnable)
        backgroundThread?.quitSafety() // 백그라운드 스레드 안전하게 파괴
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        if (overlayView != null) {
            windowManager?.removeView(overlayView)
        }
    }
}

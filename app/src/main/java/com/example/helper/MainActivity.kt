package com.example.helper

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"
    private val OVERLAY_PERMISSION_REQ_CODE = 1000
    private val SCREEN_CAPTURE_REQ_CODE = 1001
    private lateinit var mediaProjectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MainActivity onCreate")
        
        val startButton = Button(this).apply { 
            text = "로얄매치 패턴 도우미 시작" 
            textSize = 20f
        }
        setContentView(startButton)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        startButton.setOnClickListener {
            checkPermissionsAndStart()
        }
    }

    private fun checkPermissionsAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "다른 앱 위에 그리기 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivityForResult(intent, OVERLAY_PERMISSION_REQ_CODE)
            return
        }

        startActivityForResult(
            mediaProjectionManager.createScreenCaptureIntent(),
            SCREEN_CAPTURE_REQ_CODE
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d(TAG, "onActivityResult: requestCode=$requestCode, resultCode=$resultCode")
        
        when (requestCode) {
            OVERLAY_PERMISSION_REQ_CODE -> {
                if (Settings.canDrawOverlays(this)) {
                    checkPermissionsAndStart()
                } else {
                    Toast.makeText(this, "권한이 거부되어 서비스를 시작할 수 없습니다.", Toast.LENGTH_SHORT).show()
                }
            }
            SCREEN_CAPTURE_REQ_CODE -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    try {
                        Log.d(TAG, "OverlayService 기동 준비 중...")
                        val serviceIntent = Intent(this, OverlayService::class.java).apply {
                            putExtra("RESULT_CODE", resultCode)
                            putExtra("DATA_INTENT", data)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(serviceIntent)
                        } else {
                            startService(serviceIntent)
                        }
                        // 💡 [주의] 여기서 finish나 moveTaskToBack을 절대 하지 않고 가만히 대기합니다.
                    } catch (e: Exception) {
                        Log.e(TAG, "서비스 시작 실패", e)
                        Toast.makeText(this, "서비스 시작 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "화면 캡처 권한이 거부되었습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 💡 서비스에서 캡처 장치 주입을 완벽하게 끝내면 이쪽으로 신호가 와서 그때 안전하게 앱을 종료합니다.
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra("ACTION_FINISH", false) == true) {
            Log.d(TAG, "서비스 결속 성공 신호 수신. 액티비티를 종료합니다.")
            finish()
        }
    }
}

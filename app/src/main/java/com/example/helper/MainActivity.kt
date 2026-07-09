package com.example.helper

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager // 💡 [해결] 누락되었던 핵심 임포트 추가
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private val OVERLAY_PERMISSION_REQ_CODE = 1000
    private val SCREEN_CAPTURE_REQ_CODE = 1001
    private lateinit var mediaProjectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // XML 레이아웃 파일 없이 버튼 하나로 꽉 채운 메인 화면
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
        // 1. 오버레이(다른 앱 위에 그리기) 권한 확인
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "다른 앱 위에 그리기 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivityForResult(intent, OVERLAY_PERMISSION_REQ_CODE)
            return
        }

        // 2. 마이크 권한 없이 곧바로 시스템 화면 캡처 권한 요청 실행
        startActivityForResult(
            mediaProjectionManager.createScreenCaptureIntent(),
            SCREEN_CAPTURE_REQ_CODE
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        when (requestCode) {
            OVERLAY_PERMISSION_REQ_CODE -> {
                if (Settings.canDrawOverlays(this)) {
                    checkPermissionsAndStart() // 권한 획득 성공 시 다음 단계로
                } else {
                    Toast.makeText(this, "권한이 거부되어 서비스를 시작할 수 없습니다.", Toast.LENGTH_SHORT).show()
                }
            }
            SCREEN_CAPTURE_REQ_CODE -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    try {
                        val serviceIntent = Intent(this, OverlayService::class.java).apply {
                            putExtra("RESULT_CODE", resultCode)
                            putExtra("DATA_INTENT", data)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(serviceIntent)
                        } else {
                            startService(serviceIntent)
                        }
                        finish() // 서비스가 켜지면 메인 화면은 깔끔하게 종료
                    } catch (e: Exception) {
                        Toast.makeText(this, "서비스 시작 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "화면 캡처 권한이 거부되었습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

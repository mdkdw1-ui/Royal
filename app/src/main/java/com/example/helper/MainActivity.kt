package com.example.helper

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    // 🎯 에러 원인이었던 권한 요청 코드 상수 정의
    private val REQUEST_CODE_SCREEN_CAPTURE = 1000
    private val REQUEST_CODE_OVERLAY = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // OverlayService에서 종료 플래그를 들고 메인 액티비티를 불렀을 때 완전히 앱을 닫아주는 처리
        if (intent.getBooleanExtra("ACTION_FINISH", false)) {
            finish()
            return
        }

        // 레이아웃 XML 없이 코드로 깔끔하게 버튼 하나 생성
        val startButton = Button(this).apply {
            text = "로얄매치 AI 가변 격자 시작"
            textSize = 16f
            setOnClickListener {
                checkPermissionsAndStart()
            }
        }
        setContentView(startButton)
    }

    private fun checkPermissionsAndStart() {
        // 1단계: 다른 앱 위에 그리기(오버레이) 권한 체크
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, REQUEST_CODE_OVERLAY)
        } else {
            // 권한이 이미 있다면 바로 화면 공유 요청
            startScreenCaptureRequest()
        }
    }

    private fun startScreenCaptureRequest() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // 시스템 표준 화면 공유 권한 팝업 띄우기
        startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), REQUEST_CODE_SCREEN_CAPTURE)
    }

    // 🎯 핵심 피드백: 사용자가 '지금 시작'을 누르면 안전하게 서비스 실행 후 즉시 백그라운드 전환
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // 오버레이 권한 승인 후 돌아왔을 때
        if (requestCode == REQUEST_CODE_OVERLAY) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                startScreenCaptureRequest()
            } else {
                Toast.makeText(this, "오버레이 권한이 거부되면 힌트를 표시할 수 없습니다.", Toast.LENGTH_SHORT).show()
            }
        }

        // 화면 공유 팝업에서 사용자가 승인/거부했을 때
        if (requestCode == REQUEST_CODE_SCREEN_CAPTURE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val serviceIntent = Intent(this, OverlayService::class.java).apply {
                    putExtra("RESULT_CODE", resultCode)
                    putExtra("DATA_INTENT", data)
                }

                // 1. 검증된 포그라운드 서비스 실행
                ContextCompat.startForegroundService(this, serviceIntent)

                // 2. 승인 즉시 앱을 바탕화면(백그라운드)으로 안전하게 내리기
                moveTaskToBack(true)
            } else {
                Toast.makeText(this, "화면 공유 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

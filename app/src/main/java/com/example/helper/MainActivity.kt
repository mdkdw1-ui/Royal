// MainActivity 내부의 화면 공유 승인 처리 콜백 부분
override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    
    if (requestCode == REQUEST_CODE_SCREEN_CAPTURE && resultCode == Activity.RESULT_OK && data != null) {
        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            putExtra("RESULT_CODE", resultCode)
            putExtra("DATA_INTENT", data)
        }
        
        // 1. 서비스 실행
        androidx.core.content.ContextCompat.startForegroundService(this, serviceIntent)
        
        // 🎯 [핵심 수정] 사용자가 승인 버튼을 누르자마자 즉시 앱을 백그라운드로 안전하게 내림
        moveTaskToBack(true)
    }
}

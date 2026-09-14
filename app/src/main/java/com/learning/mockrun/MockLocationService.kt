package com.learning.mockrun

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Phase 0 仅是占位:让 Manifest 里的前台服务声明能通过编译。
 * Phase 1 会把它改造成前台服务,负责 addTestProvider 和持续注入坐标。
 */
class MockLocationService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null
}

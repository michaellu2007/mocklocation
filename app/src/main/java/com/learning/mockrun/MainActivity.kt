package com.learning.mockrun

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Phase 0:只验证构建链路和模拟位置授权状态,不含任何注入逻辑。
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val status = findViewById<TextView>(R.id.status_text)
        val granted = if (isMockLocationAllowed()) "已授权" else "未授权"
        status.text = getString(R.string.phase0_status, packageName, granted)
    }

    /**
     * 检查本应用是否被允许模拟位置。
     * 不用 Settings.Secure.ALLOW_MOCK_LOCATION:那是全机全局开关且 API 23 起废弃,
     * AppOps 按 uid/包名判断,是权威口径。
     * API 29 起 checkOpNoThrow 废弃,改用 unsafeCheckOpNoThrow。
     */
    private fun isMockLocationAllowed(): Boolean {
        return try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION,
                    Process.myUid(),
                    packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION,
                    Process.myUid(),
                    packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }
}

package com.learning.mockrun

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 运行状态悬浮窗:可拖动的爪印气泡,点开展开显示路线名/速度,附停止按钮。
 * 参考 LocationSpoofer 的 FloatingJoystickService,但它那套是 Compose + Service 内
 * LifecycleOwner 脚手架,本项目是 View 体系,直接普通 View 更短。
 *
 * 权限:SYSTEM_ALERT_WINDOW,须先由 MainActivity 经 canDrawOverlays 引导用户授权。
 * Android 12+ 禁止后台启动前台服务,但本服务非前台服务,由 Activity 启动即可。
 */
class FloatingPanelService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var rootView: View? = null
    private val handler = Handler(Looper.getMainLooper())

    private val poll = object : Runnable {
        override fun run() {
            val motion = MockLocationService.currentClean ?: MockLocationService.currentMotion
            if (motion == null) {
                // 模拟已结束(自家停止或回放完成),浮窗跟着收
                stopSelf()
                return
            }
            statusView?.text = getString(
                R.string.float_status, MockLocationService.activeName ?: "", motion.speedMps
            )
            handler.postDelayed(this, POLL_MS)
        }
    }

    private var statusView: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        show()
        handler.post(poll)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        rootView?.let { wm.removeView(it) }
        rootView = null
        isShowing = false
        super.onDestroy()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show() {
        val pad = (12 * resources.displayMetrics.density).toInt()
        val bubbleSize = (48 * resources.displayMetrics.density).toInt()

        // 收起态:爪印圆泡
        val bubble = TextView(this).apply {
            text = "🐾"
            textSize = 22f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(bubbleSize, bubbleSize)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE6222222.toInt())
            }
        }

        // 展开态:状态 + 停止
        val expanded = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE6222222.toInt())
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(0xE6222222.toInt())
                cornerRadius = pad.toFloat()
            }
            addView(TextView(this@FloatingPanelService).apply {
                text = getString(R.string.float_title)
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
            })
            addView(TextView(this@FloatingPanelService).apply {
                statusView = this
                setTextColor(0xFFCCCCCC.toInt())
                textSize = 13f
                setPadding(0, pad / 2, 0, pad)
            })
            addView(Button(this@FloatingPanelService).apply {
                text = getString(R.string.float_stop)
                setOnClickListener {
                    MockLocationService.stop(this@FloatingPanelService)
                    stopSelf()
                }
            })
        }

        val container = LinearLayout(this).apply { addView(bubble) }
        var expandedState = false
        fun toggle() {
            expandedState = !expandedState
            container.removeAllViews()
            container.addView(if (expandedState) expanded else bubble)
            wm.updateViewLayout(container, params)
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 200
        }

        // 拖动与点击共存:位移超过阈值算拖动,抬起时不触发展开/收起
        val slop = 12 * resources.displayMetrics.density
        var downX = 0f; var downY = 0f; var dragging = false
        container.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (dragging || dx * dx + dy * dy > slop * slop) {
                        dragging = true
                        params.x += dx.toInt()
                        params.y += dy.toInt()
                        downX = e.rawX; downY = e.rawY
                        wm.updateViewLayout(container, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) toggle()
                    true
                }
                else -> false
            }
        }

        rootView = container
        wm.addView(container, params)
        isShowing = true
    }

    companion object {
        private const val POLL_MS = 1000L

        @Volatile
        var isShowing = false
            private set

        /** 仅供前台 Activity 调用(Android 12+ 限制后台起服务,别在别处启动) */
        fun toggle(context: Context) {
            if (isShowing) {
                context.stopService(Intent(context, FloatingPanelService::class.java))
            } else {
                context.startService(Intent(context, FloatingPanelService::class.java))
            }
        }
    }
}

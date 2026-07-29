package com.powermonitor.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import com.powermonitor.R
import com.powermonitor.util.DataBus
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 全局悬浮窗服务：显示圆形悬浮球，双击切换 瞬时功率 / 累计耗电量
 */
class FloatingWindowService : Service(), LifecycleOwner {

    private lateinit var lifecycleRegistry: LifecycleRegistry
    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var textView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var modeShowInstant = true  // true=瞬时功率, false=累计耗电
    private var lastClickTime = 0L
    private var isDragging = false

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        addFloatingView()
        observeData()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        floatingView?.visibility = View.VISIBLE
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        removeFloatingView()
    }

    override fun getLifecycle(): Lifecycle = lifecycleRegistry

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun addFloatingView() {
        try {
            val inflater = LayoutInflater.from(this)
            val view = inflater.inflate(R.layout.view_floating_ball, null, false)
            floatingView = view
            textView = view.findViewById(R.id.floatingText)
            updateText(0, 0.0)

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 50
                y = 200
            }
            layoutParams = params
            windowManager.addView(view, params)

            // 触摸：拖动 + 双击切换
            view.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        isDragging = false
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (abs(dx) > 8 || abs(dy) > 8) isDragging = true
                        if (isDragging) {
                            params.x = initialX + dx
                            params.y = initialY + dy
                            runCatching { windowManager.updateViewLayout(view, params) }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            val now = System.currentTimeMillis()
                            if (now - lastClickTime < 350) {
                                // 双击切换
                                modeShowInstant = !modeShowInstant
                                val sample = DataBus.latestSample.value
                                updateText(sample.power, DataBus.accumulatedMah.value)
                            }
                            lastClickTime = now
                        }
                        view.performClick()
                        true
                    }
                    else -> false
                }
            }
        } catch (t: Throwable) {
            // 无权限或其他异常，停止服务
            stopSelf()
        }
    }

    private fun removeFloatingView() {
        floatingView?.let { view ->
            runCatching { windowManager.removeView(view) }
            floatingView = null
        }
    }

    private fun observeData() {
        lifecycleScope.launch {
            launch {
                DataBus.latestSample.collectLatest { s ->
                    if (modeShowInstant) updateText(s.power, DataBus.accumulatedMah.value)
                }
            }
            launch {
                DataBus.accumulatedMah.collectLatest { mah ->
                    if (!modeShowInstant) updateText(DataBus.latestSample.value.power, mah)
                }
            }
        }
    }

    private fun updateText(powerMw: Int, accumulatedMah: Double) {
        val text = if (modeShowInstant) {
            getString(R.string.floating_instant) + "\n${powerMw}mW"
        } else {
            getString(R.string.floating_accumulated) + "\n${String.format("%.2f", accumulatedMah)}mAh"
        }
        textView?.post { textView?.text = text }
    }
}

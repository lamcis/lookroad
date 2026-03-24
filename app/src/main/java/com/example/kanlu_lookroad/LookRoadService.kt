package com.example.kanlu_lookroad

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlin.math.abs

class LookRoadService : Service(), LifecycleOwner {

    companion object {
        var isRunning = false
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle = lifecycleRegistry

    private lateinit var windowManager: WindowManager
    private var cameraOverlay: PreviewView? = null
    private var pauseOverlay: View? = null
    private var floatingPanel: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var floatingParams: WindowManager.LayoutParams? = null

    private var camera: Camera? = null
    private var isTorchOn = false
    private var cameraProvider: ProcessCameraProvider? = null
    private var isSettingsOpen = false

    private val systemCameraManager by lazy {
        getSystemService(CAMERA_SERVICE) as CameraManager
    }

    private val cameraAvailabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            if (cameraId == "0") {
                systemCameraManager.unregisterAvailabilityCallback(this)
                hidePauseOverlay()
                cameraOverlay?.post { cameraOverlay?.let { bindCamera(it) } }
            }
        }
        override fun onCameraUnavailable(cameraId: String) {}
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) { updateOverlaySize() }
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        startForeground(1, buildNotification())
        if (cameraOverlay != null) return START_STICKY

        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val displayManager = getSystemService(DisplayManager::class.java)
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))

        setupCameraOverlay()
        setupFloatingControls()

        return START_STICKY
    }

    // ─── 摄像头图层 ────────────────────────────────────────────

    private fun setupCameraOverlay() {
        val previewView = PreviewView(this)
        cameraOverlay = previewView

        val bounds = windowManager.currentWindowMetrics.bounds
        val params = WindowManager.LayoutParams(
            bounds.width(), bounds.height(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.fitInsetsTypes = 0
        params.gravity = Gravity.TOP or Gravity.START
        overlayParams = params
        params.alpha = 0.3f
        windowManager.addView(previewView, params)
        startCamera(previewView)
    }

    private fun updateOverlaySize() {
        val bounds = windowManager.currentWindowMetrics.bounds
        overlayParams?.let { params ->
            params.width = bounds.width()
            params.height = bounds.height()
            cameraOverlay?.let { windowManager.updateViewLayout(it, params) }
        }
    }

    private fun showPauseOverlay() {
        if (pauseOverlay != null) return
        val view = View(this)
        view.setBackgroundColor(Color.BLACK)
        val bounds = windowManager.currentWindowMetrics.bounds
        val params = WindowManager.LayoutParams(
            bounds.width(), bounds.height(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        )
        params.fitInsetsTypes = 0
        params.gravity = Gravity.TOP or Gravity.START
        windowManager.addView(view, params)
        pauseOverlay = view
    }

    private fun hidePauseOverlay() {
        pauseOverlay?.let { windowManager.removeView(it) }
        pauseOverlay = null
    }

    // ─── 摄像头逻辑 ────────────────────────────────────────────

    private fun startCamera(previewView: PreviewView) {
        ProcessCameraProvider.getInstance(this).also { future ->
            future.addListener({
                cameraProvider = future.get()
                bindCamera(previewView)
            }, ContextCompat.getMainExecutor(this))
        }
    }

    private fun bindCamera(previewView: PreviewView) {
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }
        try {
            camera = cameraProvider?.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview
            )
            camera?.cameraInfo?.cameraState?.observe(this) { state ->
                when (state.type) {
                    CameraState.Type.OPEN -> {
                        hidePauseOverlay()
                        cameraOverlay?.visibility = View.VISIBLE
                    }
                    CameraState.Type.OPENING -> {
                        if (state.error?.code == CameraState.ERROR_CAMERA_IN_USE) {
                            cameraOverlay?.visibility = View.INVISIBLE
                            showPauseOverlay()
                            cameraProvider?.unbindAll()
                            systemCameraManager.registerAvailabilityCallback(
                                cameraAvailabilityCallback,
                                Handler(Looper.getMainLooper())
                            )
                        }
                    }
                    CameraState.Type.CLOSING,
                    CameraState.Type.CLOSED -> {
                        cameraOverlay?.visibility = View.INVISIBLE
                    }
                    else -> {}
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("LookRoad", "摄像头绑定失败: ${e.message}")
        }
    }

    // ─── 悬浮控制面板 ──────────────────────────────────────────

    private fun setupFloatingControls() {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.gravity = Gravity.END  // 按钮靠右对齐

        val settingsPanel = buildSettingsPanel()
        settingsPanel.visibility = View.GONE
        container.addView(settingsPanel)

        val settingsBtn = makeButton("⚙️")
        settingsBtn.setOnClickListener {
            isSettingsOpen = !isSettingsOpen
            settingsPanel.visibility = if (isSettingsOpen) View.VISIBLE else View.GONE
        }
        container.addView(settingsBtn)

        val torchBtn = makeButton("🔦")
        torchBtn.setOnClickListener {
            isTorchOn = !isTorchOn
            camera?.cameraControl?.enableTorch(isTorchOn)
            torchBtn.setBackgroundColor(
                if (isTorchOn) Color.argb(220, 255, 200, 0)
                else Color.argb(180, 0, 0, 0)
            )
        }
        container.addView(torchBtn)

        val params = WindowManager.LayoutParams(
            // 固定宽度，不再 WRAP_CONTENT，防止展开时抖动
            (260 * resources.displayMetrics.density).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.END
        params.x = 20
        params.y = 200
        floatingParams = params

        windowManager.addView(container, params)
        floatingPanel = container
        makeDraggable(container, params)
    }

    private fun buildSettingsPanel(): LinearLayout {
        val dp = resources.displayMetrics.density

        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(Color.argb(220, 20, 20, 20))
        val pad = (16 * dp).toInt()
        panel.setPadding(pad, pad, pad, pad)

        // 标题
        val title = TextView(this)
        title.text = "设置"
        title.setTextColor(Color.WHITE)
        title.textSize = 15f
        title.gravity = Gravity.CENTER
        panel.addView(title)

        // ── 亮度 ──
        panel.addView(makeLabel("☀ 屏幕亮度"))
        val brightnessBar = SeekBar(this)
        brightnessBar.max = 255
        brightnessBar.progress = Settings.System.getInt(
            contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128
        )
        brightnessBar.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        brightnessBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) Settings.System.putInt(
                    contentResolver, Settings.System.SCREEN_BRIGHTNESS, progress.coerceIn(1, 255)
                )
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        panel.addView(brightnessBar)

        // ── 透明度 ──
        panel.addView(makeLabel("👁 画面透明度"))
        val alphaBar = SeekBar(this)
        alphaBar.max = 100
        alphaBar.progress = 30
        alphaBar.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        alphaBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    overlayParams?.alpha = progress / 100f
                    cameraOverlay?.let { windowManager.updateViewLayout(it, overlayParams) }
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        panel.addView(alphaBar)

        // ── 退出 ──
        val exitBtn = TextView(this)
        exitBtn.text = "退出看路"
        exitBtn.setTextColor(Color.WHITE)
        exitBtn.setBackgroundColor(Color.argb(200, 180, 30, 30))
        exitBtn.gravity = Gravity.CENTER
        exitBtn.textSize = 14f
        val exitLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (44 * dp).toInt()
        )
        exitLp.topMargin = (12 * dp).toInt()
        exitBtn.layoutParams = exitLp
        exitBtn.setOnClickListener { stopSelf() }
        panel.addView(exitBtn)

        return panel
    }

    // ─── 工具函数 ──────────────────────────────────────────────

    private fun makeButton(emoji: String): TextView {
        val btn = TextView(this)
        btn.text = emoji
        btn.textSize = 26f
        btn.gravity = Gravity.CENTER
        btn.setBackgroundColor(Color.argb(180, 0, 0, 0))
        btn.setPadding(8, 8, 8, 8)
        val lp = LinearLayout.LayoutParams(110, 110)
        lp.topMargin = 6
        btn.layoutParams = lp
        return btn
    }

    private fun makeLabel(text: String): TextView {
        val label = TextView(this)
        label.text = text
        label.setTextColor(Color.LTGRAY)
        label.textSize = 11f
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = 12
        label.layoutParams = lp
        return label
    }

    private fun makeDraggable(view: View, params: WindowManager.LayoutParams) {
        var startX = 0f
        var startY = 0f
        var startParamX = 0
        var startParamY = 0
        var isDragging = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    startParamX = params.x
                    startParamY = params.y
                    isDragging = false
                    false  // 不消耗，让点击事件正常传递
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (!isDragging && (abs(dx) > 8 || abs(dy) > 8)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = (startParamX - dx).toInt()
                        params.y = (startParamY + dy).toInt()
                        windowManager.updateViewLayout(view, params)
                    }
                    isDragging
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        view.performClick()  // 加这行
                    }
                    isDragging
                }
                else -> false
            }
        }
    }

    // ─── 生命周期 ──────────────────────────────────────────────

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
        systemCameraManager.unregisterAvailabilityCallback(cameraAvailabilityCallback)
        getSystemService(DisplayManager::class.java)
            .unregisterDisplayListener(displayListener)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_DESTROY)
        hidePauseOverlay()
        cameraOverlay?.let { windowManager.removeView(it) }
        floatingPanel?.let { windowManager.removeView(it) }
        cameraOverlay = null
        floatingPanel = null
    }

    private fun buildNotification(): Notification {
        val channelId = "lookroad_channel"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(channelId, "看路运行状态", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, channelId)
            .setContentTitle("看路运行中")
            .setContentText("触摸穿透已启用")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }



}


package com.example.kanlu_lookroad

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Size
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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

    // 悬浮窗相关视图
    private var floatingRoot: FloatingContainer? = null
    private var edgeIcon: EdgeIconView? = null
    private var expandedMenu: LinearLayout? = null
    private var settingsPanel: LinearLayout? = null

    private var overlayParams: WindowManager.LayoutParams? = null
    private var floatingParams: WindowManager.LayoutParams? = null

    private var camera: Camera? = null
    private var isTorchOn = false
    private var cameraProvider: ProcessCameraProvider? = null

    // dp 转 px 工具
    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

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

        getSystemService(DisplayManager::class.java)
            .registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))

        setupCameraOverlay()
        setupFloatingControls()

        return START_STICKY
    }

    // ─── 摄像头图层 ────────────────────────────────────────────

    private fun setupCameraOverlay() {
        val previewView = PreviewView(this)
        cameraOverlay = previewView

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.alpha = 0.3f
        overlayParams = params
        windowManager.addView(previewView, params)
        startCamera(previewView)
    }

    private fun updateOverlaySize() {
        pauseOverlay?.let {
            val p = it.layoutParams as? WindowManager.LayoutParams ?: return
            windowManager.updateViewLayout(it, p)
        }
    }

    private fun showPauseOverlay() {
        if (pauseOverlay != null) return
        val view = View(this)
        view.setBackgroundColor(Color.BLACK)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE
        )
        params.gravity = Gravity.TOP or Gravity.START
        windowManager.addView(view, params)
        pauseOverlay = view
    }

    private fun hidePauseOverlay() {
        pauseOverlay?.let { windowManager.removeView(it) }
        pauseOverlay = null
    }

    private fun startCamera(previewView: PreviewView) {
        ProcessCameraProvider.getInstance(this).also { future ->
            future.addListener({
                cameraProvider = future.get()
                bindCamera(previewView)
            }, ContextCompat.getMainExecutor(this))
        }
    }

    private fun bindCamera(previewView: PreviewView) {
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
            ).build()
        val preview = Preview.Builder().setResolutionSelector(resolutionSelector).build()
            .also { it.surfaceProvider = previewView.surfaceProvider }

        try {
            camera = cameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            camera?.cameraInfo?.cameraState?.observe(this) { state ->
                when (state.type) {
                    CameraState.Type.OPEN -> { hidePauseOverlay(); cameraOverlay?.visibility = View.VISIBLE }
                    CameraState.Type.OPENING -> {
                        if (state.error?.code == CameraState.ERROR_CAMERA_IN_USE) {
                            cameraOverlay?.visibility = View.INVISIBLE
                            showPauseOverlay()
                            cameraProvider?.unbindAll()
                            systemCameraManager.registerAvailabilityCallback(
                                cameraAvailabilityCallback, Handler(Looper.getMainLooper())
                            )
                        }
                    }
                    CameraState.Type.CLOSING, CameraState.Type.CLOSED -> cameraOverlay?.visibility = View.INVISIBLE
                    else -> {}
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    // ─── 悬浮控制面板 (重构) ──────────────────────────────────────────

    private fun setupFloatingControls() {
        floatingRoot = FloatingContainer(this)

        // 1. 折叠状态：靠边的半圆图标
        edgeIcon = EdgeIconView(this)
        val edgeParams = FrameLayout.LayoutParams(40.dp, 84.dp)
        edgeIcon?.layoutParams = edgeParams

        // 2. 展开状态：菜单和设置面板
        expandedMenu = LinearLayout(this)
        expandedMenu?.orientation = LinearLayout.VERTICAL
        expandedMenu?.visibility = View.GONE
        expandedMenu?.setBackgroundColor(Color.TRANSPARENT)

        val btnRow = LinearLayout(this)
        btnRow.orientation = LinearLayout.HORIZONTAL
        btnRow.gravity = Gravity.CENTER

        val settingsBtn = makeButton("⚙️")
        val torchBtn = makeButton("🔦")
        btnRow.addView(settingsBtn)
        btnRow.addView(torchBtn)

        settingsPanel = buildSettingsPanel()
        settingsPanel?.visibility = View.GONE

        expandedMenu?.addView(btnRow)
        expandedMenu?.addView(settingsPanel)

        // 组装 Root
        floatingRoot?.addView(expandedMenu)
        floatingRoot?.addView(edgeIcon)

        // WindowManager 参数设置
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, // 初始不抢焦点
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 200.dp
        floatingParams = params

        windowManager.addView(floatingRoot, params)

        // ─── 事件绑定 ───

        // 点击齿轮：展开/收起具体设置
        settingsBtn.setOnClickListener {
            val isVis = settingsPanel?.visibility == View.VISIBLE
            settingsPanel?.visibility = if (isVis) View.GONE else View.VISIBLE
        }

        // 点击手电筒
        torchBtn.setOnClickListener {
            isTorchOn = !isTorchOn
            camera?.cameraControl?.enableTorch(isTorchOn)
            torchBtn.setBackgroundColor(if (isTorchOn) Color.argb(220, 255, 200, 0) else Color.argb(180, 0, 0, 0))
        }

        // 拖拽与点击展开逻辑挂在 EdgeIcon 上
        setupDragAndClickLogic(edgeIcon!!)
    }

    // 拖拽、点击、自动吸附逻辑
    private fun setupDragAndClickLogic(targetView: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        targetView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = floatingParams?.x ?: 0
                    initialY = floatingParams?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        floatingParams?.x = initialX + dx.toInt()
                        floatingParams?.y = initialY + dy.toInt()
                        windowManager.updateViewLayout(floatingRoot, floatingParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // 如果没有拖拽，认为是点击，展开菜单
                        expandMenu()
                    } else {
                        // 松手时，自动吸附到屏幕边缘
                        snapToEdge()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToEdge() {
        val screenWidth = windowManager.currentWindowMetrics.bounds.width()
        val currentX = floatingParams?.x ?: 0
        val isLeft = currentX < screenWidth / 2
        val targetX = if (isLeft) 0 else screenWidth

        val animator = ValueAnimator.ofInt(currentX, targetX)
        animator.duration = 200
        animator.addUpdateListener { anim ->
            floatingParams?.x = anim.animatedValue as Int
            windowManager.updateViewLayout(floatingRoot, floatingParams)
        }
        animator.start()

        // 切换图标方向
        edgeIcon?.isLeftEdge = isLeft
    }

    private fun expandMenu() {
        edgeIcon?.visibility = View.GONE
        expandedMenu?.visibility = View.VISIBLE
        settingsPanel?.visibility = View.GONE // 默认先只展示两个按钮

        // 修改 WindowManager 标志，允许接收外部点击事件和返回键
        floatingParams?.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        windowManager.updateViewLayout(floatingRoot, floatingParams)
    }

    fun collapseMenu() {
        expandedMenu?.visibility = View.GONE
        edgeIcon?.visibility = View.VISIBLE

        // 恢复不拦截状态，不影响底下 App
        floatingParams?.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        windowManager.updateViewLayout(floatingRoot, floatingParams)

        // 收起时再次确保吸附方向正确
        snapToEdge()
    }

    // ─── 自定义视图组件 ──────────────────────────────────────────────

    // 自定义 Root 容器，用于拦截外部点击和返回键
    inner class FloatingContainer(context: Context) : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            // 按下返回键收起菜单
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                if (expandedMenu?.visibility == View.VISIBLE) {
                    collapseMenu()
                    return true
                }
            }
            return super.dispatchKeyEvent(event)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            // 点击悬浮窗外部区域，收起菜单
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                collapseMenu()
                return true
            }
            return super.onTouchEvent(event)
        }
    }

    // 纯代码绘制：半圆 + 三角形
    inner class EdgeIconView(context: Context) : View(context) {
        var isLeftEdge = true
            set(value) { field = value; invalidate() }

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 40, 40, 40)
            style = Paint.Style.FILL
        }
        private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            path.reset()

            if (isLeftEdge) {
                // 画左侧靠边向右的半圆
                path.moveTo(0f, 0f)
                // 控制点设置为 w * 1.8f，让半圆更饱满一点
                path.quadTo(w * 1.8f, h / 2, 0f, h)
                path.close()
                canvas.drawPath(path, bgPaint)

                // 画向右的三角形 (▶) 稍微放大三角形的比例
                path.reset()
                path.moveTo(w * 0.35f, h * 0.38f)
                path.lineTo(w * 0.75f, h * 0.5f)
                path.lineTo(w * 0.35f, h * 0.62f)
                path.close()
                canvas.drawPath(path, arrowPaint)
            } else {
                // 画右侧靠边向左的半圆
                path.moveTo(w, 0f)
                // 对称处理，右侧控制点向左延展
                path.quadTo(-w * 0.8f, h / 2, w, h)
                path.close()
                canvas.drawPath(path, bgPaint)

                // 画向左的三角形 (◀) 稍微放大三角形的比例
                path.reset()
                path.moveTo(w * 0.65f, h * 0.38f)
                path.lineTo(w * 0.25f, h * 0.5f)
                path.lineTo(w * 0.65f, h * 0.62f)
                path.close()
                canvas.drawPath(path, arrowPaint)
            }
        }
    }

    private fun buildSettingsPanel(): LinearLayout {
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(Color.argb(220, 20, 20, 20))
        panel.setPadding(16.dp, 12.dp, 16.dp, 12.dp)

        // 限制面板宽度，不至于太宽
        val lp = LinearLayout.LayoutParams(260.dp, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.CENTER_HORIZONTAL
        panel.layoutParams = lp

        val title = TextView(this).apply { text = "设置"; setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER }
        panel.addView(title)

        panel.addView(makeLabel("☀ 屏幕亮度"))
        val brightnessBar = makeSeekBar(255).apply {
            progress = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                    if (fromUser) Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, v.coerceIn(1, 255))
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        panel.addView(brightnessBar)

        panel.addView(makeLabel("👁 画面透明度"))
        val alphaBar = makeSeekBar(100).apply {
            progress = ((overlayParams?.alpha ?: 0.3f) * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                    if (fromUser) {
                        overlayParams?.alpha = v / 100f
                        cameraOverlay?.let { windowManager.updateViewLayout(it, overlayParams) }
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        panel.addView(alphaBar)

        val exitBtn = TextView(this).apply {
            text = "退出看路"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(200, 180, 30, 30))
            gravity = Gravity.CENTER
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 44.dp).apply { topMargin = 10.dp }
            setOnClickListener { stopSelf() }
        }
        panel.addView(exitBtn)

        val tip = TextView(this).apply {
            text = "安全提示：本应用仅提供视觉辅助，行走时请注意现实路况，勿过度依赖。"
            setTextColor(Color.argb(180, 200, 200, 200))
            textSize = 9f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 8.dp }
        }
        panel.addView(tip)

        return panel
    }

    private fun makeButton(emoji: String): TextView {
        return TextView(this).apply {
            text = emoji
            textSize = 22f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            layoutParams = LinearLayout.LayoutParams(48.dp, 48.dp).apply { setMargins(4.dp, 4.dp, 4.dp, 4.dp) }
        }
    }

    private fun makeLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.LTGRAY)
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 10.dp }
        }
    }

    private fun makeSeekBar(max: Int): SeekBar {
        return SeekBar(this).apply {
            this.max = max
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    // ─── 生命周期 ──────────────────────────────────────────────

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
        systemCameraManager.unregisterAvailabilityCallback(cameraAvailabilityCallback)
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_DESTROY)
        hidePauseOverlay()
        cameraOverlay?.let { windowManager.removeView(it) }
        floatingRoot?.let { windowManager.removeView(it) }
        cameraOverlay = null
        floatingRoot = null
    }

    private fun buildNotification(): Notification {
        val channelId = "lookroad_channel"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(channelId, "看路运行状态", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, channelId)
            .setContentTitle("看路运行中")
            .setContentText("触摸穿透已启用")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }
}
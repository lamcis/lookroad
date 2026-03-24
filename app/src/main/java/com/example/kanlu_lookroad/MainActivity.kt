package com.example.kanlu_lookroad

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    // 【修改点】：将顶部和底部的伴生对象合并到这里
    companion object {
        const val REQUEST_CAMERA = 100
        const val PREFS_NAME = "lookroad_prefs"
        const val KEY_DISCLAIMER = "disclaimer_accepted"

        val DISCLAIMER_TEXT = """
"看路"App 免责声明与使用须知

欢迎使用"看路"App（以下简称"本应用"）。在您下载、安装及使用本应用前，请您务必仔细阅读并透彻理解本声明。您的下载、安装和使用行为将被视为对本声明全部内容的认可与同意。

一、核心安全警告

1. 本应用仅为提供便利的"辅助工具"，绝非"安全保障工具"。开发者强烈建议且呼吁用户：在行走、上下楼梯、过马路或处于任何需要高度注意周边环境的危险场景下，请优先放下手机，用肉眼观察路况。

2. 本应用通过调用手机后置摄像头提供屏幕背景的实时预览，但由于摄像头视角受限、缺乏深度感知、存在画面畸变或轻微延迟，本应用所展示的画面绝不能完全替代人眼的真实观察。

3. 请勿过度依赖本应用来规避现实世界中的障碍物、车辆或危险源。

二、免责条款

1. 风险自担：您理解并同意，您在使用本应用时，必须对自身的行为及所处的环境负责。如因使用本应用导致任何人身伤害、财产损失或意外事件，均由您个人自行承担全部责任。

2. 开发者免责：无论何种情况，本应用开发者对您因使用或无法使用本应用而导致的任何直接、间接的意外、人身伤害或财产损失，概不承担任何法律及赔偿责任。

三、技术局限性与功能中断

受限于智能手机的系统机制和硬件特性，本应用在运行中可能出现以下情况，此类中断导致的任何后果，开发者不承担责任：

1. 系统权限限制：当您打开某些系统级界面时，本应用可能会暂时隐藏或无法显示。

2. 硬件抢占：当您打开系统相机或第三方视频通话等需要占用摄像头的应用时，本应用的摄像头预览将被迫中断。

3. 卡顿与延迟：受手机性能、发热、电量等因素影响，摄像头预览画面可能出现卡顿或延迟。

4. 系统清理：建议您在系统的"电池管理"中将本应用设置为"无限制"，并在多任务界面将其"锁定"。

四、隐私与权限说明

本应用是一个纯本地运行的工具，不包含任何数据收集模块，不会录制、保存或向任何服务器上传您的摄像头画面及任何个人信息。

如果您不同意本声明的任何内容，请立即停止使用并卸载本应用。
        """.trimIndent()
    }

    private lateinit var tvStatus: TextView
    private lateinit var btnAction: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvStatus = findViewById(R.id.tv_status)
        btnAction = findViewById(R.id.btn_action)
        showDisclaimerIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DISCLAIMER, false)) {
            checkPermissions()
        }
    }

    private fun showDisclaimerIfNeeded() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DISCLAIMER, false)) {
            checkPermissions()
            return
        }

        // 用 ScrollView 包裹长文本
        val scrollView = ScrollView(this)
        val tv = TextView(this)
        tv.text = DISCLAIMER_TEXT
        tv.textSize = 13f
        tv.setPadding(40, 20, 40, 20)
        tv.setTextColor(android.graphics.Color.DKGRAY)
        scrollView.addView(tv)

        AlertDialog.Builder(this)
            .setTitle("使用须知与免责声明")
            .setView(scrollView)
            .setCancelable(false)
            .setPositiveButton("我已阅读并同意") { _, _ ->
                prefs.edit().putBoolean(KEY_DISCLAIMER, true).apply()
                checkPermissions()
            }
            .setNegativeButton("不同意，退出") { _, _ ->
                finish()
            }
            .show()
    }

    private fun checkPermissions() {
        if (!Settings.canDrawOverlays(this)) {
            tvStatus.text = "第 1/3 步：请授予「显示在其他应用上层」权限"
            btnAction.text = "去授权"
            btnAction.setOnClickListener {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
            }
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            tvStatus.text = "第 2/3 步：请授予相机权限"
            btnAction.text = "去授权"
            btnAction.setOnClickListener {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA
                )
            }
            return
        }
        if (!Settings.System.canWrite(this)) {
            tvStatus.text = "第 3/3 步：请授予「修改系统设置」权限（用于调节屏幕亮度）"
            btnAction.text = "去授权"
            btnAction.setOnClickListener {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:$packageName")
                ))
            }
            return
        }
        tvStatus.text = "所有权限已就绪，正在启动..."
        btnAction.isEnabled = false
        if (!LookRoadService.isRunning) {
            startForegroundService(Intent(this, LookRoadService::class.java))
        }
        finish()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA) checkPermissions()
    }
}
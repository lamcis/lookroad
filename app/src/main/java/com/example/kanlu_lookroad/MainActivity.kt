package com.example.kanlu_lookroad

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_CAMERA = 100
    }

    private lateinit var tvStatus: TextView
    private lateinit var btnAction: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tv_status)
        btnAction = findViewById(R.id.btn_action)

        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        // 每次从系统设置返回都重新检查
        // 覆盖悬浮窗和亮度两个需要跳转设置的权限
        checkPermissions()
    }

    private fun checkPermissions() {

        // ── 第一关：悬浮窗权限 ──────────────────────────────
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

        // ── 第二关：相机权限 ────────────────────────────────
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            tvStatus.text = "第 2/3 步：请授予相机权限"
            btnAction.text = "去授权"
            btnAction.setOnClickListener {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.CAMERA),
                    REQUEST_CAMERA
                )
            }
            return
        }

        // ── 第三关：修改系统设置权限（亮度控制）──────────────
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

        // 三关全过
        // 用 LookRoadService.isRunning 判断服务是否已经在跑
        if (!LookRoadService.isRunning) {
            startForegroundService(Intent(this, LookRoadService::class.java))
        }
        finish() // 无论如何都关掉 Activity
    }


    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA) {
            // 无论用户点允许还是拒绝，都重新跑一遍检查链
            // 检查链自己会处理被拒绝的情况
            checkPermissions()
        }
    }
}
package com.example.myapp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var toggleOverlayButton: Button

    private val PERMISSION_REQUEST_CODE = 123
    private val OVERLAY_PERMISSION_REQUEST_CODE = 234

    private lateinit var mediaProjectionManager: MediaProjectionManager

    /**
     * Lanceur pour la demande de permission de superposition.
     */
    private val overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) {
            checkAndRequestPermissions()
        } else {
            Toast.makeText(this, "La permission de superposition est requise", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Lanceur pour la demande de capture d'écran (MediaProjection).
     */
    private val mediaProjectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startOverlayService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "La capture d'écran n'a pas été autorisée", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        toggleOverlayButton = findViewById(R.id.toggle_overlay_button) // Assurez-vous que l'ID existe dans activity_main.xml

        toggleOverlayButton.setOnClickListener {
            if (isServiceRunning(OverlayService::class.java)) {
                stopOverlayService()
            } else {
                checkOverlayPermission()
            }
            updateButtonState()
        }
        updateButtonState()
    }

    private fun checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            checkAndRequestPermissions()
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            requestMediaProjection()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                requestMediaProjection()
            } else {
                Toast.makeText(this, "La permission de stockage est requise", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestMediaProjection() {
        mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startOverlayService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            putExtra("resultCode", resultCode)
            putExtra("data", data)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        updateButtonState()
    }

    private fun stopOverlayService() {
        stopService(Intent(this, OverlayService::class.java))
        updateButtonState()
    }

    private fun updateButtonState() {
        if (isServiceRunning(OverlayService::class.java)) {
            toggleOverlayButton.text = "Désactiver l'Overlay"
            toggleOverlayButton.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
        } else {
            toggleOverlayButton.text = "Activer l'Overlay"
            toggleOverlayButton.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
        }
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }
}
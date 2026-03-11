
package com.example.myapp

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.*
import android.widget.Button
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var captureZone: View
    private lateinit var startStopButton: Button

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private lateinit var imageReader: ImageReader
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private var isCapturing = false

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        
        if (intent != null) {
            mediaProjection = mediaProjectionManager.getMediaProjection(intent.getIntExtra("resultCode", 0), intent.getParcelableExtra("data")!!)
        }

        startForeground(1, NotificationCompat.Builder(this, "overlay_channel")
            .setContentTitle("Overlay Service")
            .setContentText("Capturing screen.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build())

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)
        captureZone = overlayView.findViewById(R.id.capture_zone)
        startStopButton = overlayView.findViewById(R.id.start_stop_button)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(overlayView, params)

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onDown(e: MotionEvent): Boolean {
                val initialParams = captureZone.layoutParams as WindowManager.LayoutParams
                initialX = initialParams.x
                initialY = initialParams.y
                initialTouchX = e.rawX
                initialTouchY = e.rawY
                return true
            }

            override fun onScroll(e1: MotionEvent, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                val newParams = captureZone.layoutParams as WindowManager.LayoutParams
                newParams.x = initialX + (e2.rawX - initialTouchX).toInt()
                newParams.y = initialY + (e2.rawY - initialTouchY).toInt()
                windowManager.updateViewLayout(captureZone, newParams)
                return true
            }
        })

        captureZone.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        startStopButton.setOnClickListener {
            if (isCapturing) {
                stopCapture()
            } else {
                startCapture()
            }
        }

        return START_NOT_STICKY
    }

    private fun startCapture() {
        isCapturing = true
        startStopButton.text = "STOP"
        setupVirtualDisplay()
    }

    private fun stopCapture() {
        isCapturing = false
        startStopButton.text = "START"
        virtualDisplay?.release()
        virtualDisplay = null
    }

    private fun setupVirtualDisplay() {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        val screenDensity = metrics.densityDpi
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            null
        )

        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            processImage(image)
            image.close()
        }, null)
    }

    private fun processImage(image: Image) {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        val captureRect = Rect()
        captureZone.getGlobalVisibleRect(captureRect)

        val croppedBitmap = Bitmap.createBitmap(
            bitmap,
            captureRect.left,
            captureRect.top,
            captureRect.width(),
            captureRect.height()
        )

        val redFilteredBitmap = filterRedColor(croppedBitmap)
        runOcr(redFilteredBitmap)
    }

    private fun filterRedColor(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val p = pixels[i]
            val r = p shr 16 and 0xff
            val g = p shr 8 and 0xff
            val b = p and 0xff

            if (r > 200 && g < 100 && b < 100) {
                // Keep red pixels
            } else {
                pixels[i] = 0 // Make other pixels transparent
            }
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun runOcr(bitmap: Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                for (block in visionText.textBlocks) {
                    for (line in block.lines) {
                        for (element in line.elements) {
                            val elementText = element.text
                            if (elementText.matches(Regex("\d+"))) {
                                saveData(elementText)
                            }
                        }
                    }
                }
            }
            .addOnFailureListener { e ->
                // Handle OCR failure
            }
    }

    private fun saveData(value: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val csvLine = ""$value","$timestamp"\n"

        try {
            val file = File(filesDir, "donnees.csv")
            val writer = FileWriter(file, true)
            writer.append(csvLine)
            writer.flush()
            writer.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
        mediaProjection?.stop()
        windowManager.removeView(overlayView)
    }
}

package com.example.myapp

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
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
    private lateinit var overlayView: ViewGroup
    private lateinit var captureZone: View
    private lateinit var startStopButton: Button

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private lateinit var imageReader: ImageReader
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private var isCapturing = false
    private val captureHandler = Handler(Looper.getMainLooper())
    private var lastCapturedValue: String? = null

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (isCapturing) {
                captureScreen()
                captureHandler.postDelayed(this, 200)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        createNotificationChannel()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            return START_NOT_STICKY
        }
        if (intent.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra("resultCode", 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("data")
        }

        if (data != null) {
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
        }

        if (mediaProjection == null) {
            stopSelf()
            return START_NOT_STICKY
        }


        startForeground(1, NotificationCompat.Builder(this, "overlay_channel")
            .setContentTitle("Overlay Service")
            .setContentText("Capturing screen.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build())

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_layout, null) as ViewGroup
        captureZone = overlayView.findViewById(R.id.capture_zone)
        startStopButton = overlayView.findViewById(R.id.start_stop_button)

        val metrics = Resources.getSystem().displayMetrics
        val threeCmInPixels = (3 * metrics.xdpi / 2.54).toInt()
        val oneCmInPixels = (metrics.xdpi / 2.54).toInt()

        val params = WindowManager.LayoutParams(
            threeCmInPixels,
            oneCmInPixels,
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
                val initialParams = overlayView.layoutParams as WindowManager.LayoutParams
                initialX = initialParams.x
                initialY = initialParams.y
                initialTouchX = e.rawX
                initialTouchY = e.rawY
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                val newParams = overlayView.layoutParams as WindowManager.LayoutParams
                newParams.x = initialX + (e2.rawX - initialTouchX).toInt()
                newParams.y = initialY + (e2.rawY - initialTouchY).toInt()
                windowManager.updateViewLayout(overlayView, newParams)
                return true
            }
        })

        overlayView.setOnTouchListener { _, event ->
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

        return START_STICKY
    }

    private fun startCapture() {
        isCapturing = true
        startStopButton.text = "STOP"
        setupVirtualDisplay()
        captureHandler.post(captureRunnable)
    }

    private fun stopCapture() {
        isCapturing = false
        startStopButton.text = "START"
        captureHandler.removeCallbacks(captureRunnable)
        virtualDisplay?.release()
        virtualDisplay = null
    }

    private fun setupVirtualDisplay() {
        val metrics = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(displayMetrics)
            Rect(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels)
        }
        val screenWidth = metrics.width()
        val screenHeight = metrics.height()
        val screenDensity = resources.displayMetrics.densityDpi

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
    }

    private fun captureScreen() {
        val image = imageReader.acquireLatestImage() ?: return
        processImage(image)
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
        image.close()

        val captureRect = Rect()
        captureZone.getGlobalVisibleRect(captureRect)

        // Ensure crop rectangle is within the bitmap bounds
        if (captureRect.left < 0) captureRect.left = 0
        if (captureRect.top < 0) captureRect.top = 0
        if (captureRect.right > bitmap.width) captureRect.right = bitmap.width
        if (captureRect.bottom > bitmap.height) captureRect.bottom = bitmap.height
        if (captureRect.width() <= 0 || captureRect.height() <= 0) {
            return
        }

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
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)

            if (!(r > 200 && g < 50 && b < 50)) {
                pixels[i] = Color.TRANSPARENT
            }
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun runOcr(bitmap: Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val detectedText = visionText.text.trim().replace(Regex("[^\\\\d]"), "")
                if (detectedText.isNotEmpty() && detectedText != lastCapturedValue) {
                    lastCapturedValue = detectedText
                    saveData(detectedText)
                }
            }
            .addOnFailureListener { e ->
                Log.e("OverlayService", "OCR failed", e)
            }
    }

    private fun saveData(value: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val csvLine = "\"$value\",\"$timestamp\"\n"

        try {
            val file = File(filesDir, "collecte.csv")
            val writer = FileWriter(file, true)
            writer.append(csvLine)
            writer.flush()
            writer.close()
        } catch (e: Exception) {
            Log.e("OverlayService", "Failed to save data", e)
            e.printStackTrace()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Overlay Channel"
            val descriptionText = "Channel for overlay service notification"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel("overlay_channel", name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
        mediaProjection?.stop()
        if (::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }
    }
}

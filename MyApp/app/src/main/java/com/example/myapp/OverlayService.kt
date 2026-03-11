package com.example.myapp

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import java.util.regex.Pattern

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: ViewGroup
    private lateinit var captureZone: View
    private lateinit var collectButton: Button

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private lateinit var imageReader: ImageReader
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private var isCollecting = false
    private val captureHandler = Handler(Looper.getMainLooper())

    // Expression régulière pour extraire uniquement les chiffres
    private val numberPattern = Pattern.compile("[0-9]+")

    /**
     * Tâche répétitive pour la capture et le traitement d'écran.
     */
    private val captureRunnable = object : Runnable {
        override fun run() {
            if (isCollecting) {
                captureAndProcessScreen()
                // Répéter toutes les 500ms
                captureHandler.postDelayed(this, 500)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        createNotificationChannel()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)
        val data: Intent? = intent?.getParcelableExtra("data")

        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.e("OverlayService", "MediaProjection non autorisé")
            stopSelf()
            return START_NOT_STICKY
        }

        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(MediaProjectionCallback(), captureHandler)

        setupOverlay()
        setupVirtualDisplay()

        // Démarrer le service en avant-plan pour éviter qu'il soit tué
        startForeground(1, NotificationCompat.Builder(this, "overlay_channel")
            .setContentTitle("Service de Superposition Actif")
            .setContentText("L'overlay est visible.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build())

        return START_STICKY
    }

    /**
     * Configure la vue de superposition et l'ajoute au WindowManager.
     */
    private fun setupOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_layout, null) as ViewGroup

        captureZone = overlayView.findViewById(R.id.capture_zone)
        collectButton = overlayView.findViewById(R.id.collect_button)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(overlayView, params)

        collectButton.setOnClickListener {
            if (isCollecting) {
                stopCollecting()
            } else {
                startCollecting()
            }
        }
    }

    /**
     * Configure le VirtualDisplay pour la capture d'écran.
     */
    private fun setupVirtualDisplay() {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val screenDensity = displayMetrics.densityDpi

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

    private fun startCollecting() {
        isCollecting = true
        collectButton.text = "Arrêter"
        collectButton.setBackgroundColor(Color.RED)
        captureHandler.post(captureRunnable)
    }

    private fun stopCollecting() {
        isCollecting = false
        collectButton.text = "Collecter"
        collectButton.setBackgroundColor(Color.GRAY)
        captureHandler.removeCallbacks(captureRunnable)
    }

    /**
     * Capture l'écran, extrait la zone, la filtre et lance l'OCR.
     */
    private fun captureAndProcessScreen() {
        val image = imageReader.acquireLatestImage() ?: return
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val fullBitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        fullBitmap.copyPixelsFromBuffer(buffer)
        image.close()

        val captureRect = getCaptureZoneRect()
        if (captureRect.width() <= 0 || captureRect.height() <= 0) return

        // S'assurer que le rectangle de rognage est dans les limites du bitmap
        val croppedBitmap = try {
            Bitmap.createBitmap(fullBitmap, captureRect.left, captureRect.top, captureRect.width(), captureRect.height())
        } catch (e: IllegalArgumentException) {
            Log.e("OverlayService", "Erreur de rognage du bitmap", e)
            fullBitmap.recycle()
            return
        }
        fullBitmap.recycle()

        val redFilteredBitmap = filterRedAndConvertToBlack(croppedBitmap)
        runOcr(redFilteredBitmap)
    }
    
    /**
     * Calcule la position absolue de la zone de capture sur l'écran.
     */
    private fun getCaptureZoneRect(): Rect {
        val location = IntArray(2)
        captureZone.getLocationOnScreen(location)
        return Rect(
            location[0],
            location[1],
            location[0] + captureZone.width,
            location[1] + captureZone.height
        )
    }

    /**
     * Filtre l'image pour ne garder que les pixels rouges, et les convertit en noir.
     * Les autres pixels deviennent blancs.
     */
    private fun filterRedAndConvertToBlack(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()

        for (i in pixels.indices) {
            val p = pixels[i]
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)

            // Seuil pour le rouge (ajustable)
            if (r > 180 && g < 100 && b < 100) {
                pixels[i] = Color.BLACK // Pixel rouge devient noir pour l'OCR
            } else {
                pixels[i] = Color.WHITE // Le reste devient blanc
            }
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Exécute la reconnaissance de texte sur le bitmap fourni.
     */
    private fun runOcr(bitmap: Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val matcher = numberPattern.matcher(visionText.text)
                if (matcher.find()) {
                    val detectedNumber = matcher.group()
                    Log.d("OverlayService", "Chiffre détecté : $detectedNumber")
                    saveDataToCsv(detectedNumber)
                }
            }
            .addOnFailureListener { e ->
                Log.e("OverlayService", "Échec de l'OCR", e)
            }
            .addOnCompleteListener {
                bitmap.recycle() // Libérer la mémoire du bitmap après traitement
            }
    }

    /**
     * Enregistre la valeur extraite dans le fichier CSV.
     */
    private fun saveDataToCsv(value: String) {
        if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            Log.e("OverlayService", "Permission d'écriture refusée.")
            return
        }

        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "donnees_rouges.csv")
        try {
            val writer = FileWriter(file, true) // true pour ajouter au fichier existant
            val date = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            
            // Écrire l'en-tête si le fichier est nouveau
            if (file.length() == 0L) {
                writer.append("Date;Heure;Valeur_Chiffre\n")
            }
            
            writer.append("$date;$time;$value\n")
            writer.flush()
            writer.close()
        } catch (e: Exception) {
            Log.e("OverlayService", "Erreur lors de l'écriture du fichier CSV", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Canal de Superposition"
            val descriptionText = "Notifications pour le service de superposition"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel("overlay_channel", name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCollecting()
        virtualDisplay?.release()
        mediaProjection?.stop()
        if (::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }
    }

    /**
     * Callback pour être notifié lorsque la capture MédiaProjection s'arrête.
     */
    private inner class MediaProjectionCallback : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            Log.d("OverlayService", "MediaProjection arrêté")
            stopSelf()
        }
    }
}
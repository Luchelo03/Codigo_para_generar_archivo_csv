package com.example.prueba

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.drawToBitmap
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class MainActivity : ComponentActivity() {
    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private lateinit var viewFinder: PreviewView
    private lateinit var showResultsButton: Button
    private val capturedFrames = mutableListOf<Bitmap>()
    private val emotionsDetected = mutableListOf<Pair<String, Float>>() //esto nos va a servir para guardar lo relacionado a las emociones y la confianza

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkCameraPermission()

        viewFinder = findViewById(R.id.viewFinder)  // inicializamos el PreviewView
        showResultsButton = findViewById(R.id.showResultsButton) // botón para mostrar los resultados finales

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(Runnable {
            val cameraProvider = cameraProviderFuture.get()
            val preview = androidx.camera.core.Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview)
            } catch (exc: Exception) {
                Log.e("CameraX", "Error en la cámara", exc)
            }
        }, ContextCompat.getMainExecutor(this))

        // aquí la app empezaría a capturar los fotogramas del video
        startCapturingFrames()

        // configuración del boton de resultadossss
        showResultsButton.setOnClickListener {
            generateCSV(emotionsDetected)
        }
    }

    private fun startCapturingFrames() {
        val handler = Handler(Looper.getMainLooper())
        var count = 0

        val runnable = object : Runnable {
            override fun run() {
                if (count < 60) {
                    val bitmap = viewFinder.bitmap // se captura cada fotograma del 'video'
                    bitmap?.let { capturedFrames.add(it) }
                    count++
                    handler.postDelayed(this, 1000)  // un fotograma x seg
                } else {
                    handler.removeCallbacks(this)
                    // procesamos los fotogramas
                    processFrames(capturedFrames)
                }
            }
        }
        handler.post(runnable)
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.CAMERA), 101)
        }
    }

    // función vital para el procesamiento de los fotogramas y detección de las emociones
    private fun processFrames(frames: List<Bitmap>) {
        for (frame in frames) {
            val (emotion, confidence) = detectEmotion(frame)
            emotionsDetected.add(Pair(emotion, confidence))
        }

        // calcula la emoción final
        calculateFinalEmotion(emotionsDetected)
    }

    // arrancamos TensorFlow Lite
    val interpreter: Interpreter by lazy {
        val model = loadModelFile("model.tflite")
        Interpreter(model)
    }

    //detecta la emoción para cada fotograma...AÚN DEBEMOS IMPLEMENTAR EL MODELO(POSIBLEMENTE TF)
    private fun detectEmotion(frame: Bitmap): Pair<String, Float> {


        //falta implementar el modelo para comparar las emociones



        return Pair("Triste", 0.85f)  // ejemplo para mostrar lo que te deberia de retornar
    }

    // la app calcula la emoción final basada en la mayoría de los fotogramas
    private fun calculateFinalEmotion(emotions: List<Pair<String, Float>>) {
        val emotionCounts = mutableMapOf<String, Int>()
        for (emotion in emotions) {
            emotionCounts[emotion.first] = emotionCounts.getOrDefault(emotion.first, 0) + 1
        }

        //valor que guarda la emoción que ha aparecido más veces
        val finalEmotion = emotionCounts.maxByOrNull { it.value }?.key ?: "Neutral"
        displayFinalEmotion(finalEmotion)
    }

    private fun displayFinalEmotion(emotion: String) {
        val emotionResultText: TextView = findViewById(R.id.emotionResultText)
        emotionResultText.text = "Emoción final: $emotion"
        emotionResultText.visibility = View.VISIBLE
    }


    // función para generar el csv
    private fun generateCSV(emotions: List<Pair<String, Float>>) {
        val externalStorageDir = getExternalFilesDir(null)  // almacenamiento externo accesible
        val csvFile = File(externalStorageDir, "reporte_emociones.csv")
        csvFile.printWriter().use { out ->
            out.println("Fotograma,Emoción,Confianza(%)")
            emotions.forEachIndexed { index, emotion ->
                out.println("${index + 1},${emotion.first},${emotion.second * 100}")
            }
        }
        Toast.makeText(this, "Reporte CSV guardado en $csvFile", Toast.LENGTH_LONG).show()
    }


    // Función para cargar el archivo del modelo que aún se está haciendo
    private fun loadModelFile(modelPath: String): ByteBuffer {
        val fileDescriptor = assets.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    private fun startFaceDetection() {
        val realTimeOpts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()

        val faceDetector = FaceDetection.getClient(realTimeOpts)

        val bitmap = viewFinder.bitmap
        if (bitmap != null) {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            faceDetector.process(inputImage)
                .addOnSuccessListener { faces ->
                    if (faces.isNotEmpty()) {
                        for (face in faces) {
                            drawBoundingBox(face.boundingBox)
                        }
                    }
                }
                .addOnFailureListener {
                    Log.e("FaceDetection", "Error al detectar rostros", it)
                }
        }
    }

    private fun drawBoundingBox(rect: Rect) {
        val canvas = Canvas(viewFinder.drawToBitmap())
        val paint = Paint().apply {
            color = Color.GREEN
            strokeWidth = 5f
            style = Paint.Style.STROKE
        }
        canvas.drawRect(rect, paint)
    }

    private fun stopCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(Runnable {
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()
        }, ContextCompat.getMainExecutor(this))
    }

}

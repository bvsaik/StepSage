package com.stepsage.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector.ObjectDetectorOptions
import com.stepsage.app.ui.theme.StepsageTheme
import java.io.File
import java.util.Locale

/* ─────────────────────────────────────────────
 * DTOs sent to Gemma
 * ──────────────────────────────────────────── */
data class Obj(val label: String, val dir: String, val dist: String, val score: Float)
data class Scene(val objects: List<Obj>)

/* ─────────────────────────────────────────────
 * Tunable constants
 * ──────────────────────────────────────────── */
private const val MIN_SPEAK_GAP_MS   = 1_500L
private const val INTRO_HOLD_MS      = 2_000L
private const val PROMPT_INTERVAL_MS = 1_500L
private const val POST_TTS_DELAY_MS  = 1_000L
private const val DETECTOR_INPUT_SIDE = 640
private const val MODEL_FILE_NAME     = "gemma.task"    // copied into filesDir

class MainActivity : ComponentActivity() {

    /* Permissions */
    private val REQ_CAMERA = 1001

    /* Launchers / pickers */
    private lateinit var pickModelLauncher: ActivityResultLauncher<Intent>

    /* ML + TTS */
    private lateinit var detector: ObjectDetector
    private lateinit var llm: LlmInference
    private lateinit var tts: TextToSpeech
    private val gson = Gson()

    /* Throttling */
    private var lastPromptTime = 0L
    private var lastFuture: com.google.common.util.concurrent.ListenableFuture<*>? = null
    private val ttsBuffer = StringBuilder()

    /* Repeat suppression */
    private var lastSceneKey = ""
    private var lastSpeakTime = 0L
    private var nextSpeechAllowedTime = 0L

    /* UI state */
    private val splashGone = mutableStateOf(false)
    @Volatile private var introFinished = false
    @Volatile private var gemmaReady    = false

    /* ───────────── lifecycle ───────────── */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        /* register SAF picker */
        pickModelLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { res ->
            if (res.resultCode == RESULT_OK) {
                res.data?.data?.let { uri ->
                    copyUriToPrivateFiles(uri)
                    startAll()          // model now in place
                } ?: finish()
            } else finish()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        } else if (modelReady()) {
            startAll()
        } else {
            requestModelViaPicker()
        }
    }

    /* ───────────── SAF helpers ───────────── */
    private fun modelReady(): Boolean = File(filesDir, MODEL_FILE_NAME).exists()

    private fun requestModelViaPicker() {
        Toast.makeText(this, "Select Gemma .task file (one-time)", Toast.LENGTH_LONG).show()
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        pickModelLauncher.launch(intent)
    }

    private fun copyUriToPrivateFiles(uri: Uri) {
        val dst = File(filesDir, MODEL_FILE_NAME)
        contentResolver.openInputStream(uri)?.use { inp ->
            dst.outputStream().use { out -> inp.copyTo(out) }
        }
        Toast.makeText(this, "Model copied – starting…", Toast.LENGTH_SHORT).show()
    }

    /* ───────────── startup chain ───────────── */
    private fun startAll() {
        initTTS()
        initLLM()
        initDetector()
        initUI()
    }

    /* ───────────── ObjectDetector ───────────── */
    private fun initDetector() {
        val opts = ObjectDetectorOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath("efficientdet_lite2.tflite")
                    .build()
            )
            .setRunningMode(RunningMode.IMAGE)
            .setMaxResults(5)
            .build()

        detector = ObjectDetector.createFromOptions(this, opts)
        val warmBmp =
            Bitmap.createBitmap(DETECTOR_INPUT_SIDE, DETECTOR_INPUT_SIDE, Bitmap.Config.ARGB_8888)
        detector.detect(BitmapImageBuilder(warmBmp).build())
    }

    /* ───────────── Gemma LLM ───────────── */
    private fun initLLM() {
        val modelPath = File(filesDir, MODEL_FILE_NAME).absolutePath
        llm = LlmInference.createFromOptions(
            this,
            LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(192)
                .build()
        )
        llm.generateResponseAsync("warm-up") { _, done -> if (done) gemmaReady = true }
    }

    /* ───────────── Android TTS ───────────── */
    private fun initTTS() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.US
                tts.setSpeechRate(0.82f)
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) {
                        if (id == "intro3") {               // last chunk finished
                            introFinished = true
                            nextSpeechAllowedTime =
                                SystemClock.elapsedRealtime() + POST_TTS_DELAY_MS
                            Handler(Looper.getMainLooper()).postDelayed(
                                { splashGone.value = true },
                                INTRO_HOLD_MS
                            )
                        }
                    }

                    @Deprecated("legacy")
                    override fun onError(id: String?) {}
                    override fun onError(id: String?, errorCode: Int) {}
                })

                tts.speak(
                    "Hi, I'm StepSage.",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "intro1"
                )

                tts.speak(
                    "Powered by Gemma.",
                    TextToSpeech.QUEUE_ADD,
                    null,
                    "intro2"
                )

                tts.speak(
                    "I'll be your guide today.",
                    TextToSpeech.QUEUE_ADD,
                    null,
                    "intro3"
                )
            }
        }
    }

    /* ───────────── UI + CameraX ───────────── */
    private fun initUI() = setContent {
        StepsageTheme {
            var previewStarted by remember { mutableStateOf(false) }

            Box(Modifier.fillMaxSize()) {
                /* camera preview */
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            layoutParams = android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                    },
                    update = { pv ->
                        if (!previewStarted) {
                            previewStarted = true
                            setupCamera(pv)
                        }
                    }
                )

                /* splash PNG */
                if (!splashGone.value) {
                    Image(
                        painterResource(R.drawable.coverpage),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
    }

    private fun setupCamera(pv: PreviewView) {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val selector = CameraSelector.DEFAULT_BACK_CAMERA

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(pv.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { proxy ->
                try {
                    if (!introFinished) { proxy.close(); return@setAnalyzer }

                    val bmp = pv.bitmap ?: run { proxy.close(); return@setAnalyzer }
                    val res = detector.detect(BitmapImageBuilder(bmp).build())

                    val allowed =
                        setOf("bed", "couch", "chair", "table", "door", "stairs")
                    val w = bmp.width.toFloat()
                    val h = bmp.height.toFloat()
                    val frameArea = w * h

                    val objs = res.detections()
                        .mapNotNull { d ->
                            val c = d.categories()[0]
                            val lbl = c.categoryName().lowercase()
                            if (lbl !in allowed) return@mapNotNull null

                            val box = d.boundingBox()
                            val cx = (box.left + box.right) / 2f
                            val cy = (box.top + box.bottom) / 2f
                            val area = box.width() * box.height()

                            val dir = when {
                                cx < w / 3f -> "left"
                                cx > 2f * w / 3f -> "right"
                                else -> "in front of you"
                            }
                            val dist = when {
                                area > frameArea / 4f -> "very close"
                                cy < h / 3f -> "far"
                                else -> "near"
                            }
                            Obj(lbl, dir, dist, c.score())
                        }
                        .sortedByDescending { it.score }
                        .take(3)

                    if (objs.isEmpty()) { proxy.close(); return@setAnalyzer }

                    val sceneKey = objs.joinToString("|") { "${it.label}_${it.dir}_${it.dist}" }
                    val now = SystemClock.elapsedRealtime()
                    if (sceneKey == lastSceneKey && now - lastSpeakTime < MIN_SPEAK_GAP_MS) {
                        proxy.close(); return@setAnalyzer
                    }
                    lastSceneKey = sceneKey
                    lastSpeakTime = now

                    if (
                        gemmaReady &&
                        !tts.isSpeaking &&
                        now >= nextSpeechAllowedTime &&
                        now - lastPromptTime >= PROMPT_INTERVAL_MS &&
                        (lastFuture?.isDone != false)
                    ) {
                        lastPromptTime = now
                        val json = gson.toJson(Scene(objs))
                        val prompt = """
                            You are StepSage. Speak to a blind person.
                            For each object in JSON output ONE sentence:
                            "There is a <label> <dist> to your <dir>."
                            Never mention numbers or scores.

                            JSON:
                            $json
                        """.trimIndent()

                        lastFuture = llm.generateResponseAsync(prompt) { tok, _ ->
                            runOnUiThread { onGemmaToken(tok) }
                        }
                    }
                } finally {
                    proxy.close()
                }
            }

            provider.unbindAll()
            provider.bindToLifecycle(this, selector, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    /* ───────────── Gemma token → TTS ───────────── */
    private fun onGemmaToken(tok: String) {
        ttsBuffer.append(tok)
        if (tok.endsWith(".") || tok.endsWith("!")) {
            val mode =
                if (tts.isSpeaking) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
            tts.speak(ttsBuffer.toString(), mode, null, "sage")
            ttsBuffer.clear()
            nextSpeechAllowedTime =
                SystemClock.elapsedRealtime() + POST_TTS_DELAY_MS
        }
    }

    /* ───────────── permission result ───────────── */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (
            requestCode == REQ_CAMERA &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            if (modelReady()) startAll() else requestModelViaPicker()
        } else {
            Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onDestroy() {
        if (::tts.isInitialized) tts.shutdown()
        super.onDestroy()
    }
}

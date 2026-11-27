package com.headscan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.view.ViewGroup
import com.headscan.core.HeadStyle
import com.headscan.core.HairStyle
import android.widget.SeekBar
import com.headscan.core.ScanResult
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Camera
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import java.util.EnumSet
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import com.headscan.core.ColorSource
import com.headscan.core.HeadScanPipeline
import com.headscan.core.HeadModel
import com.headscan.core.RearFace
import com.headscan.core.Keyframe
import com.headscan.core.RgbImage
import com.headscan.core.ScanException
import com.headscan.core.ScanSettings
import com.headscan.core.Sharpness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Guided head scan:
 *  1. AIM      - centre the crosshair on the person's nose (30-80 cm away) and tap Start.
 *  2. CAPTURE  - walk slowly around the head; a keyframe (depth + photo + pose) is stored every ~9 degrees.
 *  3. PROCESS  - depth fusion -> surface -> 1500-triangle mesh -> 1024 px texture -> OBJ/MTL/PNG.
 *  4. DONE     - files are in Downloads/HeadScans/..., with a Share button.
 */
class MainActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    private enum class State { AIM, CAPTURING, PROCESSING, PREVIEW, DONE }

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var coverageView: CoverageView
    private lateinit var statusText: TextView
    private lateinit var actionButton: Button
    private lateinit var secondaryButton: Button
    private lateinit var progressBar: ProgressBar

    private var session: Session? = null
    private var installRequested = false
    private val background = BackgroundRenderer()
    private var viewportWidth = 0
    private var viewportHeight = 0
    @Volatile private var viewportChanged = false

    @Volatile private var state = State.AIM
    @Volatile private var startRequested = false
    @Volatile private var startRequestedMs = 0L
    /** Set when Finish is tapped: the GL thread takes one last tracked frame to line the detail photo up with. */
    private val finishRequested = java.util.concurrent.atomic.AtomicBoolean(false)
    private var arCameraId: String? = null

    // Capture data (touched on the GL thread while capturing).
    private val keyframes = ArrayList<Keyframe>()
    private val keyframeDirs = ArrayList<FloatArray>()
    private var headCenter: FloatArray? = null
    private var frontAngle = 0f
    private var lastCaptureMs = 0L
    private val coverage = BooleanArray(COVERAGE_BINS)
    private var highViews = 0
    private val jpegWriter = Executors.newSingleThreadExecutor()
    private lateinit var workDir: File
    private var lastHint = ""

    private var savedUris: List<Uri> = emptyList()

    // Options (remembered between launches)
    private lateinit var optionsPanel: View
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    @Volatile private var selfieMode = false
    /** Image mode: the head is built from one picture chosen from the library (no camera). */
    private var imageMode = false
    // The system photo picker (opens over the app on recent Android; older phones get the file chooser).
    private val pickImage = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) processImage(uri) else if (imageMode && state == State.AIM) showStatus("No picture chosen.\n\n$IMAGE_HINT")
    }
    private var targetTriangles = LOW_TRIANGLES
    private lateinit var selfie: SelfieCapture
    private var headStyle = HeadStyle.REALISTIC
    private var hairStyle = HairStyle.SIDE_PART
    private lateinit var hairGroup: View
    private lateinit var editPanel: View
    private lateinit var editBars: List<SeekBar>
    private var shapeSize = 1f
    private var lastFit: com.headscan.core.HeadFit.Result? = null
    private var rearInput: Pair<List<Keyframe>, FloatArray>? = null      // kept so the preview can rebuild a rear scan
    private lateinit var editPost: android.widget.Spinner
    private var minimised = false
    private var editHair = HairStyle.NONE
    private var editDirty = false
    private var suppressEditEvents = false
    private lateinit var applyButton: Button
    private lateinit var editHairGroup: View
    private lateinit var editHairSpinner: android.widget.Spinner
    // Kept after processing so the preview can rebuild the selfie head with new head size / hair.
    private var selfieInput: Triple<List<Keyframe>, FloatArray, IntArray>? = null
    private var lastSettings = ScanSettings()
    // Hair colour sliders (preview): null hue = natural colour from the photos; tone -1..1.
    private lateinit var colorSliders: View
    private var hairHue: Float? = null
    private var hairTone = 0f
    private var recolorJob: kotlinx.coroutines.Job? = null
    private lateinit var previewView: PreviewView
    private var pendingResult: ScanResult? = null
    private var pendingCheck: android.graphics.Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashLog()
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        surfaceView = findViewById(R.id.surface_view)
        coverageView = findViewById(R.id.coverage_view)
        statusText = findViewById(R.id.status_text)
        actionButton = findViewById(R.id.action_button)
        secondaryButton = findViewById(R.id.secondary_button)
        progressBar = findViewById(R.id.progress)
        findViewById<TextView>(R.id.version_text).text = "v${BuildConfig.VERSION_NAME}"
        optionsPanel = findViewById(R.id.options_panel)
        previewView = findViewById(R.id.preview_view)
        applySystemBarInsets()
        // Every scan starts plain: no post process, no hair (both can be changed on the preview).
        headStyle = HeadStyle.REALISTIC
        hairGroup = findViewById(R.id.hair_group)
        hairStyle = HairStyle.NONE
        val hairSpinner = findViewById<android.widget.Spinner>(R.id.hair_spinner)
        setupHairSpinner(hairSpinner, hairStyle) { h ->
            hairStyle = h
            prefs.edit().putString("hair", h.name).apply()
        }
        setupShapeEditor()
        setupHairColorSliders()
        findViewById<View>(R.id.settings_button).setOnClickListener { showSettings() }
        findViewById<TextView>(R.id.minimise_button).setOnClickListener { v -> setMinimised(!minimised, v as TextView) }

        selfieMode = prefs.getBoolean("selfie", false)
        imageMode = prefs.getBoolean("image", false)
        hairGroup.visibility = View.VISIBLE     // every mode now builds the head from the head model, with a hairstyle
        targetTriangles = prefs.getInt("triangles", LOW_TRIANGLES)
        val qualityGroup = findViewById<RadioGroup>(R.id.quality_group)
        qualityGroup.check(if (targetTriangles == HIGH_TRIANGLES) R.id.quality_high else R.id.quality_low)
        setupModeSpinner(findViewById(R.id.mode_spinner))
        // The head model (shape AI) loads in the background; until it's ready the simple head is used.
        lifecycleScope.launch(Dispatchers.IO) {
            if (HeadModel.default == null) HeadModel.default = try { assets.open("ict_head.bin").use { HeadModel.load(it) } } catch (e: Throwable) { null }
        }
        qualityGroup.setOnCheckedChangeListener { _, id ->
            targetTriangles = if (id == R.id.quality_high) HIGH_TRIANGLES else LOW_TRIANGLES
            prefs.edit().putInt("triangles", targetTriangles).apply()
        }

        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surfaceView.setRenderer(this)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        workDir = File(cacheDir, "capture").apply { deleteRecursively(); mkdirs() }
        selfie = SelfieCapture(workDir) { nv21, w, h, file -> writeJpeg(nv21, w, h, file) }

        actionButton.setOnClickListener {
            when (state) {
                State.AIM -> if (imageMode) {
                    showStatus("Opening your photos…")
                    pickImage.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                } else { startRequestedMs = SystemClock.uptimeMillis(); lastHint = ""; startRequested = true; showStatus(if (selfieMode) "Looking for your face…" else "Looking for the head…") }
                State.CAPTURING -> requestFinish()
                State.PREVIEW -> askNameAndSave()
                State.DONE -> resetScan()
                State.PROCESSING -> Unit
            }
        }
        secondaryButton.setOnClickListener {
            when (state) {
                State.CAPTURING -> resetScan()
                State.PREVIEW -> confirmDiscard()
                State.DONE -> shareResult()
                else -> Unit
            }
        }
        enterAim()
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()
        if (state == State.PREVIEW || state == State.DONE) previewView.onResume()
        if (state != State.AIM && state != State.CAPTURING) return
        if (imageMode && state == State.AIM) return
        startArSession()
    }

    /** Switches between the rear (full head, depth) and front (selfie, face mesh) camera. */
    private fun switchCamera(useSelfie: Boolean) {
        surfaceView.onPause()
        session?.pause()
        session?.close()
        session = null
        selfieMode = useSelfie
        prefs.edit().putBoolean("selfie", useSelfie).apply()
        lastHint = ""
        viewportChanged = true
        actionButton.isEnabled = true
        startArSession()
    }

    private fun startArSession() {
        if (session == null) {
            try {
                if (ArCoreApk.getInstance().requestInstall(this, !installRequested) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    installRequested = true
                    return
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
                    return
                }
                val s = if (selfieMode) Session(this, EnumSet.of(Session.Feature.FRONT_CAMERA)) else Session(this)
                if (!selfieMode && !s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    s.close()
                    showFatal("This phone does not support the ARCore Depth API, which the rear-camera scan needs. Try Selfie mode instead.")
                    return
                }
                chooseCameraConfig(s, if (selfieMode) CameraConfig.FacingDirection.FRONT else CameraConfig.FacingDirection.BACK)
                val config = Config(s).apply {
                    if (selfieMode) {
                        augmentedFaceMode = Config.AugmentedFaceMode.MESH3D
                    } else {
                        depthMode = Config.DepthMode.AUTOMATIC
                        planeFindingMode = Config.PlaneFindingMode.DISABLED
                    }
                    focusMode = Config.FocusMode.AUTO
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    lightEstimationMode = Config.LightEstimationMode.DISABLED
                }
                try {
                    s.configure(config)
                } catch (e: Exception) {
                    s.close()
                    showFatal(if (selfieMode) "This phone doesn't support ARCore face tracking on the front camera." else "Could not configure AR: ${e.message}")
                    return
                }
                session = s
            } catch (e: UnavailableUserDeclinedInstallationException) {
                showFatal("Google Play Services for AR is required. Please install it and reopen the app."); return
            } catch (e: UnavailableArcoreNotInstalledException) {
                showFatal("Google Play Services for AR is required. Please install it and reopen the app."); return
            } catch (e: UnavailableApkTooOldException) {
                showFatal("Please update Google Play Services for AR."); return
            } catch (e: UnavailableSdkTooOldException) {
                showFatal("Please update this app."); return
            } catch (e: UnavailableDeviceNotCompatibleException) {
                showFatal("This phone does not support ARCore."); return
            } catch (e: Exception) {
                showFatal("Could not start AR: ${e.message}"); return
            }
        }
        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            showFatal("The camera is in use by another app.")
            session = null
            return
        }
        surfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        surfaceView.onPause()
        previewView.onPause()
        session?.pause()
    }

    override fun onDestroy() {
        session?.close()
        session = null
        jpegWriter.shutdown()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA && (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this, "Camera permission is needed to scan.", Toast.LENGTH_LONG).show()
            finish()
        }
        // On success onResume() runs again and creates the session.
    }

    /** Prefer a depth-sensor (ToF) config when available, then the largest CPU image (sharper texture). */
    private fun chooseCameraConfig(s: Session, facing: CameraConfig.FacingDirection) {
        val filter = CameraConfigFilter(s).setFacingDirection(facing)
        val configs = s.getSupportedCameraConfigs(filter)
        val best = configs
            // Larger tracking images can stop depth or tracking on some phones; the full-resolution
            // Finish photo gives the sharpness instead.
            .filter { it.imageSize.width <= 1920 }
            .maxWithOrNull(
                compareBy<CameraConfig> { if (it.depthSensorUsage == CameraConfig.DepthSensorUsage.REQUIRE_AND_USE) 1 else 0 }
                    .thenBy { it.imageSize.width * it.imageSize.height }
            )
        if (best != null) s.cameraConfig = best
        arCameraId = s.cameraConfig.cameraId
    }

    // ---------------------------------------------------------------- GL renderer

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        background.createOnGlThread()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
        viewportChanged = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        if (viewportChanged) {
            s.setDisplayGeometry(displayRotation(), viewportWidth, viewportHeight)
            viewportChanged = false
        }
        s.setCameraTextureName(background.textureId)
        val frame = try { s.update() } catch (e: Exception) { return }
        background.draw(frame)

        val camera = frame.camera
        if (selfieMode) {
            drawSelfieFrame(s, frame)
            return
        }
        if (camera.trackingState != TrackingState.TRACKING) {
            hint(trackingHint(camera.trackingFailureReason))
            return
        }
        when (state) {
            State.AIM -> if (startRequested) {
                startRequested = false
                tryStartCapture(frame, camera)
            } else {
                hint("Point the camera at the person's head from about half a metre away and tap Start – no need to aim.\n\nThe person being scanned must keep perfectly still.")
                showDistance(frame)
            }
            State.CAPTURING -> if (finishRequested.compareAndSet(true, false)) {
                val before = keyframes.size
                captureKeyframe(frame, camera)
                val fresh = keyframes.size > before
                runOnUiThread { finishCapture(fresh) }
            } else {
                maybeCaptureKeyframe(frame, camera)
            }
            else -> Unit
        }
    }

    private fun drawSelfieFrame(s: Session, frame: Frame) {
        when (state) {
            State.AIM -> {
                val face = selfie.findFace(s)
                if (startRequested) {
                    startRequested = false
                    if (face == null) { hint("No face found. Hold the phone at arm's length, face the screen, and tap Start again."); return }
                    selfie.reset()
                    state = State.CAPTURING
                    runOnUiThread { enterCapturing() }
                    hint(selfie.update(s, frame, force = true))
                } else {
                    hint(if (face == null) "Selfie mode: hold the phone at arm's length and look at the screen."
                    else "Face found. Hold the phone still, then tap Start and slowly turn your head.\n\nSelfie mode captures your real face and puts it on a generic head and neck.")
                }
            }
            State.CAPTURING -> {
                val before = selfie.keyframes.size
                if (finishRequested.compareAndSet(true, false)) {
                    selfie.update(s, frame, force = true)
                    val fresh = selfie.keyframes.size > before
                    runOnUiThread { finishCapture(fresh) }
                    return
                }
                hint(selfie.update(s, frame))
                if (selfie.keyframes.size != before) {
                    val snapshot = selfie.coverage.copyOf()
                    val count = selfie.keyframes.size
                    runOnUiThread {
                        coverageView.update(snapshot, crosshair = false)
                        actionButton.isEnabled = count >= SelfieCapture.MIN_KEYFRAMES
                    }
                }
            }
            else -> Unit
        }
    }

    private fun writeJpeg(nv21: ByteArray, w: Int, h: Int, file: File) {
        jpegWriter.execute {
            FileOutputStream(file).use { YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 92, it) }
        }
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display?.rotation ?: Surface.ROTATION_0
        else windowManager.defaultDisplay.rotation

    private fun trackingHint(reason: TrackingFailureReason): String = when (reason) {
        TrackingFailureReason.INSUFFICIENT_LIGHT -> "Too dark – add more light."
        TrackingFailureReason.EXCESSIVE_MOTION -> "Moving too fast – slow down."
        TrackingFailureReason.INSUFFICIENT_FEATURES -> "Can't see enough detail – include more of the room in view."
        TrackingFailureReason.CAMERA_UNAVAILABLE -> "Camera unavailable."
        else -> "Move the phone slowly to start tracking…"
    }

    // ---------------------------------------------------------------- capture logic (GL thread)

    private var lastDistanceMs = 0L
    private var shownDistanceLabel = ""

    /** Rear camera, before Start: the Start button says if the person is too close or too far (middle of the view). */
    private fun showDistance(frame: Frame) {
        val now = SystemClock.uptimeMillis()
        if (now - lastDistanceMs < 300) return
        lastDistanceMs = now
        val d = try {
            frame.acquireDepthImage16Bits().use { img ->
                val w = img.width; val h = img.height
                val depth = ImageUtils.depthToShorts(img)
                val vals = ArrayList<Int>()
                for (y in h / 3 until 2 * h / 3) for (x in w / 3 until 2 * w / 3) {
                    val v = depth[y * w + x].toInt() and 0xFFFF
                    if (v in 100..5000) vals.add(v)
                }
                if (vals.size < 20) 0f else { vals.sort(); vals[vals.size / 4] / 1000f }   // nearer quarter: the head, not the wall
            }
        } catch (e: Exception) { 0f }
        val label = when {
            d <= 0f -> "Start"
            d < MIN_GOOD_DIST -> "Start · too close"
            d > MAX_GOOD_DIST -> "Start · too far"
            else -> "Start · distance OK"
        }
        if (label == shownDistanceLabel) return
        shownDistanceLabel = label
        runOnUiThread { if (state == State.AIM && !imageMode && !selfieMode) actionButton.text = label }
    }

    private fun tryStartCapture(frame: Frame, camera: Camera) {
        val depthImage = try { frame.acquireDepthImage16Bits() } catch (e: NotYetAvailableException) {
            // Depth can take a moment after tracking starts: keep trying for a few seconds before giving up.
            if (SystemClock.uptimeMillis() - startRequestedMs < START_RETRY_MS) {
                startRequested = true
                hint("Waiting for the depth sensor… move the phone very slightly side to side.")
            } else {
                hint("The depth sensor isn't ready yet – move the phone slowly side to side and tap Start again.")
            }
            return
        }
        // No aiming needed: the head is the nearest solid thing in the middle of the view.
        val found = depthImage.use { img ->
            val dw = img.width; val dh = img.height
            ImageUtils.nearestHead(ImageUtils.depthToShorts(img), dw, dh)?.let { Pair(it, ImageUtils.toIntrinsics(camera.textureIntrinsics).scaledTo(dw, dh)) }
        }
        if (found == null) {
            if (SystemClock.uptimeMillis() - startRequestedMs < START_RETRY_MS) {
                startRequested = true
                hint("Looking for the head… point the phone at the person from about half a metre away.")
            } else {
                hint("Couldn't find the head. Stand 30 cm – 1.2 m from the person with their head in view and tap Start again.")
            }
            return
        }
        val (uvd, di) = found
        val d = uvd.third
        val pose = camera.pose
        val t = pose.translation
        // Ray through the found pixel (depth is measured along the camera's view axis).
        val rx0 = (uvd.first - di.cx) / di.fx * d; val ry0 = -(uvd.second - di.cy) / di.fy * d
        val hitCam = floatArrayOf(rx0, ry0, -d)
        val hit = pose.transformPoint(hitCam)
        val fwd0 = floatArrayOf(hit[0] - t[0], hit[1] - t[1], hit[2] - t[2])
        val fl0 = sqrt(fwd0[0] * fwd0[0] + fwd0[1] * fwd0[1] + fwd0[2] * fwd0[2])
        val fwd = floatArrayOf(fwd0[0] / fl0, fwd0[1] / fl0, fwd0[2] / fl0)
        // Push the centre ~10 cm into the head along the horizontal view direction.
        var hx = fwd[0]; var hz = fwd[2]
        val hl = sqrt(hx * hx + hz * hz).coerceAtLeast(1e-4f)
        hx /= hl; hz /= hl
        headCenter = floatArrayOf(hit[0] + hx * HEAD_DEPTH, hit[1] + 0.02f, hit[2] + hz * HEAD_DEPTH)
        frontAngle = atan2(-hx, -hz)
        keyframes.clear(); keyframeDirs.clear(); coverage.fill(false); highViews = 0
        lastCaptureMs = 0
        state = State.CAPTURING
        runOnUiThread { enterCapturing() }
        captureKeyframe(frame, camera)
    }

    private fun maybeCaptureKeyframe(frame: Frame, camera: Camera) {
        val c = headCenter ?: return
        val t = camera.pose.translation
        val dx = t[0] - c[0]; val dy = t[1] - c[1]; val dz = t[2] - c[2]
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        val dir = floatArrayOf(dx / dist, dy / dist, dz / dist)
        val z = camera.pose.zAxis
        // angle between camera forward (-Z) and the direction to the head centre
        val aim = Math.toDegrees(acos((-(z[0] * -dir[0] + z[1] * -dir[1] + z[2] * -dir[2])).coerceIn(-1f, 1f).toDouble()))
        when {
            dist < MIN_DIST -> { hint(captureHint("Too close – step back a little.")); showPosition(dir); return }
            dist > MAX_DIST -> { hint(captureHint("Too far – move closer.")); showPosition(dir); return }
            aim > MAX_AIM_DEG -> { hint(captureHint("Point the phone back at the head.")); showPosition(dir); return }
        }
        hint(captureHint(null))
        showPosition(dir)
        val now = SystemClock.uptimeMillis()
        if (now - lastCaptureMs < MIN_INTERVAL_MS || keyframes.size >= MAX_KEYFRAMES) return
        var minAngle = 180.0
        for (k in keyframeDirs) {
            val dot = (k[0] * dir[0] + k[1] * dir[1] + k[2] * dir[2]).coerceIn(-1f, 1f)
            minAngle = minOf(minAngle, Math.toDegrees(acos(dot.toDouble())))
        }
        if (minAngle >= NEW_VIEW_DEG) captureKeyframe(frame, camera)
    }

    private fun captureHint(problem: String?): String {
        val covered = coverage.count { it }
        val sb = StringBuilder()
        sb.append("Captured ${keyframes.size} views · ${covered * 100 / COVERAGE_BINS}% of the way around\n")
        sb.append(problem ?: when {
            covered < FINISH_BINS -> "Walk slowly around the head, following the yellow arrow. You can finish at three-quarters of the ring."
            covered < COVERAGE_BINS || highViews < 2 -> "That's enough to finish: stand in front of the face and tap Finish.\nOptional: carry on round, or raise the phone over the top of the head, for a better result."
            else -> "All done! Stand in front of the face and tap Finish – a full-resolution photo is taken from there."
        })
        return sb.toString()
    }

    private fun captureKeyframe(frame: Frame, camera: Camera) {
        val c = headCenter ?: return
        val depthImage = try { frame.acquireDepthImage16Bits() } catch (e: NotYetAvailableException) { return }
        val cameraImage = try { frame.acquireCameraImage() } catch (e: NotYetAvailableException) { depthImage.close(); return }
        class Raw(val depth: ShortArray, val dw: Int, val dh: Int, val nv21: ByteArray, val cw: Int, val ch: Int)
        val raw = try {
            if (cameraImage.format != ImageFormat.YUV_420_888) null
            else Raw(
                ImageUtils.depthToShorts(depthImage), depthImage.width, depthImage.height,
                ImageUtils.yuvToNv21(cameraImage), cameraImage.width, cameraImage.height,
            )
        } finally {
            depthImage.close()
            cameraImage.close()
        } ?: return
        val depth = raw.depth; val dw = raw.dw; val dh = raw.dh
        val nv21 = raw.nv21; val cw = raw.cw; val ch = raw.ch
        val camToWorld = FloatArray(16).also { camera.pose.toMatrix(it, 0) }
        val worldToCam = FloatArray(16).also { camera.pose.inverse().toMatrix(it, 0) }
        val depthIntr = ImageUtils.toIntrinsics(camera.textureIntrinsics).scaledTo(dw, dh)
        val colorIntr = ImageUtils.toIntrinsics(camera.imageIntrinsics)

        val file = File(workDir, "kf_%03d.jpg".format(keyframes.size))
        writeJpeg(nv21, cw, ch, file)
        keyframes.add(Keyframe(camToWorld, worldToCam, depth, dw, dh, depthIntr, colorIntr, file.absolutePath, sharpness = Sharpness.of(nv21, cw, ch)))

        val dx = camToWorld[12] - c[0]; val dy = camToWorld[13] - c[1]; val dz = camToWorld[14] - c[2]
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        keyframeDirs.add(floatArrayOf(dx / dist, dy / dist, dz / dist))
        val elevation = Math.toDegrees(Math.asin((dy / dist).toDouble()))
        if (elevation > 35) highViews++
        if (elevation < 60) {
            var az = Math.toDegrees((atan2(dx, dz) - frontAngle).toDouble())
            az = ((az % 360) + 360) % 360
            coverage[((az / 360.0) * COVERAGE_BINS).toInt().coerceIn(0, COVERAGE_BINS - 1)] = true
        }
        lastCaptureMs = SystemClock.uptimeMillis()
        buzz()
        val snapshot = coverage.copyOf()
        val count = keyframes.size
        runOnUiThread {
            coverageView.update(snapshot, crosshair = false)
            actionButton.isEnabled = count >= MIN_KEYFRAMES
        }
    }

    /** A short buzz for each captured view. */
    private fun buzz() {
        val v = getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(android.os.VibrationEffect.createOneShot(18, 120))
            else @Suppress("DEPRECATION") v.vibrate(18)
        } catch (e: Throwable) { }
    }

    /** Moves the "you are here" dot on the ring and points the arrow towards the nearest gap. */
    private fun showPosition(dir: FloatArray) {
        var az = Math.toDegrees((atan2(dir[0], dir[2]) - frontAngle).toDouble())
        az = ((az % 360) + 360) % 360
        val bin = ((az / 360.0) * COVERAGE_BINS).toInt().coerceIn(0, COVERAGE_BINS - 1)
        var way = 0
        if (!coverage[bin] || coverage.count { it } < COVERAGE_BINS) {
            for (step in 0 until COVERAGE_BINS) {
                if (!coverage[(bin + step) % COVERAGE_BINS]) { way = if (step == 0) 0 else 1; break }
                if (!coverage[(bin - step + COVERAGE_BINS) % COVERAGE_BINS]) { way = -1; break }
            }
        }
        if (bin == shownBin && way == shownWay) return
        shownBin = bin; shownWay = way
        runOnUiThread { coverageView.setPosition(bin, way) }
    }
    private var shownBin = -2
    private var shownWay = 0

    // ---------------------------------------------------------------- processing

    /** Finish tapped: grab one last tracked frame on the GL thread, then take the detail photo. */
    private fun requestFinish() {
        val minFrames = if (selfieMode) SelfieCapture.MIN_KEYFRAMES else MIN_KEYFRAMES
        val have = if (selfieMode) selfie.keyframes.size else keyframes.size
        if (have < minFrames) {
            Toast.makeText(this, "Capture at least $minFrames views first.", Toast.LENGTH_SHORT).show()
            return
        }
        actionButton.isEnabled = false
        secondaryButton.isEnabled = false
        finishRequested.set(true)
        // If tracking is lost the GL thread won't get to it: go ahead without the detail photo.
        statusText.postDelayed({ if (finishRequested.compareAndSet(true, false)) finishCapture(false) }, 1500)
    }

    /** @param anchorFresh the last keyframe was taken just now, so the detail photo can be lined up with it. */
    private fun finishCapture(anchorFresh: Boolean) {
        if (state != State.CAPTURING) return
        secondaryButton.isEnabled = true
        state = State.PROCESSING
        surfaceView.onPause()   // blocks until the GL thread is paused, so the keyframe list is stable
        session?.pause()
        val isSelfie = selfieMode
        val frames = ArrayList(if (isSelfie) selfie.keyframes else keyframes)
        enterProcessing()
        if (anchorFresh && frames.isNotEmpty()) {
            showStatus(if (isSelfie) "Taking a sharp photo – hold still and keep looking at the screen…" else "Taking a sharp photo – hold the phone still…")
            DetailPhoto.capture(this, front = isSelfie, cameraId = arCameraId) { shot -> processCapture(frames, shot) }
        } else {
            processCapture(frames, null)
        }
    }

    private fun processCapture(frames: ArrayList<Keyframe>, shot: DetailPhoto.Shot?) {
        val isSelfie = selfieMode
        val center = if (isSelfie) FloatArray(3) else headCenter!!.copyOf()
        val faceVerts = if (isSelfie) selfie.averagedVertices() else null
        val faceTris = if (isSelfie) selfie.triangles else null
        val settings = ScanSettings(targetTriangles = targetTriangles, style = headStyle, hairStyle = hairStyle,
            faceOffsetZ = if (isSelfie) DEFAULT_FACE_Z else 0f)
        lastSettings = settings
        showStatus("Building 3D model…")
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                jpegWriter.submit(Runnable {}).get()   // wait for pending JPEG writes
                var detailNote = ""
                if (shot != null) {
                    runOnUiThread { showStatus("Lining up the sharp photo…") }
                    val detail = try { DetailPhoto.toKeyframe(shot, frames.last(), workDir) } catch (e: Throwable) { null }
                    if (detail != null) frames.add(detail) else detailNote = " (sharp photo skipped: it didn't line up)"
                }
                selfieInput = if (isSelfie && faceVerts != null && faceTris != null) Triple(frames, faceVerts, faceTris) else null
                rearInput = if (!isSelfie) Pair(frames, center) else null
                // Selfie mode: the person's outline in each photo, to measure the head.
                val outlines = if (isSelfie) {
                    runOnUiThread { showStatus("Measuring your head from the photos…") }
                    try { Silhouettes.compute(frames.filter { it.priority <= 1f }) } catch (e: Throwable) { null }
                } else null
                val colorSource = photoSource()
                val onProgress = { msg: String, p: Float ->
                    runOnUiThread { showStatus("Building 3D model$detailNote…\n$msg"); progressBar.progress = (p * 100).toInt() }
                }
                var pipeline = HeadScanPipeline(settings)
                // Rear camera: find the face in the scan photos, so the head model can be fitted and everything below
                // the neck (clothes) left out. Falls back to the plain depth scan if no face is found.
                val rear = if (isSelfie) null else {
                    runOnUiThread { showStatus("Finding the face in the photos…") }
                    try {
                        val found = RearFaces.find(frames)
                        if (found.landmarks.isEmpty()) null else RearFace.build(frames, found.landmarks, found.triangles)
                    } catch (e: Throwable) { null }
                }
                if (!isSelfie && rear == null) detailNote += " (no face found in the photos: plain depth scan)"
                val result = if (isSelfie) {
                    if (faceVerts == null || faceTris == null) throw ScanException("No face mesh was captured.")
                    pipeline.runSelfie(frames, faceVerts, faceTris, colorSource, onProgress, silhouettes = outlines)
                } else if (rear != null) {
                    pipeline = HeadScanPipeline(settings.copy(headShape = rear.shape))
                    selfieInput = Triple(rear.keyframes, rear.faceVertices, rear.faceTriangles)
                    rearInput = null
                    pipeline.runSelfie(rear.keyframes, rear.faceVertices, rear.faceTriangles, colorSource, onProgress)
                } else {
                    pipeline.run(frames, center, colorSource, onProgress)
                }
                // Selfie mode: also save a photo with the face mesh drawn on it, to check the texture lines up.
                val check = if (isSelfie && faceVerts != null) AlignmentCheck.draw(frames.first(), faceVerts) else null
                withContext(Dispatchers.Main) {
                    // Keep the measured head for rebuilds (hairstyle, style), so it isn't measured again.
                    lastSettings = settings.copy(headShape = result.headShape)
                    lastFit = result.headFit
                    enterPreview(result, check, fresh = true)
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    savedUris = emptyList()
                    enterDone("Scan failed: ${e.message ?: e.javaClass.simpleName}\n\nTry again with better light, slower movement and the head kept centred.")
                }
            }
        }
    }

    private fun photoSource() = ColorSource { kf ->
        val bmp = BitmapFactory.decodeFile(kf.colorRef)
            ?: throw ScanException("Could not read captured photo ${kf.colorRef}")
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        RgbImage(bmp.width, bmp.height, px).also { bmp.recycle() }
    }

    /** Rebuilds the selfie head with the head size and hairstyle chosen in the preview. */
    private fun applyEdits() {
        val input = selfieInput ?: return
        val settings = lastSettings.copy(
            hairStyle = editHair,
            style = headStyle,
        )
        state = State.PROCESSING
        actionButton.isEnabled = false
        secondaryButton.isEnabled = false
        applyButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val result = HeadScanPipeline(settings).runSelfie(input.first, input.second, input.third, photoSource(), progress = { msg, p ->
                    runOnUiThread { showStatus("Rebuilding head…\n$msg"); progressBar.progress = (p * 100).toInt() }
                })
                withContext(Dispatchers.Main) {
                    lastSettings = settings
                    secondaryButton.isEnabled = true
                    enterPreview(result, pendingCheck, fresh = false)
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    state = State.PREVIEW
                    progressBar.visibility = View.GONE
                    actionButton.isEnabled = true
                    secondaryButton.isEnabled = true
                    applyButton.isEnabled = true
                    showStatus("Couldn't rebuild: ${e.message}. Try smaller changes.")
                }
            }
        }
    }

    private fun askNameAndSave() {
        val result = pendingResult ?: return
        val input = EditText(this).apply {
            setText(ScanExporter.defaultName())
            setSelectAllOnFocus(true)
            isSingleLine = true
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle("Save head scan")
            .setMessage("Name for this model:")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                save(result.withHairColor(hairHue, hairTone).scaled(shapeSize, shapeSize, shapeSize), input.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
        input.requestFocus()
    }

    private fun save(result: ScanResult, name: String) {
        actionButton.isEnabled = false
        secondaryButton.isEnabled = false
        showStatus("Saving…")
        val check = pendingCheck
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val saved = ScanExporter.save(this@MainActivity, result, name, check)
                withContext(Dispatchers.Main) {
                    pendingCheck = null
                    savedUris = saved.uris
                    secondaryButton.isEnabled = true
                    enterDone("Saved to ${saved.location}\n\n${result.triangleCount} triangles, ${result.textureSize}×${result.textureSize} texture. " +
                        "Keep the .obj, .mtl and .png together when copying them.")
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    actionButton.isEnabled = true
                    secondaryButton.isEnabled = true
                    showStatus("Couldn't save: ${e.message}. Try again.")
                }
            }
        }
    }

    /** Preview editor: size (live), GoldenEye / realistic style and hairstyle; the head rebuilds by itself. */
    private fun setupShapeEditor() {
        editPanel = findViewById(R.id.edit_panel)
        applyButton = findViewById(R.id.edit_apply)
        editHairGroup = findViewById(R.id.edit_hair_group)
        editHairSpinner = findViewById(R.id.edit_hair_spinner)
        editPost = findViewById(R.id.edit_postprocess)
        editBars = listOf(findViewById(R.id.edit_size))
        val label = findViewById<TextView>(R.id.edit_size_label)
        editBars[0].setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val t = (progress - 50) / 50f
                shapeSize = 1f + t * 0.5f            // 50%..150%
                label.text = "Size ${(shapeSize * 100).toInt()}%"
                previewView.setTransform(shapeSize, 0f, 0f, 0f)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        val postAdapter = android.widget.ArrayAdapter(this, R.layout.spinner_item, listOf("None", "GoldenEye: Source style"))
        postAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        editPost.adapter = postAdapter
        editPost.setSelection(0, false)
        editPost.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val style = if (position == 1) HeadStyle.GOLDENEYE_SOURCE else HeadStyle.REALISTIC
                if (style != headStyle) {
                    headStyle = style
                    if (!suppressEditEvents) markDirty()
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        setupHairSpinner(editHairSpinner, HairStyle.NONE) { h ->
            if (h != editHair) {
                editHair = h
                if (!suppressEditEvents) markDirty()
            }
        }
        applyButton.setOnClickListener { applyEdits() }
        findViewById<View>(R.id.edit_reset).setOnClickListener { editBars[0].progress = 50 }
        resetShape(HairStyle.NONE)
    }

    /** The two vertical sliders at the side of the preview: hair colour (rainbow) and tone (dark to light). */
    private fun setupHairColorSliders() {
        colorSliders = findViewById(R.id.color_sliders)
        val hue = findViewById<SeekBar>(R.id.hair_hue)
        val tone = findViewById<SeekBar>(R.id.hair_tone)
        val hueLabel = findViewById<TextView>(R.id.hair_hue_label)
        val toneLabel = findViewById<TextView>(R.id.hair_tone_label)
        val density = resources.displayMetrics.density
        // Track: photo colour, then natural shades (black to white), then the rainbow — drawn by sampling
        // the slider evenly, so each colour sits where it will be picked.
        val rainbow = IntArray(60) { k ->
            val pos = (k + 0.5f) / 60f * hue.max
            val h = hueFor(pos.toInt(), hue.max)
            when {
                h == null -> 0xFF6A6A6A.toInt()
                h >= com.headscan.core.HairColor.NATURAL -> com.headscan.core.HairColor.naturalShade(h - com.headscan.core.HairColor.NATURAL)
                else -> com.headscan.core.HairColor.hsv(h, 0.9f, 0.95f)
            }
        }
        hue.progressDrawable = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, rainbow,
        ).apply { cornerRadius = 4 * density; setSize(0, (8 * density).toInt()) }
        tone.progressDrawable = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF111111.toInt(), 0xFF888888.toInt(), 0xFFF2F2F2.toInt()),
        ).apply { cornerRadius = 4 * density; setSize(0, (8 * density).toInt()) }
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                hairHue = hueFor(hue.progress, hue.max)
                hairTone = (tone.progress - 50) / 50f
                hueLabel.text = when {
                    hairHue == null -> "Photo"
                    hairHue!! >= com.headscan.core.HairColor.NATURAL -> "Natural"
                    else -> "Rainbow"
                }
                toneLabel.text = "Tone"
                if (fromUser) refreshHairColor()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        }
        hue.setOnSeekBarChangeListener(listener)
        tone.setOnSeekBarChangeListener(listener)
        findViewById<View>(R.id.edit_reset).setOnLongClickListener {
            hue.progress = 0; tone.progress = 50; refreshHairColor(); true
        }
        hueLabel.text = "Photo"
    }

    /** Colour slider position: photo colour, then natural shades, then rainbow hues (see [HairColor.target]). */
    private fun hueFor(progress: Int, max: Int): Float? {
        val f = progress.toFloat() / max
        return when {
            f < HUE_PHOTO_END -> null
            f < HUE_NATURAL_END -> com.headscan.core.HairColor.NATURAL + (f - HUE_PHOTO_END) / (HUE_NATURAL_END - HUE_PHOTO_END)
            else -> (f - HUE_NATURAL_END) / (1f - HUE_NATURAL_END) * 360f
        }
    }

    /** Recolours the preview texture in the background; only the latest slider position is applied. */
    private fun refreshHairColor() {
        val base = pendingResult ?: return
        if (!base.canRecolorHair) return
        val hue = hairHue; val tone = hairTone
        recolorJob?.cancel()
        recolorJob = lifecycleScope.launch(Dispatchers.Default) {
            val recolored = base.withHairColor(hue, tone)
            if (kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive == true) {
                previewView.replaceTexture(recolored.texture, recolored.textureSize)
            }
        }
    }

    /** "Select capture method:" – Front (selfie), Rear (ARCore) or Image (a picture from the library). */
    private fun setupModeSpinner(spinner: android.widget.Spinner) {
        val labels = listOf("Front (selfie)", "Rear (ARCore)", "Image")
        val adapter = android.widget.ArrayAdapter(this, R.layout.spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(if (imageMode) 2 else if (selfieMode) 0 else 1, false)
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (state != State.AIM) return
                val wantImage = position == 2
                prefs.edit().putBoolean("image", wantImage).apply()
                if (wantImage) {
                    if (imageMode) return
                    imageMode = true
                    surfaceView.onPause(); session?.pause()
                    hairGroup.visibility = View.VISIBLE
                    lastHint = ""
                    enterAim()
                    return
                }
                val wantSelfie = position == 0
                val wasImage = imageMode
                imageMode = false
                if (wantSelfie != selfieMode) switchCamera(wantSelfie)
                else if (wasImage) { lastHint = ""; enterAim(); startArSession() }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    /** Image mode: finds the face in the chosen picture and builds the head from it. */
    private fun processImage(uri: android.net.Uri) {
        if (state == State.PROCESSING || state == State.CAPTURING) return
        if (state != State.AIM) resetScan()
        state = State.PROCESSING
        enterProcessing()
        showStatus("Finding the face in the picture…")
        val settings = ScanSettings(targetTriangles = targetTriangles, style = headStyle, hairStyle = hairStyle)
        lastSettings = settings
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                // Wait for the head model if it's still loading.
                var waited = 0
                while (HeadModel.default == null && waited < 50) { kotlinx.coroutines.delay(100); waited++ }
                val face = PhotoImport.load(this@MainActivity, uri, workDir) { Breadcrumb.set(this@MainActivity, it) }
                Breadcrumb.set(this@MainActivity, "building the head from the picture")
                val frames = listOf(face.keyframe)
                selfieInput = Triple(frames, face.vertices, face.triangles)
                rearInput = null
                val result = HeadScanPipeline(settings).runSelfie(frames, face.vertices, face.triangles, photoSource(), { msg, p ->
                    runOnUiThread { showStatus("Building 3D model from the picture…\n$msg"); progressBar.progress = (p * 100).toInt() }
                })
                val check = AlignmentCheck.draw(face.keyframe, face.vertices)
                Breadcrumb.clear(this@MainActivity)
                withContext(Dispatchers.Main) {
                    lastSettings = settings.copy(headShape = result.headShape)
                    lastFit = result.headFit
                    enterPreview(result, check, fresh = true)
                }
            } catch (e: Throwable) {
                Breadcrumb.clear(this@MainActivity)
                withContext(Dispatchers.Main) {
                    selfieInput = null
                    savedUris = emptyList()
                    enterDone("Couldn't build a head from that picture: ${e.message ?: e.javaClass.simpleName}\n\nUse a clear, well-lit photo of one face looking at the camera.")
                }
            }
        }
    }

    private fun setupHairSpinner(spinner: android.widget.Spinner, initial: HairStyle, onPick: (HairStyle) -> Unit) {
        val adapter = android.widget.ArrayAdapter(this, R.layout.spinner_item, HairStyle.values().map { hairLabel(it) })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(HairStyle.values().indexOf(initial))
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                onPick(HairStyle.values()[position])
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun hairLabel(h: HairStyle) = when (h) {
        HairStyle.NONE -> "No hair"
        HairStyle.SIDE_PART -> "Side parting"
        HairStyle.BUZZ -> "Buzz cut"
        HairStyle.MOHICAN -> "Mohican"
        HairStyle.CURTAINS -> "Curtains"
        HairStyle.SPIKY -> "Spiky"
    }

    private fun defaultProgress(@Suppress("UNUSED_PARAMETER") k: Int): Int = 50

    /** A head size or hairstyle change: rebuild the head shortly after the slider stops, no Apply needed. */
    private fun markDirty() {
        editDirty = true
        if (state != State.PREVIEW || (selfieInput == null && rearInput == null)) return
        actionButton.isEnabled = false
        statusText.removeCallbacks(liveRebuild)
        statusText.postDelayed(liveRebuild, 300)
    }

    private var rebuildJob: kotlinx.coroutines.Job? = null
    private var rebuildQueued = false
    private val liveRebuild = Runnable { startLiveRebuild() }

    /** Rebuilds the selfie head in the background while the current model stays on screen, then swaps it in. */
    private fun startLiveRebuild() {
        if (state != State.PREVIEW) return
        if (rebuildJob != null) { rebuildQueued = true; return }
        val input = selfieInput
        val rear = rearInput
        if (input == null && rear == null) return
        val settings = lastSettings.copy(
            hairStyle = editHair,
            style = headStyle,
        )
        editDirty = false
        actionButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        rebuildJob = lifecycleScope.launch(Dispatchers.Default) {
            var error: String? = null
            try {
                val onProgress = { msg: String, p: Float ->
                    runOnUiThread { if (state == State.PREVIEW) { showStatus("Updating head…\n$msg"); progressBar.progress = (p * 100).toInt() } }
                }
                val result = if (input != null) HeadScanPipeline(settings).runSelfie(input.first, input.second, input.third, photoSource(), onProgress)
                    else HeadScanPipeline(settings).run(rear!!.first, rear.second, photoSource(), onProgress)
                withContext(Dispatchers.Main) {
                    if (state == State.PREVIEW) {
                        lastSettings = settings
                        pendingResult = result
                        previewView.show(result)
                        previewView.setTransform(shapeSize, 0f, 0f, 0f)
                        colorSliders.visibility = if (result.canRecolorHair) View.VISIBLE else View.GONE
                        refreshHairColor()
                    }
                }
            } catch (e: Throwable) {
                error = e.message ?: e.javaClass.simpleName
            }
            withContext(Dispatchers.Main) {
                if (state != State.PREVIEW) { rebuildJob = null; rebuildQueued = false; return@withContext }
                rebuildJob = null      // this job is finishing; let the next rebuild start
                if (rebuildQueued) {
                    rebuildQueued = false
                    startLiveRebuild()
                } else {
                    progressBar.visibility = View.GONE
                    actionButton.isEnabled = true
                    showStatus(if (error != null) "Couldn't update: $error. Try a smaller move." else previewText(pendingResult))
                }
            }
        }
    }

    /** Puts every editor control back to neutral (used for a brand-new scan result). */
    private fun resetShape(hair: HairStyle) {
        suppressEditEvents = true
        editBars.forEachIndexed { k, b -> b.progress = defaultProgress(k) + 1; b.progress = defaultProgress(k) }   // force change events so labels update
        editHair = hair
        editHairSpinner.setSelection(HairStyle.values().indexOf(hair))
        editPost.setSelection(if (headStyle == HeadStyle.GOLDENEYE_SOURCE) 1 else 0, false)
        suppressEditEvents = false
        editDirty = false
        applyButton.isEnabled = false
    }

    private fun confirmDiscard() {
        AlertDialog.Builder(this)
            .setTitle("Discard this scan?")
            .setMessage("The model will not be saved.")
            .setPositiveButton("Discard") { _, _ -> resetScan() }
            .setNegativeButton("Keep", null)
            .show()
    }

    /** Pads the on-screen controls so they clear the status bar and Android's navigation buttons. */
    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.root)
        val bottomPanel = findViewById<View>(R.id.bottom_panel)
        val version = findViewById<View>(R.id.version_text)
        val topPanel = findViewById<View>(R.id.top_panel)
        val base = (16 * resources.displayMetrics.density).toInt()
        val versionBase = (version.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            (topPanel.layoutParams as ViewGroup.MarginLayoutParams).topMargin = base + bars.top
            (bottomPanel.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = base + bars.bottom
            (version.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = versionBase + bars.bottom
            topPanel.requestLayout(); bottomPanel.requestLayout(); version.requestLayout()
            insets
        }
    }

    private fun shareResult() {
        if (savedUris.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(savedUris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share head scan"))
    }

    // ---------------------------------------------------------------- UI states

    private fun enterAim() {
        state = State.AIM
        editPanel.visibility = View.GONE
        colorSliders.visibility = View.GONE
        optionsPanel.visibility = View.VISIBLE
        progressBar.visibility = View.GONE
        secondaryButton.visibility = View.GONE
        actionButton.text = if (imageMode) "Choose photo" else "Start"
        shownDistanceLabel = ""
        actionButton.isEnabled = true
        if (imageMode) showStatus(IMAGE_HINT)
        coverageView.update(BooleanArray(0), crosshair = false)
        coverageView.setPosition(-1, 0); shownBin = -2
    }

    private fun enterCapturing() {
        optionsPanel.visibility = View.GONE
        actionButton.text = "Finish"
        actionButton.isEnabled = false
        secondaryButton.isEnabled = true
        secondaryButton.text = "Cancel"
        secondaryButton.visibility = View.VISIBLE
        coverageView.update(if (selfieMode) selfie.coverage.copyOf() else coverage.copyOf(), crosshair = false)
    }

    private fun enterProcessing() {
        optionsPanel.visibility = View.GONE
        actionButton.isEnabled = false
        secondaryButton.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        coverageView.update(BooleanArray(0), crosshair = false)
        coverageView.setPosition(-1, 0); shownBin = -2
        showStatus("Building 3D model…")
    }

    private fun enterPreview(result: ScanResult, check: android.graphics.Bitmap?, fresh: Boolean) {
        state = State.PREVIEW
        pendingResult = result
        pendingCheck = check
        optionsPanel.visibility = View.GONE
        progressBar.visibility = View.GONE
        surfaceView.visibility = View.INVISIBLE
        previewView.visibility = View.VISIBLE
        previewView.onResume()
        previewView.show(result)
        previewView.setTransform(shapeSize, 0f, 0f, 0f)
        if (fresh) resetShape(lastSettings.hairStyle)
        editDirty = false
        applyButton.isEnabled = false
        val selfieEdits = selfieInput != null
        applyMinimisedSliders()
        editHairGroup.visibility = if (selfieEdits) View.VISIBLE else View.GONE
        applyButton.visibility = View.GONE   // edits apply live
        editPanel.visibility = View.VISIBLE
        colorSliders.visibility = if (result.canRecolorHair) View.VISIBLE else View.GONE
        if (fresh) {
            findViewById<SeekBar>(R.id.hair_hue).progress = 0
            findViewById<SeekBar>(R.id.hair_tone).progress = 50
            hairHue = null; hairTone = 0f
        } else refreshHairColor()
        actionButton.text = "Save"
        actionButton.isEnabled = true
        secondaryButton.text = "Discard"
        secondaryButton.visibility = View.VISIBLE
        showStatus(previewText(result))
    }

    /** Settings page (the ⋮ button): head style. Changes apply to the next scan. */
    private fun showSettings() {
        val d = resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(px(22), px(8), px(22), px(8))
        }
        fun header(text: String) = TextView(this).apply {
            this.text = text
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, px(14), 0, px(4))
        }
        box.addView(header("Head style"))
        val styles = RadioGroup(this)
        val realistic = android.widget.RadioButton(this).apply { id = View.generateViewId(); text = "Realistic" }
        val goldeneye = android.widget.RadioButton(this).apply { id = View.generateViewId(); text = "GoldenEye: Source" }
        styles.addView(realistic); styles.addView(goldeneye)
        styles.check(if (headStyle == HeadStyle.GOLDENEYE_SOURCE) goldeneye.id else realistic.id)
        box.addView(styles)

        box.addView(TextView(this).apply {
            text = "Also on the preview screen, where it updates the head straight away."
            textSize = 12f
            alpha = 0.7f
            setPadding(0, px(10), 0, 0)
        })
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Done") { _, _ ->
                headStyle = if (styles.checkedRadioButtonId == goldeneye.id) HeadStyle.GOLDENEYE_SOURCE else HeadStyle.REALISTIC
                prefs.edit().putString("style", headStyle.name).apply()
                if (::editPost.isInitialized) editPost.setSelection(if (headStyle == HeadStyle.GOLDENEYE_SOURCE) 1 else 0, false)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** "Head 15.4 × 21.0 × 19.6 cm, measured from your photos." (or the average-proportions fallback). */
    private fun headSizeText(result: ScanResult?): String {
        val sz = result?.headSize ?: return ""
        val fit = result.headFit ?: lastFit
        fun cm(m: Float) = "%.1f".format(m * 100)
        val dims = "Head ${cm(sz[0])} × ${cm(sz[1])} × ${cm(sz[2])} cm (width × height × depth)"
        return when {
            fit != null && fit.depthMeasured -> "$dims, measured from your photos.\n"
            fit != null -> "$dims, measured from your photos (depth estimated: turn your head further next time).\n"
            else -> "$dims, average proportions: your outline couldn't be found in the photos (try a plain background).\n"
        }
    }

    private fun previewText(result: ScanResult?): String {
        val style = if (headStyle == HeadStyle.GOLDENEYE_SOURCE) "GoldenEye: Source style" else "No post process"
        return "Preview · ${result?.triangleCount ?: 0} triangles · ${result?.textureSize ?: 0}px texture · $style\nDrag to spin. " +
            (if (selfieInput != null) headSizeText(result) + "Change the hairstyle or style and the head updates by itself. " +
                "The side sliders change hair colour and tone. " else "Pick a post process and the head updates by itself. ") +
            "Save it with a name, or discard it."
    }

    private fun enterDone(message: String) {
        editPanel.visibility = View.GONE
        colorSliders.visibility = View.GONE
        optionsPanel.visibility = View.GONE
        state = State.DONE
        progressBar.visibility = View.GONE
        actionButton.text = "New scan"
        actionButton.isEnabled = true
        secondaryButton.text = "Share"
        secondaryButton.visibility = if (savedUris.isEmpty()) View.GONE else View.VISIBLE
        showStatus(message)
    }

    private fun resetScan() {
        val wasRunning = state == State.CAPTURING || state == State.AIM
        statusText.removeCallbacks(liveRebuild)
        rebuildQueued = false
        rebuildJob?.cancel(); rebuildJob = null
        finishRequested.set(false)
        secondaryButton.isEnabled = true
        if (wasRunning) surfaceView.onPause()
        pendingResult = null
        pendingCheck?.recycle(); pendingCheck = null
        selfieInput = null
        rearInput = null
        previewView.onPause()
        previewView.visibility = View.GONE
        surfaceView.visibility = View.VISIBLE
        keyframes.clear(); keyframeDirs.clear(); coverage.fill(false)
        selfie.reset()
        headCenter = null
        workDir.listFiles()?.forEach { it.delete() }
        lastHint = ""
        enterAim()
        if (imageMode) { if (wasRunning) surfaceView.onPause(); session?.pause(); return }
        if (wasRunning) {
            surfaceView.onResume()
        } else {
            try { session?.resume() } catch (e: CameraNotAvailableException) { showFatal("The camera is in use by another app."); return }
            surfaceView.onResume()
        }
    }

    private fun hint(text: String) {
        if (text == lastHint) return
        lastHint = text
        runOnUiThread { if (state == State.AIM || state == State.CAPTURING) statusText.text = text }
    }

    private fun showStatus(text: String) { statusText.text = text }

    /** Minimise: instructions shrink to one line and the size sliders fold away, leaving more of the view free. */
    private fun setMinimised(on: Boolean, button: TextView) {
        minimised = on
        button.text = if (on) "+" else "—"
        button.contentDescription = if (on) "Expand" else "Minimise"
        statusText.maxLines = if (on) 1 else Int.MAX_VALUE
        statusText.ellipsize = if (on) android.text.TextUtils.TruncateAt.END else null
        statusText.textSize = if (on) 13f else 16f
        applyMinimisedSliders()
    }

    private fun applyMinimisedSliders() {
        if (!::editBars.isInitialized) return
        editBars.forEachIndexed { k, b ->
            // Each slider sits in its own row (the head rows hold an inner line with the − / + buttons).
            var row = b.parent as View
            if (row.parent !== editPanel) row = row.parent as View
            val show = !minimised
            row.visibility = if (show) View.VISIBLE else View.GONE
        }
    }

    /**
     * If the app crashes, the error is written to a file and shown the next time it opens, so a problem on the
     * phone can be reported (and fixed) instead of the app just disappearing.
     */
    private fun installCrashLog() {
        val file = File(filesDir, "last_crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { file.writeText("v${BuildConfig.VERSION_NAME}\n" + android.util.Log.getStackTraceString(e).take(3000)) } catch (_: Throwable) {}
            previous?.uncaughtException(t, e)
        }
        val step = Breadcrumb.takeReport(this)
        if (file.exists() || step != null) {
            val text = (if (file.exists()) try { file.readText() } catch (_: Throwable) { "" } else "") + (step?.let { "\n$it" } ?: "")
            file.delete()
            if (text.isNotBlank()) window.decorView.post {
                android.app.AlertDialog.Builder(this).setTitle("The app crashed last time")
                    .setMessage("Please send a screenshot of this so it can be fixed:\n\n" + text.take(1200))
                    .setPositiveButton("OK", null).show()
            }
        }
    }

    private fun showFatal(text: String) {
        statusText.text = text
        actionButton.isEnabled = false
    }

    companion object {
        private const val REQUEST_CAMERA = 1
        private const val IMAGE_HINT = "Image mode: tap Choose photo and pick a clear, well-lit picture of one face looking at the camera. " +
            "The face comes from the picture; the rest of the head is predicted by the head-shape model."
        private const val COVERAGE_BINS = 24
        private const val HEAD_DEPTH = 0.10f       // nose tip to head centre, metres
        private const val MIN_DIST = 0.2f
        /** Best range for the rear scan (shown on the Start button). */
        private const val MIN_GOOD_DIST = 0.3f
        private const val MAX_GOOD_DIST = 0.9f
        private const val MAX_DIST = 1.3f
        private const val MAX_AIM_DEG = 45.0
        /** Ring sections needed before the hint says it's OK to finish (three-quarters). */
        private const val FINISH_BINS = 18
        private const val NEW_VIEW_DEG = 9.0
        private const val MIN_INTERVAL_MS = 250L
        private const val MAX_KEYFRAMES = 60
        private const val MIN_KEYFRAMES = 8
        /** How far the face can be moved on the head: ±2 cm sideways, ±3 cm up/down, ±2 cm in/out. */
        /** Hair colour slider positions below this mean "natural colour". */
        private const val HUE_PHOTO_END = 0.03f
        private const val HUE_NATURAL_END = 0.5f
        private const val START_RETRY_MS = 15000L
        /** New selfie heads start with the face 20 mm in. */
        private const val DEFAULT_FACE_Z = 0f      // the skull is now placed from the forehead depth
        private const val LOW_TRIANGLES = 1500
        private const val HIGH_TRIANGLES = 3500
    }
}

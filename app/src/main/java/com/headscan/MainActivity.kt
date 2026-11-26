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
    private var targetTriangles = LOW_TRIANGLES
    private lateinit var selfie: SelfieCapture
    private var headStyle = HeadStyle.REALISTIC
    private var hairStyle = HairStyle.SWEPT
    private lateinit var hairGroup: View
    private lateinit var editPanel: View
    private lateinit var editBars: List<SeekBar>
    private var shapeSize = 1f
    private val faceOffset = floatArrayOf(0f, 0f, 0f)        // face position on the head: X (right), Y (up), Z (forward), metres
    private var editHair = HairStyle.BUZZ
    private var editDirty = false
    private var suppressEditEvents = false
    private lateinit var applyButton: Button
    private lateinit var editHairGroup: View
    private lateinit var editHairSpinner: android.widget.Spinner
    // Kept after processing so the preview can rebuild the selfie head with new face position / hair.
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
        headStyle = if (prefs.getString("style", "") == HeadStyle.GOLDENEYE_SOURCE.name) HeadStyle.GOLDENEYE_SOURCE else HeadStyle.REALISTIC
        hairGroup = findViewById(R.id.hair_group)
        hairStyle = HairStyle.values().firstOrNull { it.name == prefs.getString("hair", "") } ?: HairStyle.SWEPT
        val hairSpinner = findViewById<android.widget.Spinner>(R.id.hair_spinner)
        setupHairSpinner(hairSpinner, hairStyle) { h ->
            hairStyle = h
            prefs.edit().putString("hair", h.name).apply()
        }
        hairGroup.visibility = if (selfieMode) View.VISIBLE else View.GONE
        setupShapeEditor()
        setupHairColorSliders()
        findViewById<View>(R.id.settings_button).setOnClickListener { showSettings() }

        selfieMode = prefs.getBoolean("selfie", false)
        targetTriangles = prefs.getInt("triangles", LOW_TRIANGLES)
        val modeGroup = findViewById<RadioGroup>(R.id.mode_group)
        val qualityGroup = findViewById<RadioGroup>(R.id.quality_group)
        modeGroup.check(if (selfieMode) R.id.mode_selfie else R.id.mode_rear)
        qualityGroup.check(if (targetTriangles == HIGH_TRIANGLES) R.id.quality_high else R.id.quality_low)
        modeGroup.setOnCheckedChangeListener { _, id ->
            val newSelfie = id == R.id.mode_selfie
            if (newSelfie != selfieMode) switchCamera(newSelfie)
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
                State.AIM -> { startRequestedMs = SystemClock.uptimeMillis(); lastHint = ""; startRequested = true; showStatus(if (selfieMode) "Looking for your face…" else "Measuring distance to the face…") }
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
        startArSession()
    }

    /** Switches between the rear (full head, depth) and front (selfie, face mesh) camera. */
    private fun switchCamera(useSelfie: Boolean) {
        surfaceView.onPause()
        session?.pause()
        session?.close()
        session = null
        selfieMode = useSelfie
        hairGroup.visibility = if (useSelfie) View.VISIBLE else View.GONE
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
                hint("Point the camera at the face from 30–80 cm, put the crosshair on the nose, then tap Start.\n\nThe person being scanned must keep perfectly still.")
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

    private fun tryStartCapture(frame: Frame, camera: Camera) {
        val depthImage = try { frame.acquireDepthImage16Bits() } catch (e: NotYetAvailableException) {
            // Depth can take a moment after tracking starts: keep trying for a few seconds before giving up.
            if (SystemClock.uptimeMillis() - startRequestedMs < START_RETRY_MS) {
                startRequested = true
                hint("Measuring distance to the face… move the phone very slightly side to side.")
            } else {
                hint("Depth not ready yet – move the phone slowly side to side and tap Start again.")
            }
            return
        }
        val d = depthImage.use { ImageUtils.centerDepth(ImageUtils.depthToShorts(it), it.width, it.height) }
        if (d < 0.2f || d > 1.2f) {
            hint("Couldn't measure the face (got ${"%.2f".format(d)} m). Stay 30–80 cm away with the crosshair on the nose and tap Start again.")
            return
        }
        val pose = camera.pose
        val t = pose.translation
        val z = pose.zAxis
        val fwd = floatArrayOf(-z[0], -z[1], -z[2])
        val hit = floatArrayOf(t[0] + fwd[0] * d, t[1] + fwd[1] * d, t[2] + fwd[2] * d)
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
            dist < MIN_DIST -> { hint(captureHint("Too close – step back a little.")); return }
            dist > MAX_DIST -> { hint(captureHint("Too far – move closer.")); return }
            aim > MAX_AIM_DEG -> { hint(captureHint("Keep the head in the middle of the screen.")); return }
        }
        hint(captureHint(null))
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
            covered < COVERAGE_BINS -> "Walk slowly around the head, keeping it centred. Fill the ring."
            highViews < 4 -> "Great! Now raise the phone and look down at the top of the head."
            else -> "Done? Stand in front of the face and tap Finish – a full-resolution photo is taken from there."
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
        val snapshot = coverage.copyOf()
        val count = keyframes.size
        runOnUiThread {
            coverageView.update(snapshot, crosshair = true)
            actionButton.isEnabled = count >= MIN_KEYFRAMES
        }
    }

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
                val colorSource = photoSource()
                val onProgress = { msg: String, p: Float ->
                    runOnUiThread { showStatus("Building 3D model$detailNote…\n$msg"); progressBar.progress = (p * 100).toInt() }
                }
                val pipeline = HeadScanPipeline(settings)
                val result = if (isSelfie) {
                    if (faceVerts == null || faceTris == null) throw ScanException("No face mesh was captured.")
                    pipeline.runSelfie(frames, faceVerts, faceTris, colorSource, onProgress)
                } else {
                    pipeline.run(frames, center, colorSource, onProgress)
                }
                // Selfie mode: also save a photo with the face mesh drawn on it, to check the texture lines up.
                val check = if (isSelfie && faceVerts != null) AlignmentCheck.draw(frames.first(), faceVerts) else null
                withContext(Dispatchers.Main) { enterPreview(result, check, fresh = true) }
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

    /** Rebuilds the selfie head with the face position and hairstyle chosen in the preview. */
    private fun applyEdits() {
        val input = selfieInput ?: return
        val settings = lastSettings.copy(
            hairStyle = editHair,
            faceOffsetX = faceOffset[0], faceOffsetY = faceOffset[1], faceOffsetZ = faceOffset[2],
        )
        state = State.PROCESSING
        actionButton.isEnabled = false
        secondaryButton.isEnabled = false
        applyButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val result = HeadScanPipeline(settings).runSelfie(input.first, input.second, input.third, photoSource()) { msg, p ->
                    runOnUiThread { showStatus("Rebuilding head…\n$msg"); progressBar.progress = (p * 100).toInt() }
                }
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

    /** Preview editor: size (live), face position on the head and hairstyle (Selfie mode, rebuilt with Apply). */
    private fun setupShapeEditor() {
        editPanel = findViewById(R.id.edit_panel)
        applyButton = findViewById(R.id.edit_apply)
        editHairGroup = findViewById(R.id.edit_hair_group)
        editHairSpinner = findViewById(R.id.edit_hair_spinner)
        val ids = listOf(R.id.edit_size, R.id.edit_width, R.id.edit_height, R.id.edit_depth)
        val labels = listOf(R.id.edit_size_label, R.id.edit_width_label, R.id.edit_height_label, R.id.edit_depth_label)
        val names = listOf("Size", "Face ←→", "Face ↑↓", "Face in/out")
        editBars = ids.map { findViewById(it) }
        // Face sliders: one step = 1 mm, each on its own full-width row with big − / + buttons either side.
        for (k in 1..3) {
            editBars[k].max = Math.round(2 * FACE_MOVE[k - 1] * 1000)
            enlargeFaceRow(editBars[k], findViewById(labels[k]))
        }
        editBars.forEachIndexed { i, bar ->
            val label = findViewById<TextView>(labels[i])
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    val half = sb.max / 2f
                    val t = (progress - half) / half          // -1..1
                    if (i == 0) {
                        shapeSize = 1f + t * 0.5f            // 50%..150%
                        label.text = "Size ${(shapeSize * 100).toInt()}%"
                        previewView.setTransform(shapeSize, 0f, 0f, 0f)
                    } else {
                        faceOffset[i - 1] = t * FACE_MOVE[i - 1]
                        val mm = Math.round(faceOffset[i - 1] * 1000)
                        label.text = "${names[i]} ${if (mm > 0) "+" else ""}$mm mm"
                        if (!suppressEditEvents) markDirty()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        setupHairSpinner(editHairSpinner, HairStyle.BUZZ) { h ->
            if (h != editHair) {
                editHair = h
                if (!suppressEditEvents) markDirty()
            }
        }
        applyButton.setOnClickListener { applyEdits() }
        findViewById<View>(R.id.edit_reset).setOnClickListener {
            editBars[0].progress = 50
            for (k in 1..3) editBars[k].progress = defaultProgress(k)
        }
    }

    /**
     * Rebuilds a face slider row as: label on top, then [−] slider [+] filling the width.
     * Tapping − / + moves the face 1 mm; holding keeps moving it.
     */
    private fun enlargeFaceRow(bar: SeekBar, label: TextView) {
        val row = bar.parent as android.widget.LinearLayout
        val d = resources.displayMetrics.density
        row.removeView(bar)
        row.orientation = android.widget.LinearLayout.VERTICAL
        label.layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
        label.setPadding(0, (6 * d).toInt(), 0, 0)
        val line = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        fun stepButton(text: String, delta: Int) = Button(this).apply {
            this.text = text
            textSize = 22f
            minWidth = 0; minimumWidth = 0
            setPadding(0, 0, 0, 0)
            layoutParams = android.widget.LinearLayout.LayoutParams((56 * d).toInt(), (48 * d).toInt())
            val repeat = object : Runnable {
                override fun run() {
                    bar.progress = (bar.progress + delta).coerceIn(0, bar.max)
                    postDelayed(this, 90)
                }
            }
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        bar.progress = (bar.progress + delta).coerceIn(0, bar.max)
                        v.isPressed = true
                        v.postDelayed(repeat, 450)
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        v.removeCallbacks(repeat)
                        if (ev.actionMasked == android.view.MotionEvent.ACTION_UP) v.performClick()
                    }
                }
                true
            }
        }
        bar.layoutParams = android.widget.LinearLayout.LayoutParams(0, (48 * d).toInt(), 1f)
        bar.setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
        line.addView(stepButton("−", -1))
        line.addView(bar)
        line.addView(stepButton("+", +1))
        row.addView(line)
        resetShape(HairStyle.BUZZ)
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
        HairStyle.CLASSIC_PART -> "Classic side part"
        HairStyle.CLASSIC_SLICK -> "Classic slicked back"
        HairStyle.CLASSIC_TOUSLED -> "Classic tousled"
        HairStyle.SWEPT -> "Swept locks"
        HairStyle.SPIKY -> "Spiky"
        HairStyle.SLICKED_BACK -> "Slicked back"
        HairStyle.CURTAINS -> "Curtains"
        HairStyle.FAUX_HAWK -> "Faux hawk"
        HairStyle.LONG_LOCKS -> "Long locks"
        HairStyle.BUZZ -> "Buzz cut"
        HairStyle.CREW_CUT -> "Crew cut"
        HairStyle.SHORT -> "Short hair"
        HairStyle.MOHAWK -> "Mohawk"
        HairStyle.AFRO -> "Afro"
        HairStyle.SIDE_PART -> "Side parting"
        HairStyle.FLAT_TOP -> "Flat top"
        HairStyle.BOB -> "Bob"
        HairStyle.BUN -> "Bun"
    }

    /** Slider position for each editor control's default (face starts [DEFAULT_FACE_Z] in). */
    private fun defaultProgress(k: Int): Int {
        if (k == 0) return 50
        val half = editBars[k].max / 2
        return if (k == 3) half + Math.round(DEFAULT_FACE_Z * 1000) else half
    }

    /** A face position or hairstyle change: rebuild the head shortly after the slider stops, no Apply needed. */
    private fun markDirty() {
        editDirty = true
        if (state != State.PREVIEW || selfieInput == null) return
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
        val input = selfieInput ?: return
        val settings = lastSettings.copy(
            hairStyle = editHair,
            faceOffsetX = faceOffset[0], faceOffsetY = faceOffset[1], faceOffsetZ = faceOffset[2],
        )
        editDirty = false
        actionButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        rebuildJob = lifecycleScope.launch(Dispatchers.Default) {
            var error: String? = null
            try {
                val result = HeadScanPipeline(settings).runSelfie(input.first, input.second, input.third, photoSource()) { msg, p ->
                    runOnUiThread { if (state == State.PREVIEW) { showStatus("Updating head…\n$msg"); progressBar.progress = (p * 100).toInt() } }
                }
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
        val base = (16 * resources.displayMetrics.density).toInt()
        val versionBase = (version.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            (statusText.layoutParams as ViewGroup.MarginLayoutParams).topMargin = base + bars.top
            (bottomPanel.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = base + bars.bottom
            (version.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = versionBase + bars.bottom
            statusText.requestLayout(); bottomPanel.requestLayout(); version.requestLayout()
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
        actionButton.text = "Start"
        actionButton.isEnabled = true
        coverageView.update(BooleanArray(0), crosshair = true)
    }

    private fun enterCapturing() {
        optionsPanel.visibility = View.GONE
        actionButton.text = "Finish"
        actionButton.isEnabled = false
        secondaryButton.isEnabled = true
        secondaryButton.text = "Cancel"
        secondaryButton.visibility = View.VISIBLE
        coverageView.update(if (selfieMode) selfie.coverage.copyOf() else coverage.copyOf(), crosshair = !selfieMode)
    }

    private fun enterProcessing() {
        optionsPanel.visibility = View.GONE
        actionButton.isEnabled = false
        secondaryButton.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        coverageView.update(BooleanArray(0), crosshair = false)
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
        for (k in 1..3) (editBars[k].parent as View).visibility = if (selfieEdits) View.VISIBLE else View.GONE
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
            text = "Settings apply to the next scan."
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
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun previewText(result: ScanResult?): String {
        val style = if (headStyle == HeadStyle.GOLDENEYE_SOURCE) "GoldenEye: Source style" else "Realistic"
        return "Preview · ${result?.triangleCount ?: 0} triangles · ${result?.textureSize ?: 0}px texture · $style\nDrag to spin. " +
            (if (selfieInput != null) "Move your face on the head or change the hairstyle; the head updates by itself. " +
                "The side sliders change hair colour and tone. " else "") +
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
        previewView.onPause()
        previewView.visibility = View.GONE
        surfaceView.visibility = View.VISIBLE
        keyframes.clear(); keyframeDirs.clear(); coverage.fill(false)
        selfie.reset()
        headCenter = null
        workDir.listFiles()?.forEach { it.delete() }
        lastHint = ""
        enterAim()
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

    private fun showFatal(text: String) {
        statusText.text = text
        actionButton.isEnabled = false
    }

    companion object {
        private const val REQUEST_CAMERA = 1
        private const val COVERAGE_BINS = 24
        private const val HEAD_DEPTH = 0.10f       // nose tip to head centre, metres
        private const val MIN_DIST = 0.25f
        private const val MAX_DIST = 1.0f
        private const val MAX_AIM_DEG = 25.0
        private const val NEW_VIEW_DEG = 9.0
        private const val MIN_INTERVAL_MS = 250L
        private const val MAX_KEYFRAMES = 60
        private const val MIN_KEYFRAMES = 8
        /** How far the face can be moved on the head: ±2 cm sideways, ±3 cm up/down, ±2 cm in/out. */
        /** Hair colour slider positions below this mean "natural colour". */
        private const val HUE_PHOTO_END = 0.03f
        private const val HUE_NATURAL_END = 0.5f
        private const val START_RETRY_MS = 4000L
        private val FACE_MOVE = floatArrayOf(0.02f, 0.03f, 0.04f)
        /** New selfie heads start with the face 20 mm in. */
        private const val DEFAULT_FACE_Z = -0.02f
        private const val LOW_TRIANGLES = 1500
        private const val HIGH_TRIANGLES = 3500
    }
}

package com.krackmusik.filmforge

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.media3.effect.Media3Effect
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbFilter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {
    private val ink = Color.rgb(16, 18, 16)
    private val accent = Color.rgb(216, 244, 154)
    private val executor by lazy { ContextCompat.getMainExecutor(this) }
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var shutter: Button
    private lateinit var switchLens: Button
    private lateinit var modeButton: Button
    private lateinit var permissionButton: Button
    private lateinit var photoSpeedButton: Button
    private lateinit var exposure: SeekBar
    private lateinit var recipeRow: LinearLayout
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var effect: Media3Effect? = null
    private var recording: Recording? = null
    private var finalizing = false
    private var photoBusy = false
    private var cameraOpening = false
    private var videoMode = true
    private var front = false
    private var selectedRecipe = 0
    private var responsiveFrontPhoto = true
    private var lastMedia: Uri? = null
    private val recipes = listOf("Original", "Amber 35", "Silver", "Night Blue", "Soft Cinema")

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasCamera()) openCamera()
        else {
            status.text = "Camera permission required"
            permissionButton.visibility = View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        front = savedInstanceState?.getBoolean("front") ?: false
        videoMode = savedInstanceState?.getBoolean("video") ?: true
        selectedRecipe = savedInstanceState?.getInt("recipe") ?: 0
        responsiveFrontPhoto = getPreferences(MODE_PRIVATE).getBoolean("responsiveFrontPhoto", true)
        buildInterface()
        if (hasCamera()) openCamera()
        else permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun hasCamera() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun hasAudio() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun text(value: String, size: Float = 13f) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.WHITE)
        setPadding(dp(8), dp(5), dp(8), dp(5))
    }
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(accent)
        textSize = 12f
        setOnClickListener { action() }
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ink)
            setPadding(dp(12), dp(4), dp(12), dp(8))
            fitsSystemWindows = true
        }
        setContentView(root)
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("FILMFORGE", 23f).apply {
            typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
            setTextColor(accent)
            letterSpacing = .12f
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        header.addView(text("CAMERA / 01", 11f))
        root.addView(header)
        val frame = FrameLayout(this)
        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            contentDescription = "Camera preview; tap to focus"
        }
        frame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        status = text("Opening camera", 13f).apply { setTextColor(accent) }
        root.addView(status)
        details = text("", 11f)
        root.addView(details)
        permissionButton = button("Open app permissions") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }.apply { visibility = View.GONE }
        root.addView(permissionButton)
        root.addView(text("FILM RECIPES", 10f).apply { setTextColor(Color.GRAY); letterSpacing = .15f })
        recipeRow = LinearLayout(this)
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(recipeRow)
        }
        recipes.forEachIndexed { index, name ->
            recipeRow.addView(button(name) {
                // Original uses the direct camera path for front photos.
                // Rebind only when crossing between direct and film processing.
                val needsRebind = front && !videoMode && (selectedRecipe == 0) != (index == 0)
                selectedRecipe = index
                if (needsRebind) bindCamera() else applyRecipe()
            })
        }
        root.addView(scroll)
        photoSpeedButton = button("Selfie: Responsive") {
            if (!busy()) {
                responsiveFrontPhoto = !responsiveFrontPhoto
                getPreferences(MODE_PRIVATE).edit()
                    .putBoolean("responsiveFrontPhoto", responsiveFrontPhoto).apply()
                bindCamera()
            }
        }.apply {
            visibility = View.GONE
            contentDescription = "Selfie photo quality: Responsive reduces resolution; Detail uses camera defaults"
        }
        root.addView(photoSpeedButton)
        val exposureRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        exposureRow.addView(text("EV", 12f))
        exposure = SeekBar(this).apply { isEnabled = false; contentDescription = "Exposure compensation" }
        exposureRow.addView(exposure, LinearLayout.LayoutParams(0, dp(36), 1f))
        root.addView(exposureRow)
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        modeButton = button("Video / Photo") {
            if (!busy()) { videoMode = !videoMode; bindCamera() }
        }
        shutter = button("Record") { capture() }.apply {
            textSize = 18f
            setTextColor(ink)
            backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
            isEnabled = false
        }
        switchLens = button("Flip") {
            if (!busy()) { front = !front; bindCamera() }
        }.apply { contentDescription = "Switch front and rear camera" }
        controls.addView(modeButton, LinearLayout.LayoutParams(0, dp(58), 1f))
        controls.addView(shutter, LinearLayout.LayoutParams(0, dp(58), 1.3f))
        controls.addView(switchLens, LinearLayout.LayoutParams(0, dp(58), 1f))
        root.addView(controls)
        root.addView(button("Open last capture") {
            lastMedia?.let { uri ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, if (contentResolver.getType(uri)?.startsWith("video") == true) "video/*" else "image/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (_: Exception) { toast("Open your Gallery to view the capture") }
            } ?: toast("Take a photo or record a clip first")
        })
        preview.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                camera?.cameraControl?.startFocusAndMetering(
                    FocusMeteringAction.Builder(preview.meteringPointFactory.createPoint(event.x, event.y))
                        .setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                )
                preview.performClick()
            }
            true
        }
        applyRecipe()
    }

    private fun openCamera() {
        if (cameraOpening) return
        cameraOpening = true
        permissionButton.visibility = View.GONE
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraOpening = false
            if (isDestroyed || isFinishing) return@addListener
            try { provider = future.get(); bindCamera() }
            catch (error: Exception) { fail("Cannot open camera", error) }
        }, executor)
    }

    private fun bindCamera() {
        if (busy() || !hasCamera()) return
        val activeProvider = provider ?: return
        shutter.isEnabled = false
        camera = null
        status.text = "Opening camera"
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        try {
            if (!activeProvider.hasCamera(selector)) {
                val alternate = if (front) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                check(activeProvider.hasCamera(alternate)) { "No available camera" }
                front = !front
                toast("That camera is unavailable; using the other lens")
                bindCamera()
                return
            }
            activeProvider.unbindAll()
            effect?.close()
            effect = null
            val frontPhoto = front && !videoMode
            // SurfaceView avoids the extra TextureView composition when supported.
            // CameraX automatically falls back on devices that require TextureView.
            preview.implementationMode = if (frontPhoto)
                PreviewView.ImplementationMode.PERFORMANCE
                else PreviewView.ImplementationMode.COMPATIBLE
            if (!(frontPhoto && selectedRecipe == 0)) {
                effect = Media3Effect(this,
                    CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE or CameraEffect.IMAGE_CAPTURE,
                    executor
                ) { error -> fail("Film processing failed; reopen camera", error) }
            }
            applyRecipe()
            val rotation = preview.display?.rotation ?: android.view.Surface.ROTATION_0
            val previewBuilder = Preview.Builder().setTargetRotation(rotation)
            if (frontPhoto && responsiveFrontPhoto) {
                previewBuilder.setResolutionSelector(frontPhotoResolution(Size(960, 720), 1_300_000L))
            }
            val viewfinder = previewBuilder.build().also {
                it.setSurfaceProvider(preview.surfaceProvider)
            }
            val group = UseCaseGroup.Builder().addUseCase(viewfinder)
            effect?.let { group.addEffect(it) }
            if (videoMode) {
                val recorder = Recorder.Builder().setQualitySelector(
                    QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD))
                ).build()
                val video = VideoCapture.Builder(recorder)
                    .setTargetFrameRate(Range(30, 30)).setTargetRotation(rotation).build()
                videoCapture = video
                imageCapture = null
                group.addUseCase(video)
            } else {
                val photoBuilder = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(rotation)
                if (frontPhoto && responsiveFrontPhoto) {
                    // The photo and preview share the live effects stream.
                    // Limit its pixel load explicitly in Responsive mode.
                    photoBuilder.setResolutionSelector(frontPhotoResolution(Size(1600, 1200), 2_100_000L))
                }
                val photo = photoBuilder.build()
                imageCapture = photo
                videoCapture = null
                group.addUseCase(photo)
            }
            camera = activeProvider.bindToLifecycle(this, selector, group.build())
            configureExposure()
            shutter.isEnabled = true
            shutter.text = if (videoMode) "Record" else "Shoot"
            modeButton.text = if (videoMode) "VIDEO" else "PHOTO"
            status.text = "Ready • ${recipes[selectedRecipe]}"
            photoSpeedButton.visibility = if (frontPhoto) View.VISIBLE else View.GONE
            photoSpeedButton.text = if (responsiveFrontPhoto)
                "Selfie: Responsive (up to 2 MP) • tap for Detail"
                else "Selfie: Detail • tap for Responsive"
            val outputSize = imageCapture?.resolutionInfo?.resolution
            val sizeLabel = outputSize?.let { " • ${it.width}×${it.height}" } ?: ""
            details.text = (if (front) "Front" else "Rear") +
                if (videoMode) " • 1080p / 30 target • " + if (hasAudio()) "Mic on" else "Silent"
                else sizeLabel + if (selectedRecipe == 0) " • Original" else " • Film look baked in"
        } catch (error: Exception) { fail("Camera setup failed", error) }
    }

    private fun frontPhotoResolution(preferred: Size, maxPixels: Long): ResolutionSelector =
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(preferred,
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .setResolutionFilter { supported, _ ->
                val bounded = supported.filter { it.width.toLong() * it.height <= maxPixels }
                // Keep CameraX's order and support a camera with no small output.
                if (bounded.isNotEmpty()) bounded
                else supported.minByOrNull { it.width.toLong() * it.height }?.let { listOf(it) }
                    ?: emptyList()
            }.build()

    private fun configureExposure() {
        val boundCamera = camera ?: return
        val state = boundCamera.cameraInfo.exposureState
        val limits = state.exposureCompensationRange
        exposure.setOnSeekBarChangeListener(null)
        exposure.isEnabled = state.isExposureCompensationSupported
        exposure.max = limits.upper - limits.lower
        exposure.progress = state.exposureCompensationIndex - limits.lower
        exposure.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) boundCamera.cameraControl.setExposureCompensationIndex(value + limits.lower)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
    }

    private fun applyRecipe() {
        val effects: List<Effect> = when (selectedRecipe) {
            1 -> listOf(RgbAdjustment.Builder().setRedScale(1.08f).setGreenScale(1.01f).setBlueScale(.88f).build(), Contrast(.12f), HslAdjustment.Builder().adjustSaturation(-10f).build())
            2 -> listOf(RgbFilter.createGrayscaleFilter(), Contrast(.22f))
            3 -> listOf(RgbAdjustment.Builder().setRedScale(.91f).setGreenScale(1.02f).setBlueScale(1.14f).build(), Contrast(.14f))
            4 -> listOf(Contrast(-.12f), HslAdjustment.Builder().adjustSaturation(-18f).build(), RgbAdjustment.Builder().setRedScale(1.03f).setBlueScale(.97f).build())
            else -> emptyList()
        }
        effect?.setEffects(effects)
        if (::recipeRow.isInitialized) for (index in 0 until recipeRow.childCount) {
            recipeRow.getChildAt(index).alpha = if (index == selectedRecipe) 1f else .45f
        }
        if (::status.isInitialized && !busy() && camera != null) status.text = "Ready • ${recipes[selectedRecipe]}"
    }

    private fun busy() = recording != null || finalizing || photoBusy

    private fun capture() {
        if (recording != null) {
            finalizing = true
            shutter.isEnabled = false
            status.text = "Saving clip…"
            recording?.stop()
            return
        }
        if (busy() || camera == null) return
        if (videoMode) startRecording() else takePhoto()
    }

    private fun values(mime: String, folder: String) = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "FILMFORGE_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()))
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/FILMFORGE")
    }

    private fun setControlsLocked(locked: Boolean) {
        switchLens.isEnabled = !locked
        modeButton.isEnabled = !locked
        photoSpeedButton.isEnabled = !locked
        for (index in 0 until recipeRow.childCount) recipeRow.getChildAt(index).isEnabled = !locked
    }

    private fun takePhoto() {
        val photo = imageCapture ?: return
        photoBusy = true
        shutter.isEnabled = false
        setControlsLocked(true)
        status.text = "Saving photo…"
        val output = ImageCapture.OutputFileOptions.Builder(contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values("image/jpeg", "Pictures")).build()
        photo.takePicture(output, executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                lastMedia = result.savedUri
                photoFinished("Saved to Pictures/FILMFORGE")
            }
            override fun onError(error: ImageCaptureException) {
                photoFinished("Photo failed: ${error.message}")
            }
        })
    }

    private fun photoFinished(message: String) {
        photoBusy = false
        shutter.isEnabled = camera != null
        setControlsLocked(false)
        status.text = message
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startRecording() {
        val capture = videoCapture ?: return
        try {
            val output = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(values("video/mp4", "Movies")).build()
            var pending = capture.output.prepareRecording(this, output)
            if (hasAudio()) pending = pending.withAudioEnabled()
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
            setControlsLocked(true)
            recording = pending.start(executor) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> { shutter.text = "Stop"; status.text = "REC • ${recipes[selectedRecipe]}" }
                    is VideoRecordEvent.Status -> {
                        val seconds = event.recordingStats.recordedDurationNanos / 1_000_000_000
                        status.text = "REC • %02d:%02d • %s".format(seconds / 60, seconds % 60, recipes[selectedRecipe])
                    }
                    is VideoRecordEvent.Finalize -> {
                        recording = null
                        finalizing = false
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        setControlsLocked(false)
                        shutter.text = "Record"
                        shutter.isEnabled = camera != null
                        if (!event.hasError()) {
                            lastMedia = event.outputResults.outputUri
                            status.text = "Saved to Movies/FILMFORGE"
                        } else {
                            status.text = "Recording failed (${event.error}); check storage and retry"
                        }
                    }
                }
            }
        } catch (error: Exception) {
            recording = null
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            setControlsLocked(false)
            fail("Recording failed", error)
        }
    }

    private fun fail(message: String, error: Throwable) {
        android.util.Log.e("FILMFORGE", message, error)
        status.text = "$message: ${error.message}"
        shutter.isEnabled = false
    }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }

    override fun onResume() {
        super.onResume()
        if (::preview.isInitialized && hasCamera()) {
            if (provider == null) openCamera()
            else if (camera == null) bindCamera()
        }
    }
    override fun onStop() {
        recording?.stop()
        super.onStop()
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!busy()) preview.post { bindCamera() }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("front", front)
        outState.putBoolean("video", videoMode)
        outState.putInt("recipe", selectedRecipe)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        recording?.close()
        provider?.unbindAll()
        effect?.close()
        super.onDestroy()
    }
}

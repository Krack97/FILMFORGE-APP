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

@androidx.annotation.OptIn(UnstableApi::class, androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
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
    private var lastVideoSize: Size? = null
    private var previewStreamSize: Size? = null
    private val filmState = FilmState()
    private val neural by lazy { NeuralDetail(applicationContext) }
    private val aiWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val catalog = FilmRecipe.presets().toMutableList()
    private val recipes get() = catalog.map { it.name }
    private var activeRecipe = catalog[0]
    private var boundProcessing = false
    private var nativePhotoFrame = true
    private var nativeVideoFrame = false
    private var manualIso: Int? = null
    private var manualTimeNs = 16_666_667L
    private var whiteBalance = android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_AUTO
    private var focusDiopters: Float? = null
    @Volatile private var lastCrop: android.graphics.Rect? = null
    private lateinit var frameButton: Button
    private lateinit var aiButton: Button
    private lateinit var toolsRow: LinearLayout
    private val controlButtons = mutableListOf<Button>()

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
        loadCustomRecipes()
        selectedRecipe = selectedRecipe.coerceIn(0,catalog.lastIndex)
        activeRecipe = catalog[selectedRecipe]
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
        controlButtons.clear()
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
        header.addView(text("v0.2 • LIVE FILM", 10f))
        root.addView(header)
        val frame = FrameLayout(this)
        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            contentDescription = "Camera preview; tap to focus"
        }
        frame.addView(preview, FrameLayout.LayoutParams(-1, -1))

        val landscape=resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE
        val body=LinearLayout(this).apply{orientation=if(landscape)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL}
        val controlPanel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        body.addView(frame,if(landscape)LinearLayout.LayoutParams(0,-1,1f) else LinearLayout.LayoutParams(-1,0,1f))
        if(landscape)body.addView(ScrollView(this).apply{addView(controlPanel)},LinearLayout.LayoutParams(dp(300),-1))
        else body.addView(controlPanel,LinearLayout.LayoutParams(-1,-2))
        root.addView(body,LinearLayout.LayoutParams(-1,0,1f))

        status = text("Opening camera", 13f).apply { setTextColor(accent) }
        controlPanel.addView(status)
        details = text("", 11f)
        controlPanel.addView(details)
        permissionButton = button("Open app permissions") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }.apply { visibility = View.GONE }
        controlPanel.addView(permissionButton)
        controlPanel.addView(text("FILM RECIPES", 10f).apply { setTextColor(Color.GRAY); letterSpacing = .15f })
        recipeRow = LinearLayout(this)
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(recipeRow)
        }
        rebuildRecipeButtons()
        controlPanel.addView(scroll)
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
        controlPanel.addView(photoSpeedButton)
        toolsRow = LinearLayout(this)
        val toolsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(toolsRow)
        }
        fun tool(label:String, action:()->Unit):Button = button(label,action).also {
            toolsRow.addView(it); controlButtons.add(it)
        }
        tool("Edit / save look") { if(!busy()) showRecipeEditor() }
        tool("Camera controls") { if(!busy()) showCameraControls() }
        frameButton=tool("Frame") {
            if(!busy()) {
                if(videoMode)nativeVideoFrame=!nativeVideoFrame else nativePhotoFrame=!nativePhotoFrame
                bindCamera()
            }
        }
        aiButton=tool("AI detail: Off") { if(!busy()) toggleAi() }
        tool("Sensor / info") { showCapabilities() }
        controlPanel.addView(toolsScroll)
        val exposureRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        exposureRow.addView(text("EV", 12f))
        exposure = SeekBar(this).apply { isEnabled = false; contentDescription = "Exposure compensation" }
        exposureRow.addView(exposure, LinearLayout.LayoutParams(0, dp(36), 1f))
        controlPanel.addView(exposureRow)
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
        controlPanel.addView(controls)
        controlPanel.addView(button("Open last capture") {
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
            filmState.recipe=activeRecipe
            boundProcessing=filmState.needsProcessing()
            if (boundProcessing) {
                effect = Media3Effect(this,
                    CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE or CameraEffect.IMAGE_CAPTURE,
                    executor
                ) { error -> fail("Film processing failed; reopen camera", error) }
                effect?.setEffects(listOf(LiveFilmEffect(filmState,neural) { message ->
                    runOnUiThread {
                        if(!isDestroyed) { aiButton.text="AI detail: Off"; status.text=message }
                    }
                }))
            }
            applyRecipe(false)
            val rotation = preview.display?.rotation ?: android.view.Surface.ROTATION_0
            val previewBuilder = Preview.Builder().setTargetRotation(rotation)
            val nativeFrame=if(videoMode)nativeVideoFrame else nativePhotoFrame
            val aspect=if(nativeFrame) sensorAspect() else AspectRatio.RATIO_16_9
            val previewSize=if(aspect==AspectRatio.RATIO_4_3)Size(960,720) else Size(1280,720)
            previewBuilder.setResolutionSelector(frameResolution(previewSize,1_300_000L,aspect))
            androidx.camera.camera2.interop.Camera2Interop.Extender(previewBuilder)
                .setSessionCaptureCallback(object:android.hardware.camera2.CameraCaptureSession.CaptureCallback(){
                    override fun onCaptureCompleted(session:android.hardware.camera2.CameraCaptureSession,
                        request:android.hardware.camera2.CaptureRequest,result:android.hardware.camera2.TotalCaptureResult){
                        lastCrop=result.get(android.hardware.camera2.CaptureResult.SCALER_CROP_REGION)
                    }
                })
            val viewfinder = previewBuilder.build().also {
                it.setSurfaceProvider(preview.surfaceProvider)
            }
            val group = UseCaseGroup.Builder().addUseCase(viewfinder)
            effect?.let { group.addEffect(it) }
            if (videoMode) {
                val recorder = Recorder.Builder().setAspectRatio(aspect).setQualitySelector(
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
                    photoBuilder.setResolutionSelector(frameResolution(
                        if(aspect==AspectRatio.RATIO_4_3) Size(1600,1200) else Size(1920,1080),
                        2_100_000L,aspect))
                } else {
                    photoBuilder.setResolutionSelector(ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy(aspect,AspectRatioStrategy.FALLBACK_RULE_AUTO))
                        .setResolutionStrategy(ResolutionStrategy(
                            if(aspect==AspectRatio.RATIO_4_3)Size(1920,1440) else Size(1920,1080),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                }
                val photo = photoBuilder.build()
                imageCapture = photo
                videoCapture = null
                group.addUseCase(photo)
            }
            camera = activeProvider.bindToLifecycle(this, selector, group.build())
            configureExposure()
            applyCameraControls()
            shutter.isEnabled = true
            shutter.text = if (videoMode) "Record" else "Shoot"
            modeButton.text = if (videoMode) "VIDEO" else "PHOTO"
            status.text = "Ready • ${activeRecipe.name}"
            photoSpeedButton.visibility = if (frontPhoto) View.VISIBLE else View.GONE
            photoSpeedButton.text = if (responsiveFrontPhoto)
                "Selfie: Responsive (up to 2 MP) • tap for Detail"
                else "Selfie: Detail • tap for Responsive"
            previewStreamSize=viewfinder.resolutionInfo?.resolution
            lastVideoSize=null
            val outputSize = if(videoMode)lastVideoSize
                else imageCapture?.resolutionInfo?.resolution
            val sizeLabel = outputSize?.let { " • ${it.width}×${it.height}" } ?: ""
            frameButton.text=if(nativeFrame)"Frame: Sensor ratio" else "Frame: 16:9"
            aiButton.text=if(filmState.aiEnabled)"AI detail: On" else "AI detail: Off"
            val actualRatio=outputSize?.let { maxOf(it.width,it.height).toDouble()/minOf(it.width,it.height) }
            val expectedRatio=if(aspect==AspectRatio.RATIO_4_3)4.0/3.0 else 16.0/9.0
            val matched=actualRatio!=null && kotlin.math.abs(actualRatio-expectedRatio)<.05
            details.text=(if(front)"Front" else "Rear")+sizeLabel+
                (if(videoMode)if(hasAudio())" • Mic on" else " • Silent" else " • JPEG")+
                (if(nativeFrame)if(matched)" • Sensor ratio matched" else " • Sensor ratio unconfirmed" else "")+
                (if(filmState.aiEnabled)" • Neural detail" else "")

        } catch (error: Exception) {
            if(videoMode && nativeVideoFrame) {
                nativeVideoFrame=false
                toast("Sensor-ratio video was rejected by this camera. Returning to 16:9.")
                bindCamera()
            }else fail("Camera setup failed",error)
        }
    }

    private fun frameResolution(preferred: Size, maxPixels: Long,aspect:Int): ResolutionSelector =
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy(aspect,AspectRatioStrategy.FALLBACK_RULE_AUTO))
            .setResolutionStrategy(ResolutionStrategy(preferred,
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .setResolutionFilter { supported, _ ->
                val bounded = supported.filter { it.width.toLong() * it.height <= maxPixels }
                // Keep CameraX's order and support a camera with no small output.
                if (bounded.isNotEmpty()) bounded
                else supported.minByOrNull { it.width.toLong() * it.height }?.let { listOf(it) }
                    ?: emptyList()
            }.build()


    private fun loadCustomRecipes() {
        try {
            val list=org.json.JSONArray(getPreferences(MODE_PRIVATE).getString("customRecipes","[]"))
            for(i in 0 until list.length())catalog.add(FilmRecipe.fromJson(list.getJSONObject(i)))
        }catch(_:Exception){/* Keep built-in recipes if saved data is invalid. */}
    }
    private fun slider(parent:LinearLayout,name:String,value:Float,min:Float=0f,max:Float=1f,
        change:(Float)->Unit) {
        val label=text("$name: %.2f".format(value),12f);parent.addView(label)
        parent.addView(SeekBar(this).apply {
            this.max=100
            progress=(((value-min)/(max-min))*100).toInt().coerceIn(0,100)
            contentDescription=name
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(v:SeekBar?,p:Int,fromUser:Boolean) {
                    if(fromUser){
                        val amount=min+(max-min)*p/100f
                        label.text="$name: %.2f".format(amount)
                        change(amount)
                    }
                }
                override fun onStartTrackingTouch(v:SeekBar?){}
                override fun onStopTrackingTouch(v:SeekBar?){}
            })
        })
    }
    private fun persistCustomRecipes(){
        val json=org.json.JSONArray()
        catalog.drop(FilmRecipe.presets().size).forEach{json.put(it.toJson())}
        getPreferences(MODE_PRIVATE).edit().putString("customRecipes",json.toString()).apply()
    }
    private fun showRecipeEditor(){
        val panel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(16),0,dp(16),0) }
        val name=EditText(this).apply{
            setText(activeRecipe.name+" custom");setTextColor(Color.WHITE)
            hint="Recipe name";setSingleLine(true)
        }
        panel.addView(name)
        panel.addView(text("Live edits affect preview and capture. Named film looks are creative interpretations.",11f))
        fun edit(next:FilmRecipe){activeRecipe=next;applyRecipe()}
        slider(panel,"Contrast",activeRecipe.contrast,-1f,1f){edit(activeRecipe.copy(contrast=it))}
        slider(panel,"Saturation",activeRecipe.saturation,0f,2f){edit(activeRecipe.copy(saturation=it))}
        slider(panel,"Warmth",activeRecipe.warmth,-1f,1f){edit(activeRecipe.copy(warmth=it))}
        slider(panel,"Tint",activeRecipe.tint,-1f,1f){edit(activeRecipe.copy(tint=it))}
        slider(panel,"Faded blacks",activeRecipe.fade){edit(activeRecipe.copy(fade=it))}
        slider(panel,"Film grain",activeRecipe.grain){edit(activeRecipe.copy(grain=it))}
        slider(panel,"Halation",activeRecipe.halation){edit(activeRecipe.copy(halation=it))}
        slider(panel,"Highlight softness",activeRecipe.highlights){edit(activeRecipe.copy(highlights=it))}
        slider(panel,"Shadow lift",activeRecipe.shadows,-1f,1f){edit(activeRecipe.copy(shadows=it))}
        slider(panel,"Vignette",activeRecipe.vignette){edit(activeRecipe.copy(vignette=it))}
        slider(panel,"Edge-preserving denoise",activeRecipe.denoise){edit(activeRecipe.copy(denoise=it))}
        slider(panel,"Skin-color protection",activeRecipe.skin){edit(activeRecipe.copy(skin=it))}
        slider(panel,"Motion blend",activeRecipe.motion){edit(activeRecipe.copy(motion=it))}
        panel.addView(text("Motion blend is a temporal trail effect. Physical motion blur uses the shutter control.",11f))
        panel.addView(button("Copy recipe JSON"){
            val json=activeRecipe.copy(name=name.text.toString().trim().ifEmpty{"My film"}).toJson().toString(2)
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("FILMFORGE recipe",json))
            toast("Recipe copied")
        })
        android.app.AlertDialog.Builder(this).setTitle("Film Recipe Creator")
            .setView(ScrollView(this).apply{addView(panel);setBackgroundColor(ink)})
            .setPositiveButton("Save recipe"){_,_->
                val saved=activeRecipe.copy(name=name.text.toString().trim().take(40).ifEmpty{"My film"})
                catalog.add(saved);selectedRecipe=catalog.lastIndex;activeRecipe=saved
                persistCustomRecipes();rebuildRecipeButtons();applyRecipe()
            }.setNegativeButton("Keep live edits",null)
            .setNeutralButton("Import JSON"){_,_->importRecipe()}.show()
    }
    private fun importRecipe(){
        val input=EditText(this).apply{hint="Paste FILMFORGE recipe JSON"}
        android.app.AlertDialog.Builder(this).setTitle("Import recipe").setView(input)
            .setPositiveButton("Import"){_,_->
                try{
                    val r=FilmRecipe.fromJson(org.json.JSONObject(input.text.toString()))
                    catalog.add(r);selectedRecipe=catalog.lastIndex;activeRecipe=r
                    persistCustomRecipes();rebuildRecipeButtons();applyRecipe()
                }catch(_:Exception){toast("This is not a valid FILMFORGE recipe")}
            }.setNegativeButton("Cancel",null).show()
    }
    private fun characteristics():android.hardware.camera2.CameraCharacteristics?=try {
        val info=camera?.cameraInfo ?: provider?.availableCameraInfos?.firstOrNull {
            it.lensFacing==if(front)CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        }
        if(info==null)null else {
            val id=androidx.camera.camera2.interop.Camera2CameraInfo.from(info).cameraId
            (getSystemService(CAMERA_SERVICE) as android.hardware.camera2.CameraManager).getCameraCharacteristics(id)
        }
    }catch(_:Exception){null}
    private fun sensorAspect():Int {
        val active=characteristics()?.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val ratio=active?.let{maxOf(it.width(),it.height()).toDouble()/minOf(it.width(),it.height())}
        return if(ratio!=null && kotlin.math.abs(ratio-4.0/3)<.08)
            AspectRatio.RATIO_4_3 else AspectRatio.RATIO_16_9
    }
    private fun hasManual(c:android.hardware.camera2.CameraCharacteristics?)=
        c?.get(android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)==true

    private fun applyCameraControls(){
        val bound=camera ?: return
        val c=characteristics()
        val options=androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
        val iso=manualIso
        if(iso!=null && hasManual(c)){
            val range=c?.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val times=c?.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            options.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
                android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_OFF)
            options.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY,range?.clamp(iso)?:iso)
            options.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME,times?.clamp(manualTimeNs)?:manualTimeNs)
            options.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.SENSOR_FRAME_DURATION,maxOf(33_333_333L,manualTimeNs))
            exposure.isEnabled=false
        }else{
            options.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
                android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_ON)
            exposure.isEnabled=bound.cameraInfo.exposureState.isExposureCompensationSupported
        }
        val modes=c?.get(android.hardware.camera2.CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
        if(modes?.contains(whiteBalance)==true)
            options.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE,whiteBalance)
        val minFocus=c?.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?:0f
        if(focusDiopters!=null && minFocus>0f){
            options.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_OFF)
            options.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.LENS_FOCUS_DISTANCE,focusDiopters!!.coerceIn(0f,minFocus))
        }
        val future=androidx.camera.camera2.interop.Camera2CameraControl.from(bound.cameraControl).setCaptureRequestOptions(options.build())
        future.addListener({
            try{future.get()}catch(_:Exception){status.text="Camera rejected a manual control; switch back to Auto"}
        },executor)
    }

    private fun showCameraControls(){
        val c=characteristics()
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),0,dp(16),0)}
        if(hasManual(c)){
            val isoRange=c?.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            panel.addView(button("Exposure: Auto / Manual"){
                manualIso=if(manualIso==null)(isoRange?.clamp(200)?:200) else null
                applyCameraControls();toast(if(manualIso==null)"Automatic exposure" else "Manual exposure")
            })
            if(isoRange!=null)slider(panel,"ISO", (manualIso?:isoRange.clamp(200)).toFloat(),
                isoRange.lower.toFloat(),maxOf(isoRange.upper,isoRange.lower+1).toFloat()){
                manualIso=it.toInt();applyCameraControls()
            }
            panel.addView(text("Shutter (slow exposures can reduce live frame rate)",11f))
            val shutters=linkedMapOf("1/24" to 41_666_667L,"1/48" to 20_833_333L,
                "1/60" to 16_666_667L,"1/120" to 8_333_333L,"1/250" to 4_000_000L)
            val row=LinearLayout(this)
            shutters.forEach{(name,time)->row.addView(button(name){
                manualTimeNs=time
                if(manualIso==null)manualIso=isoRange?.clamp(200)?:200
                applyCameraControls();toast("Shutter $name")
            })}
            panel.addView(HorizontalScrollView(this).apply{addView(row)})
        }else panel.addView(text("This camera does not expose manual ISO/shutter. EV remains available.",12f))
        panel.addView(text("White balance",12f))
        val wb=linkedMapOf("Auto" to 1,"Daylight" to 5,"Cloudy" to 6,"Tungsten" to 2,"Fluorescent" to 3)
        val modes=c?.get(android.hardware.camera2.CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?:intArrayOf()
        val wbRow=LinearLayout(this)
        wb.forEach{(name,mode)->if(modes.contains(mode))wbRow.addView(button(name){
            whiteBalance=mode;applyCameraControls();toast("White balance: $name")
        })}
        panel.addView(HorizontalScrollView(this).apply{addView(wbRow)})
        val focus=c?.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?:0f
        if(focus>0f)slider(panel,"Manual focus (diopters)",focusDiopters?:0f,0f,focus){
            focusDiopters=it;applyCameraControls()
        }else panel.addView(text("This lens has fixed focus.",11f))
        panel.addView(button("Reset camera controls"){
            manualIso=null;focusDiopters=null;whiteBalance=1;applyCameraControls();toast("Auto controls restored")
        })
        android.app.AlertDialog.Builder(this).setTitle("Camera controls")
            .setView(ScrollView(this).apply{setBackgroundColor(ink);addView(panel)})
            .setPositiveButton("Done",null).show()
    }
    private fun toggleAi(){
        if(filmState.aiEnabled){
            filmState.aiEnabled=false;aiButton.text="AI detail: Off";applyRecipe();return
        }
        aiButton.isEnabled=false;aiButton.text="AI: Benchmarking…"
        aiWorker.execute{
            var ok=false
            val result=try{ok=neural.prepare();neural.description}
                catch(e:Exception){"AI unavailable: "+(e.message?:"model could not load")}
            runOnUiThread{
                if(!isDestroyed){
                    aiButton.isEnabled=!busy()
                    if(ok && !busy()){
                        filmState.aiEnabled=true;aiButton.text="AI detail: On";applyRecipe()
                    }else aiButton.text="AI detail: Off"
                    android.app.AlertDialog.Builder(this).setTitle("Live neural detail")
                        .setMessage(result+"\n\nA trained x3 luminance model analyses small live frames and blends its detail residual into the full-size image. This is not full-resolution multi-frame reconstruction or an AI noise-removal model. No footage leaves the phone.\n\nAI stops automatically if processing becomes too slow.")
                        .setPositiveButton("OK",null).show()
                }
            }
        }
    }
    private fun showCapabilities(){
        val c=characteristics()
        val active=c?.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val map=c?.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        fun sizes(format:Int)=map?.getOutputSizes(format)?.take(16)?.joinToString { "${it.width}×${it.height}" }?:"Not exposed"
        val capabilities=c?.get(android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?:intArrayOf()
        val output=if(videoMode)lastVideoSize else imageCapture?.resolutionInfo?.resolution
        val report="FILMFORGE 0.2.0 • ${android.os.Build.MODEL}\n"+
            "Camera: ${if(front)"front" else "rear"}\n"+
            "Sensor active array: ${active?.width()}×${active?.height()}\n"+
            "Reported crop: $lastCrop\nPreview stream: $previewStreamSize\nOutput: $output (video dimensions available after saving a clip)\n"+
            "Manual exposure: ${hasManual(c)}\nRAW exposed: ${capabilities.contains(3)}\n"+
            "JPEG sizes: ${sizes(android.graphics.ImageFormat.JPEG)}\n"+
            "YUV sizes: ${sizes(android.graphics.ImageFormat.YUV_420_888)}\n\n"+
            "Sensor-ratio mode requests the sensor's 4:3 or 16:9 framing. CameraX and the encoder may reject or substitute it. Matching dimensions do not prove full-sensor open-gate readout.\n\n"+
            "RAW availability is reported; RAW/DNG capture and multi-frame HDR are not implemented.\n\n"+
            neural.description+"\nLast AI inference: %.1f ms\nLast effect CPU submission: %.1f ms".format(filmState.aiMs,filmState.renderMs)
        android.app.AlertDialog.Builder(this).setTitle("Sensor / open-gate check").setMessage(report)
            .setPositiveButton("Done",null)
            .setNeutralButton("Copy report"){_,_->
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("FILMFORGE sensor report",report))
                toast("Report copied")
            }.show()
    }

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

    private fun applyRecipe(allowRebind:Boolean=true) {
        filmState.recipe=activeRecipe
        if(allowRebind && camera!=null && boundProcessing!=filmState.needsProcessing() && !busy()) {
            bindCamera()
            return
        }
        if (::recipeRow.isInitialized) for (index in 0 until recipeRow.childCount) {
            recipeRow.getChildAt(index).alpha=if(index==selectedRecipe)1f else .45f
        }
        if (::status.isInitialized && !busy() && camera!=null) status.text="Ready • ${activeRecipe.name}"
    }

    private fun rebuildRecipeButtons() {
        recipeRow.removeAllViews()
        catalog.forEachIndexed { index, recipe ->
            recipeRow.addView(button(recipe.name) {
                selectedRecipe=index;activeRecipe=recipe;applyRecipe()
            })
        }
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
        controlButtons.forEach{it.isEnabled=!locked}
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
                            inspectSavedVideo(event.outputResults.outputUri)
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


    private fun inspectSavedVideo(uri:Uri){
        aiWorker.execute{
            val size=try{
                val retriever=android.media.MediaMetadataRetriever()
                try{
                    retriever.setDataSource(this,uri)
                    val w=retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                    val h=retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                    if(w!=null && h!=null)Size(w,h) else null
                }finally{retriever.release()}
            }catch(_:Exception){null}
            runOnUiThread{
                if(!isDestroyed && videoMode && lastMedia==uri){
                    lastVideoSize=size
                    if(size!=null)details.text="Saved video: ${size.width}×${size.height} • See Sensor / info"
                }
            }
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
        if(!busy()){
            provider?.unbindAll();effect?.close();effect=null;camera=null
            buildInterface()
            preview.post{bindCamera()}
        }
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
        aiWorker.shutdownNow()
        neural.close()
        super.onDestroy()
    }
}


/** Controls are immutable snapshots so UI edits never race a half-updated frame. */
data class FilmRecipe(
    val name: String, val contrast: Float = 0f, val saturation: Float = 1f,
    val warmth: Float = 0f, val tint: Float = 0f, val fade: Float = 0f,
    val grain: Float = 0f, val halation: Float = 0f, val highlights: Float = 0f,
    val shadows: Float = 0f, val vignette: Float = 0f, val denoise: Float = 0f,
    val skin: Float = 0f, val motion: Float = 0f
) {
    fun toJson() = org.json.JSONObject().apply {
        put("name", name)
        fields().forEach { (key, value) -> put(key, value.toDouble()) }
    }
    fun fields() = linkedMapOf("contrast" to contrast, "saturation" to saturation,
        "warmth" to warmth, "tint" to tint, "fade" to fade, "grain" to grain,
        "halation" to halation, "highlights" to highlights, "shadows" to shadows,
        "vignette" to vignette, "denoise" to denoise, "skin" to skin, "motion" to motion)
    companion object {
        fun fromJson(j: org.json.JSONObject) = FilmRecipe(j.optString("name", "My film"),
            j.optDouble("contrast", 0.0).toFloat().coerceIn(-1f,1f),
            j.optDouble("saturation",1.0).toFloat().coerceIn(0f,2f),
            j.optDouble("warmth",0.0).toFloat().coerceIn(-1f,1f),
            j.optDouble("tint",0.0).toFloat().coerceIn(-1f,1f),
            j.optDouble("fade",0.0).toFloat().coerceIn(0f,1f),
            j.optDouble("grain",0.0).toFloat().coerceIn(0f,1f),
            j.optDouble("halation",0.0).toFloat().coerceIn(0f,1f),
            j.optDouble("highlights",0.0).toFloat().coerceIn(0f,1f),
            j.optDouble("shadows",0.0).toFloat().coerceIn(-1f,1f),
            j.optDouble("vignette",0.0).toFloat().coerceIn(0f,1f),
            j.optDouble("denoise",0.0).toFloat().coerceIn(0f,1f),
            j.optDouble("skin",0.0).toFloat().coerceIn(0f,1f),
            j.optDouble("motion",0.0).toFloat().coerceIn(0f,1f))
        fun presets() = listOf(
            FilmRecipe("Original"),
            FilmRecipe("Portra 400", .08f,.86f,.20f,.06f,.18f,.12f,.08f,.60f,.12f,.08f,.08f,.75f),
            FilmRecipe("Portra 800", .12f,.82f,.24f,.06f,.20f,.25f,.12f,.50f,.08f,.10f,.10f,.75f),
            FilmRecipe("800T", .20f,.90f,-.28f,.04f,.10f,.24f,.55f,.35f,-.08f,.15f,.08f,.45f),
            FilmRecipe("Gold 200", .18f,1.12f,.42f,.03f,.12f,.16f,.13f,.35f,.02f,.14f,.04f,.50f),
            FilmRecipe("Classic Chrome", .24f,.68f,-.08f,-.07f,.13f,.13f,.04f,.55f,-.08f,.12f,.04f,.50f),
            FilmRecipe("Vision3 500T", .12f,.78f,-.18f,.05f,.15f,.18f,.30f,.70f,.18f,.12f,.10f,.65f),
            FilmRecipe("HP5", .30f,0f,0f,0f,.10f,.36f,.05f,.40f,-.08f,.15f),
            FilmRecipe("Amber 35",.18f,.9f,.38f,0f,.10f,.16f,.16f,.35f,0f,.12f),
            FilmRecipe("Silver",.32f,0f,0f,0f,.02f,.20f,0f,.20f),
            FilmRecipe("Night Blue",.22f,.92f,-.4f,-.08f,.05f,.12f,.3f,.45f,0f,.18f,.1f,.4f),
            FilmRecipe("Soft Cinema",-.12f,.78f,.10f,0f,.22f,.1f,.12f,.65f,.18f,.10f,.12f,.65f)
        )
    }
}

class FilmState {
    @Volatile var recipe = FilmRecipe("Original")
    @Volatile var aiEnabled = false
    @Volatile var aiMs = 0.0
    @Volatile var renderMs = 0.0
    fun needsProcessing() = aiEnabled || recipe != FilmRecipe("Original")
}

/** Trained sub-pixel CNN; runs locally, with a hard latency circuit breaker. */
class NeuralDetail(private val context: android.content.Context) : AutoCloseable {
    private var session: ai.onnxruntime.OrtSession? = null
    private val environment by lazy { ai.onnxruntime.OrtEnvironment.getEnvironment() }
    @Volatile var ready = false
    @Volatile var maxEdge = 128
    @Volatile var benchmarkMs = 0.0
    @Volatile var description = "AI has not been benchmarked"
    @Synchronized fun prepare(): Boolean {
        if (ready) return true
        if (session == null) {
            val options = ai.onnxruntime.OrtSession.SessionOptions()
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            try {
                val bytes = context.assets.open("super-resolution.onnx").use { it.readBytes() }
                session = environment.createSession(bytes, options)
            } finally { options.close() }
        }
        val probe = FloatArray(128*96) { i -> ((i % 128) / 127f) * .8f + .1f }
        infer(probe,128,96) // warm up before measuring
        val start=android.os.SystemClock.elapsedRealtimeNanos()
        repeat(3) { infer(probe,128,96) }
        benchmarkMs=(android.os.SystemClock.elapsedRealtimeNanos()-start)/3_000_000.0
        ready=benchmarkMs<=40.0
        description=if(ready) "Neural detail: %.1f ms inference (128px live analysis)".format(benchmarkMs)
            else "Live AI disabled: %.1f ms/frame exceeded the 40 ms inference budget".format(benchmarkMs)
        return ready
    }
    @Synchronized fun infer(input: FloatArray,w:Int,h:Int): FloatArray {
        val active=session ?: error("AI model is unavailable")
        val tensor=ai.onnxruntime.OnnxTensor.createTensor(environment,
            java.nio.FloatBuffer.wrap(input),longArrayOf(1,1,h.toLong(),w.toLong()))
        tensor.use {
            active.run(mapOf(active.inputNames.first() to tensor)).use { result ->
                val out=result[0] as ai.onnxruntime.OnnxTensor
                val shape=(out.info as ai.onnxruntime.TensorInfo).shape
                check(shape.contentEquals(longArrayOf(1,1,(h*3).toLong(),(w*3).toLong()))) {
                    "Unexpected neural model output"
                }
                return FloatArray(w*h*9).also { out.floatBuffer.get(it) }
            }
        }
    }
    @Synchronized override fun close() { session?.close(); session=null; ready=false }
}

private const val FILM_VERTEX = """
attribute vec4 aPosition;
varying vec2 vUv;
void main(){ gl_Position=aPosition; vUv=(aPosition.xy+1.0)*0.5; }
"""
private const val COPY_FRAGMENT = """
precision highp float;
uniform sampler2D uInput;
varying vec2 vUv;
void main(){gl_FragColor=texture2D(uInput,vUv);}
"""
private const val FILM_FRAGMENT = """
precision highp float;
uniform sampler2D uInput;
uniform sampler2D uResidual;
uniform sampler2D uHistory;
uniform vec2 uPixel;
uniform float uTime;
uniform float uContrast,uSat,uWarmth,uTint,uFade,uGrain,uHalo,uHighlights;
uniform float uShadows,uVignette,uDenoise,uSkin,uMotion,uAi,uHasHistory;
varying vec2 vUv;
vec3 gamma(vec3 x){return pow(max(x,vec3(0.0)),vec3(1.0/2.2));}
vec3 linear(vec3 x){return pow(max(x,vec3(0.0)),vec3(2.2));}
float lum(vec3 c){return dot(c,vec3(.299,.587,.114));}
float noise(vec2 p){return fract(sin(dot(p,vec2(12.9898,78.233))+uTime)*43758.5453)-.5;}
vec3 bright(vec2 p){vec3 c=gamma(texture2D(uInput,p).rgb);return c*smoothstep(.68,.98,lum(c));}
void main(){
 vec3 raw=texture2D(uInput,vUv).rgb;
 vec3 c=gamma(raw);
 vec3 original=c;
 if(uDenoise>.001){
   vec3 n1=gamma(texture2D(uInput,vUv+vec2(uPixel.x,0.0)).rgb);
   vec3 n2=gamma(texture2D(uInput,vUv-vec2(uPixel.x,0.0)).rgb);
   vec3 n3=gamma(texture2D(uInput,vUv+vec2(0.0,uPixel.y)).rgb);
   vec3 n4=gamma(texture2D(uInput,vUv-vec2(0.0,uPixel.y)).rgb);
   float a=exp(-dot(n1-c,n1-c)*90.0),b=exp(-dot(n2-c,n2-c)*90.0);
   float d=exp(-dot(n3-c,n3-c)*90.0),e=exp(-dot(n4-c,n4-c)*90.0);
   c=mix(c,(c*2.0+n1*a+n2*b+n3*d+n4*e)/(2.0+a+b+d+e),uDenoise*.85);
 }
 if(uAi>.001) c+=vec3((texture2D(uResidual,vUv).r-.5)/3.0)*uAi;
 float skin=smoothstep(.015,.06,original.r-original.b) *
   (1.0-smoothstep(.25,.5,abs(original.r-original.g))) *
   smoothstep(.03,.15,lum(original))*uSkin;
 float sat=mix(uSat,1.0,skin*.55*step(.01,uSat));
 c=mix(vec3(lum(c)),c,sat);
 c=(c-.5)*(1.0+uContrast*.65)+.5;
 c+=vec3(uWarmth*.065,uTint*.04,-uWarmth*.065);
 float l=lum(c);
 c+=uShadows*.14*(1.0-smoothstep(.1,.65,l));
 vec3 high=max(c-vec3(.6),vec3(0.0));
 c-=high*high*uHighlights*.65;
 c=c*(1.0-uFade*.24)+uFade*.065;
 if(uHalo>.001){
   vec2 q=uPixel*4.0;
   vec3 glow=(bright(vUv+vec2(q.x,0.0))+bright(vUv-vec2(q.x,0.0))+
       bright(vUv+vec2(0.0,q.y))+bright(vUv-vec2(0.0,q.y)))*.25;
   c+=glow*vec3(.13,.026,.008)*uHalo*(1.0-smoothstep(.8,1.0,lum(c)));
 }
 c+=noise(vUv/uPixel)*uGrain*.055*(.7+.3*(1.0-lum(c)));
 float edge=smoothstep(.15,.72,length(vUv-.5));
 c*=1.0-edge*uVignette*.45;
 vec3 result=linear(clamp(c,0.0,1.0));
 if(uMotion>.001 && uHasHistory>.5)
   result=mix(result,texture2D(uHistory,vUv).rgb,uMotion*.45);
 gl_FragColor=vec4(result,1.0);
}
"""

@androidx.annotation.OptIn(UnstableApi::class)
class LiveFilmEffect(private val state: FilmState,private val ai:NeuralDetail,
    private val onAiSlow:(String)->Unit) : androidx.media3.effect.GlEffect {
    override fun toGlShaderProgram(context:android.content.Context,useHdr:Boolean):
        androidx.media3.effect.GlShaderProgram {
        check(!useHdr) { "FILMFORGE film engine currently supports SDR" }
        return FilmProgram(state,ai,onAiSlow)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private class FilmProgram(private val state:FilmState,private val ai:NeuralDetail,
    private val onAiSlow:(String)->Unit) : androidx.media3.effect.BaseGlShaderProgram(false,1) {
    private val program=androidx.media3.common.util.GlProgram(FILM_VERTEX,FILM_FRAGMENT)
    private val copy=androidx.media3.common.util.GlProgram(FILM_VERTEX,COPY_FRAGMENT)
    private val vertices=floatArrayOf(-1f,-1f,0f,1f, 1f,-1f,0f,1f,
        -1f,1f,0f,1f, 1f,1f,0f,1f)
    private var width=0; private var height=0
    private var aiWidth=0; private var aiHeight=0
    private var history=0; private var small=0; private var residual=0; private var smallFbo=0
    private var hasHistory=false; private var previousTime=Long.MIN_VALUE; private var slowFrames=0
    private var pixels=java.nio.ByteBuffer.allocateDirect(0)
    private var packed=java.nio.ByteBuffer.allocateDirect(0)
    private var y=FloatArray(0)
    init {
        program.setBufferAttribute("aPosition",vertices,4)
        copy.setBufferAttribute("aPosition",vertices,4)
    }
    private fun texture(w:Int,h:Int):Int {
        val ids=IntArray(1); android.opengl.GLES20.glGenTextures(1,ids,0)
        android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D,ids[0])
        android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D,android.opengl.GLES20.GL_TEXTURE_MIN_FILTER,android.opengl.GLES20.GL_LINEAR)
        android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D,android.opengl.GLES20.GL_TEXTURE_MAG_FILTER,android.opengl.GLES20.GL_LINEAR)
        android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D,android.opengl.GLES20.GL_TEXTURE_WRAP_S,android.opengl.GLES20.GL_CLAMP_TO_EDGE)
        android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D,android.opengl.GLES20.GL_TEXTURE_WRAP_T,android.opengl.GLES20.GL_CLAMP_TO_EDGE)
        android.opengl.GLES20.glTexImage2D(android.opengl.GLES20.GL_TEXTURE_2D,0,android.opengl.GLES20.GL_RGBA,w,h,0,
            android.opengl.GLES20.GL_RGBA,android.opengl.GLES20.GL_UNSIGNED_BYTE,null)
        return ids[0]
    }
    override fun configure(inputWidth:Int,inputHeight:Int):androidx.media3.common.util.Size {
        clearTextures()
        width=inputWidth; height=inputHeight
        val scale=128.0/maxOf(width,height)
        aiWidth=maxOf(16,((width*scale).toInt()/4)*4)
        aiHeight=maxOf(16,((height*scale).toInt()/4)*4)
        history=texture(width,height)
        small=texture(aiWidth,aiHeight)
        residual=texture(aiWidth*3,aiHeight*3)
        val fb=IntArray(1); android.opengl.GLES20.glGenFramebuffers(1,fb,0);smallFbo=fb[0]
        pixels=java.nio.ByteBuffer.allocateDirect(aiWidth*aiHeight*4)
        packed=java.nio.ByteBuffer.allocateDirect(aiWidth*aiHeight*9*4)
        y=FloatArray(aiWidth*aiHeight)
        hasHistory=false
        return androidx.media3.common.util.Size(width,height)
    }
    private fun draw(p:androidx.media3.common.util.GlProgram,input:Int) {
        p.use(); p.setSamplerTexIdUniform("uInput",input,0)
        p.bindAttributesAndUniforms()
        android.opengl.GLES20.glDrawArrays(android.opengl.GLES20.GL_TRIANGLE_STRIP,0,4)
    }
    private fun neural(input:Int):Boolean {
        if(!state.aiEnabled||!ai.ready)return false
        val fbo=IntArray(1); android.opengl.GLES20.glGetIntegerv(android.opengl.GLES20.GL_FRAMEBUFFER_BINDING,fbo,0)
        val viewport=IntArray(4);android.opengl.GLES20.glGetIntegerv(android.opengl.GLES20.GL_VIEWPORT,viewport,0)
        try {
            android.opengl.GLES20.glBindFramebuffer(android.opengl.GLES20.GL_FRAMEBUFFER,smallFbo)
            android.opengl.GLES20.glFramebufferTexture2D(android.opengl.GLES20.GL_FRAMEBUFFER,
                android.opengl.GLES20.GL_COLOR_ATTACHMENT0,android.opengl.GLES20.GL_TEXTURE_2D,small,0)
            check(android.opengl.GLES20.glCheckFramebufferStatus(android.opengl.GLES20.GL_FRAMEBUFFER)==android.opengl.GLES20.GL_FRAMEBUFFER_COMPLETE)
            android.opengl.GLES20.glViewport(0,0,aiWidth,aiHeight);draw(copy,input)
            pixels.clear()
            android.opengl.GLES20.glReadPixels(0,0,aiWidth,aiHeight,android.opengl.GLES20.GL_RGBA,android.opengl.GLES20.GL_UNSIGNED_BYTE,pixels)
            for(i in y.indices) {
                fun g(offset:Int)=Math.pow((pixels.get(i*4+offset).toInt() and 255)/255.0,1.0/2.2).toFloat()
                y[i]=.299f*g(0)+.587f*g(1)+.114f*g(2)
            }
            val begin=android.os.SystemClock.elapsedRealtimeNanos()
            val output=ai.infer(y,aiWidth,aiHeight)
            state.aiMs=(android.os.SystemClock.elapsedRealtimeNanos()-begin)/1_000_000.0
            packed.clear()
            val ow=aiWidth*3;val oh=aiHeight*3
            for(py in 0 until oh)for(px in 0 until ow){
                val sx=((px+.5f)/3f-.5f).coerceIn(0f,(aiWidth-1).toFloat())
                val sy=((py+.5f)/3f-.5f).coerceIn(0f,(aiHeight-1).toFloat())
                val x0=sx.toInt();val y0=sy.toInt()
                val x1=minOf(x0+1,aiWidth-1);val y1=minOf(y0+1,aiHeight-1)
                val tx=sx-x0;val ty=sy-y0
                val base=(y[y0*aiWidth+x0]*(1-tx)+y[y0*aiWidth+x1]*tx)*(1-ty)+
                    (y[y1*aiWidth+x0]*(1-tx)+y[y1*aiWidth+x1]*tx)*ty
                val delta=(output[py*ow+px]-base).coerceIn(-.15f,.15f)
                packed.put(((.5f+delta*3f)*255).toInt().coerceIn(0,255).toByte())
                packed.put(0);packed.put(0);packed.put(255.toByte())
            }
            packed.flip()
            android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D,residual)
            android.opengl.GLES20.glTexSubImage2D(android.opengl.GLES20.GL_TEXTURE_2D,0,0,0,ow,oh,
                android.opengl.GLES20.GL_RGBA,android.opengl.GLES20.GL_UNSIGNED_BYTE,packed)
            return true
        } catch(e:Exception) {
            state.aiEnabled=false
            onAiSlow("AI stopped: "+(e.message?:"processing error"))
            return false
        } finally {
            android.opengl.GLES20.glBindFramebuffer(android.opengl.GLES20.GL_FRAMEBUFFER,fbo[0])
            android.opengl.GLES20.glViewport(viewport[0],viewport[1],viewport[2],viewport[3])
        }
    }
    override fun drawFrame(inputTexId:Int,presentationTimeUs:Long) {
        val start=android.os.SystemClock.elapsedRealtimeNanos()
        val activeAi=neural(inputTexId)
        val r=state.recipe
        program.use()
        program.setSamplerTexIdUniform("uInput",inputTexId,0)
        program.setSamplerTexIdUniform("uResidual",residual,1)
        program.setSamplerTexIdUniform("uHistory",history,2)
        program.setFloatsUniform("uPixel",floatArrayOf(1f/width,1f/height))
        program.setFloatUniform("uTime",(presentationTimeUs%1_000_000_000L)/1_000_000f)
        val values=mapOf("uContrast" to r.contrast,"uSat" to r.saturation,"uWarmth" to r.warmth,
            "uTint" to r.tint,"uFade" to r.fade,"uGrain" to r.grain,"uHalo" to r.halation,
            "uHighlights" to r.highlights,"uShadows" to r.shadows,"uVignette" to r.vignette,
            "uDenoise" to r.denoise,"uSkin" to r.skin,"uMotion" to r.motion,
            "uAi" to if(activeAi).65f else 0f,
            "uHasHistory" to if(hasHistory && presentationTimeUs>previousTime &&
                presentationTimeUs-previousTime<200_000L)1f else 0f)
        values.forEach{(k,v)->program.setFloatUniform(k,v)}
        program.bindAttributesAndUniforms()
        android.opengl.GLES20.glDrawArrays(android.opengl.GLES20.GL_TRIANGLE_STRIP,0,4)
        if(r.motion>.001f){
            android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D,history)
            android.opengl.GLES20.glCopyTexSubImage2D(android.opengl.GLES20.GL_TEXTURE_2D,0,0,0,0,0,width,height)
            hasHistory=true
        }else hasHistory=false
        previousTime=presentationTimeUs
        state.renderMs=(android.os.SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0
        if(activeAi){
            slowFrames=if(state.renderMs>75.0)slowFrames+1 else 0
            if(slowFrames>=3){
                state.aiEnabled=false
                onAiSlow("AI paused: live processing exceeded 75 ms/frame. Film effects remain active.")
            }
        }
    }
    override fun flush(){super.flush();hasHistory=false;previousTime=Long.MIN_VALUE}
    private fun clearTextures(){
        if(history!=0)android.opengl.GLES20.glDeleteTextures(3,intArrayOf(history,small,residual),0)
        if(smallFbo!=0)android.opengl.GLES20.glDeleteFramebuffers(1,intArrayOf(smallFbo),0)
        history=0;small=0;residual=0;smallFbo=0
    }
    override fun release(){
        try{super.release()}finally{clearTextures();program.delete();copy.delete()}
    }
}

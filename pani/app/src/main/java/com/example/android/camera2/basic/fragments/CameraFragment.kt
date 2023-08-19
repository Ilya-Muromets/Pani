/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.camera2.basic.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.text.Html
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import com.example.android.camera2.basic.CameraActivity
import com.example.android.camera2.basic.R
import com.example.android.camera2.basic.databinding.FragmentCameraBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.Integer.max
import java.lang.Integer.min
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.random.Random

class CameraFragment : Fragment() {

    lateinit var saveFolderDir: File
    private val imageBufferSize: Int = 42   // Max number of images in imageQueue
    private val TAG = CameraFragment::class.java.simpleName

    // nav -> grabbed from navigation/UI
    var navSelectedCamera: Int = 0  // 0 - Wide, 1 - Ultrawide, 2 - Tele, 3 - 2X, 4 - 10X
    var navISO: Int = 1000
    var navExposure: Long = 10000
    var navFocal: Float = 1.0F
    var navLockAF: Boolean = true
    var navLockAE: Boolean = true
    var navLockOIS: Boolean = false
    var navManualFocus: Boolean = false
    var navManualExposure: Boolean = false
    var navTrash: Boolean = false // delete files after writing
    var navFilename: String = "0"
    var navMaxFPS: Float = 22F

    lateinit var navInfoTextView: TextView
    lateinit var navFramesValue: TextView

    var currentISO: Int = 42
    var currentFrameDuration: Long = 42
    var currentExposure: Long = 42
    var currentFocal: Float = 1.0F

    var sensorAccValues: MutableList<List<Float>> = mutableListOf()
    var sensorRotValues: MutableList<List<Float>> = mutableListOf()

    var capturingBurst: AtomicBoolean = AtomicBoolean(false) // are we capturing a burst
    var numCapturedFrames: AtomicInteger = AtomicInteger(0) // number of captures received
//    var numSavedFrames1: AtomicInteger = AtomicInteger(0)
//    var numSavedFrames2: AtomicInteger = AtomicInteger(0)

    /** Android ViewBinding */
    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    /** AndroidX navigation arguments */
    private val args: CameraFragmentArgs by navArgs()

    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private val cameraManager: CameraManager by lazy {
        val context = requireContext().applicationContext
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    /** [CameraCharacteristics] corresponding to the provided Camera ID */
    private var physicalCameraID: String = "2" // main

    /** Readers used as buffers for camera still shots */
    private lateinit var imageReader: ImageReader

    /** [HandlerThread] where all camera operations run */
    private val cameraThread = HandlerThread("CameraThread").apply { start() }

    /** [Handler] corresponding to [cameraThread] */
    private val cameraHandler = Handler(cameraThread.looper)

    /** [HandlerThread] where all buffer reading operations run */
    private val imageReaderThread = HandlerThread("imageReaderThread").apply { start() }

    /** [Handler] corresponding to [imageReaderThread] */
    private val imageReaderHandler = Handler(imageReaderThread.looper)

    /** The [CameraDevice] that will be opened in this fragment */
    private lateinit var camera: CameraDevice

    /** Internal reference to the ongoing [CameraCaptureSession] configured with our parameters */
    private lateinit var session: CameraCaptureSession

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission") override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
//        val characteristicsP = cameraManager.getCameraCharacteristics(physicalCameraID)
//        Log.d("f", "Pixel Array: " + characteristicsP.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE).toString())
//        Log.d("f", "Active Array: " + characteristicsP.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE).toString())

        fragmentCameraBinding.viewFinder.post {
            val width = fragmentCameraBinding.viewFinder.width
            val height = width * 4032 / 3024  // for a 4:3 aspect ratio
            val params = fragmentCameraBinding.viewFinder.layoutParams
            params.width = width
            params.height = height
            fragmentCameraBinding.viewFinder.layoutParams = params
        }

        fragmentCameraBinding.viewFinder.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceCreated(holder: SurfaceHolder) { // Selects appropriate preview size and configures view finder
                //                val previewSize = getPreviewOutputSize(fragmentCameraBinding.viewFinder.display,
                //                                                       characteristicsP,
                //                                                       SurfaceHolder::class.java)
                //                Log.d(TAG, "View finder size: ${fragmentCameraBinding.viewFinder.width} x ${fragmentCameraBinding.viewFinder.height}")
                //                Log.d(TAG, "Selected preview size: $previewSize")
                fragmentCameraBinding.viewFinder.setAspectRatio(960, 720)

                // To ensure that size is set, initialize camera in the view's thread
                view.post { initializeCamera() }
            }
        })
    }

    /**
     * Begin all camera operations in a coroutine in the main thread. This function:
     * - Opens the camera
     * - Configures the camera session
     * - Starts the preview by dispatching a repeating capture request
     * - Sets up the still image capture listeners
     */

    private fun restartCameraStream() {
        lifecycleScope.launch(Dispatchers.Main) {
            startCameraStream()
        }
    }

    @SuppressLint("ClickableViewAccessibility") private suspend fun startCameraStream() {
        val characteristicsP = cameraManager.getCameraCharacteristics(physicalCameraID)

        val size = characteristicsP.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.getOutputSizes(args.pixelFormat).maxByOrNull { it.height * it.width }!!
        imageReader = ImageReader.newInstance(size.width, size.height, args.pixelFormat, imageBufferSize, HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_CPU_WRITE_RARELY)

        // Creates list of Surfaces where the camera will output frames
        val targets = listOf(fragmentCameraBinding.viewFinder.holder.surface, imageReader.surface)

        // Start a capture session using our open camera and list of Surfaces where frames will go
        session = createCaptureSession(camera, targets, cameraHandler)

        val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(fragmentCameraBinding.viewFinder.holder.surface)
        }

        // Set UI settings

        if (navManualExposure) {
            captureRequest.apply {
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, navExposure)
                set(CaptureRequest.SENSOR_SENSITIVITY, navISO)
            }
        }

        if (navManualFocus) {
            captureRequest.apply {
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, navFocal)
            }
        } else if (navLockAF ){
            captureRequest.apply {
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            }
        } else {
            captureRequest.apply {
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }
        }

        if (navLockOIS) {
            captureRequest.apply {
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            }
        }

        val captureCallback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                super.onCaptureCompleted(session, request, result)

                currentISO = result.get(CaptureResult.SENSOR_SENSITIVITY)!!
                currentExposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)!!
                currentFrameDuration = result.get(CaptureResult.SENSOR_FRAME_DURATION)!!
                currentFocal = result.get(CaptureResult.LENS_FOCUS_DISTANCE)!!

                val inverseExposure = 1 / (currentExposure / 1e9)
                val roundedFocal = String.format("%.2f", currentFocal) // update display info
                val text = "<font color=#808080>ISO:</font> ${currentISO} <font color=#808080>Exposure:</font>1/${inverseExposure.toInt()} <font color=#808080>Focus:</font> ${roundedFocal}"
                requireActivity().runOnUiThread {
                    navInfoTextView.text = Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY)
                }

            }
        }

        // This will keep sending the capture request as frequently as possible until the
        // session is torn down or session.stopRepeating() is called
        session.setRepeatingRequest(captureRequest.build(), captureCallback, cameraHandler)

        fragmentCameraBinding.viewFinder.setOnTouchListener { view, motionEvent ->
            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> { // refocus when you tap the viewfinder

                    captureRequest.apply {
                        set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                    }
                    session.capture(captureRequest.build(), captureCallback, cameraHandler)
                }
            }
            true
        }

        fragmentCameraBinding.captureButton.setOnTouchListener { view, motionEvent ->
            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> { // When the button is pressed down, start taking photos
                    view.isPressed = true
                    numCapturedFrames.set(0)
                    capturingBurst.set(true)
                    createDataFolder(navFilename)

                    fragmentCameraBinding.overlay.background = ContextCompat.getDrawable(requireContext(), R.drawable.border)

                    // Perform I/O heavy operations in a different scope
                    session.stopRepeating() // pause the live viewfinder for resources

                    val burstDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
                    val cameraName = arrayOf("MAIN", "UW", "TELE", "2X", "5X")[navSelectedCamera]
                    lifecycleScope.launch(burstDispatcher) {
                        takeBurst(imageReader, cameraName, characteristicsP, AtomicInteger(0))
                    }

                }

                MotionEvent.ACTION_UP -> { // When the button is released, stop taking photos
                    view.isPressed = false
                    capturingBurst.set(false)
                    saveMotion() // save gyro/accelerometer
                    saveCharacteristics(characteristicsP) // save camera characteristics
                    lifecycleScope.launch(Dispatchers.Main) {
                        if (navTrash) {
                            saveFolderDir.deleteRecursively()
                        }
                    }
                    fragmentCameraBinding.overlay.background = null
                    session.setRepeatingRequest(captureRequest.build(), captureCallback, cameraHandler) // restart viewfinder
                }
            }
            true
        }
    }

    @SuppressLint("ClickableViewAccessibility") private fun initializeCamera() = lifecycleScope.launch(Dispatchers.Main) {

        // Open the selected camera
        camera = openCamera(cameraManager, args.cameraId, cameraHandler)

        startCameraStream()

        // Set up motion listeners for gyro/accelerometer
        val sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val linearAccelerationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        val sensorEventListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (!capturingBurst.get()) {
                    when (event.sensor.type) {
                        Sensor.TYPE_LINEAR_ACCELERATION -> { // time, x, y, z
//                            Log.d(TAG, "Acceleration: " +
//                                    event.values[0].toString().slice(0..4) + " " +
//                                    event.values[1].toString().slice(0..4) + " " +
//                                    event.values[2].toString().slice(0..4))
                            sensorAccValues.add(listOf(event.timestamp.toFloat(), event.values[0], event.values[1], event.values[2]))
                        }

                        Sensor.TYPE_ROTATION_VECTOR -> {
                            val quaternion = FloatArray(4)
                            SensorManager.getQuaternionFromVector(quaternion, event.values) // time, w, x, y, z
                            sensorRotValues.add(listOf(event.timestamp.toFloat(), quaternion[0], quaternion[1], quaternion[2], quaternion[3]))
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }

        sensorManager.registerListener(sensorEventListener, linearAccelerationSensor, 5000)
        sensorManager.registerListener(sensorEventListener, rotationVectorSensor, 5000)

        // Set up UI listeners (buttons, switches, etc)

        val drawerLayout = requireActivity().findViewById<DrawerLayout>(R.id.drawer_layout)
        drawerLayout.setScrimColor(Color.TRANSPARENT)

        val navEditFilename = requireActivity().findViewById<EditText>(R.id.filename_input)
        val navEditExposure = requireActivity().findViewById<EditText>(R.id.nav_exposure)
        val navEditISO = requireActivity().findViewById<EditText>(R.id.nav_iso)
        val navEditFocal = requireActivity().findViewById<EditText>(R.id.nav_focal)
        val navEditMaxFPS = requireActivity().findViewById<EditText>(R.id.nav_max_fps)
        val navSwitchLockAE = requireActivity().findViewById<Switch>(R.id.nav_lock_AE)
        val navSwitchLockAF = requireActivity().findViewById<Switch>(R.id.nav_lock_AF)
        val navSwitchLockOIS = requireActivity().findViewById<Switch>(R.id.nav_lock_OIS)
        val navSwitchManualE = requireActivity().findViewById<Switch>(R.id.nav_manual_exposure)
        val navSwitchManualF = requireActivity().findViewById<Switch>(R.id.nav_manual_focus)
        val navSwitchTrash = requireActivity().findViewById<Switch>(R.id.nav_trash)
        val navRadioCamera = requireActivity().findViewById<RadioGroup>(R.id.radio_group)

        navInfoTextView = requireActivity().findViewById(R.id.text_info)
        navFramesValue = requireActivity().findViewById(R.id.frames_value)

        navEditFilename.setOnEditorActionListener { v, actionId, event ->
            if (v.text.isNotEmpty() && actionId == EditorInfo.IME_ACTION_DONE || event?.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ENTER) {
                navFilename = v.text.toString()
            }
            navEditFilename.isCursorVisible = false // turn off cursor when done
            false
        }

        navEditFilename.setOnClickListener {
            navEditFilename.isCursorVisible = true // turn on cursor when editing
        }

        navEditExposure.setOnEditorActionListener { v, actionId, event ->
            if (v.text.isNotEmpty() && actionId == EditorInfo.IME_ACTION_DONE || event?.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ENTER) {
                navExposure = (1 / v.text.toString().toFloat() * 1e9).toLong() // to nanoseconds
                restartCameraStream()
            }
            navEditExposure.isCursorVisible = false
            false
        }

        navEditExposure.setOnClickListener {
            navEditExposure.isCursorVisible = true
        }

        navEditISO.setOnEditorActionListener { v, actionId, event ->
            if (v.text.isNotEmpty() &&  actionId == EditorInfo.IME_ACTION_DONE || event?.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ENTER) {
                navISO = v.text.toString().toInt()
                restartCameraStream()
            }
            navEditISO.isCursorVisible = false
            false
        }

        navEditISO.setOnClickListener {
            navEditISO.isCursorVisible = true
        }

        navEditFocal.setOnEditorActionListener { v, actionId, event ->
            if (v.text.isNotEmpty() &&  actionId == EditorInfo.IME_ACTION_DONE || event?.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ENTER) {
                navFocal = v.text.toString().toFloat()
                restartCameraStream()
            }
            navEditFocal.isCursorVisible = false
            false
        }

        navEditFocal.setOnClickListener {
            navEditFocal.isCursorVisible = true
        }

        navEditMaxFPS.setOnEditorActionListener { v, actionId, event ->
            if (v.text.isNotEmpty() &&  actionId == EditorInfo.IME_ACTION_DONE || event?.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ENTER) {
                navMaxFPS = v.text.toString().toFloat()
            }
            navEditMaxFPS.isCursorVisible = false
            false
        }

        navEditMaxFPS.setOnClickListener {
            navEditMaxFPS.isCursorVisible = true
        }

        navSwitchLockAE.setOnCheckedChangeListener { _, isChecked ->
            navLockAE = isChecked
        }

        navSwitchLockAF.setOnCheckedChangeListener { _, isChecked ->
            navLockAF = isChecked
            restartCameraStream()
        }

        navSwitchLockOIS.setOnCheckedChangeListener { _, isChecked ->
            navLockOIS = isChecked
            restartCameraStream()
        }

        navSwitchManualE.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                navManualExposure = true
                navISO = currentISO
                navExposure = currentExposure
                navEditISO.setText(currentISO.toString())
                navEditExposure.setText((1 / (currentExposure / 1e9)).toInt().toString())
            } else {
                navManualExposure = false
            }
            restartCameraStream()
        }

        navSwitchManualF.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                navManualFocus = true
                navFocal = currentFocal
                navEditFocal.setText(currentFocal.toString())
            } else {
                navManualFocus = false
            }
            restartCameraStream()
        }

        navSwitchTrash.setOnCheckedChangeListener { _, isChecked ->
            navTrash = isChecked
        }

        navRadioCamera.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.nav_camera_main -> {
                    physicalCameraID = "2"
                    navSelectedCamera = 0
                }

                R.id.nav_camera_uw -> {
                    physicalCameraID = "3"
                    navSelectedCamera = 1
                }

                R.id.nav_camera_tele -> {
                    physicalCameraID = "4"
                    navSelectedCamera = 2
                }

                R.id.nav_camera_2X -> {
                    physicalCameraID = "5"
                    navSelectedCamera = 3
                }

                R.id.nav_camera_10X -> {
                    physicalCameraID = "6"
                    navSelectedCamera = 4
                }

            }
            restartCameraStream()
        }
    }

    /** Opens the camera and returns the opened device (as the result of the suspend coroutine) */
    @SuppressLint("MissingPermission") private suspend fun openCamera(manager: CameraManager, cameraId: String, handler: Handler? = null): CameraDevice = suspendCancellableCoroutine { cont ->

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) = cont.resume(device)

            override fun onDisconnected(device: CameraDevice) {
                Log.w(TAG, "Camera $cameraId has been disconnected")
                requireActivity().finish()
            }

            override fun onError(device: CameraDevice, error: Int) {
                val msg = when (error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }
                val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                Log.e(TAG, exc.message, exc)
                if (cont.isActive) cont.resumeWithException(exc)
            }
        }, handler)
    }

    /**
     * Starts a [CameraCaptureSession] and returns the configured session (as the result of the
     * suspend coroutine
     */
    private suspend fun createCaptureSession(device: CameraDevice, targets: List<Surface>, handler: Handler? = null): CameraCaptureSession = suspendCoroutine { cont ->

        // Create a capture session using the predefined targets; this also involves defining the
        // session state callback to be notified of when the session is read

        val outputConfigsLogical = OutputConfiguration(targets[0])
        outputConfigsLogical.setPhysicalCameraId(physicalCameraID) // physical ID
        val outputConfigsAll: List<OutputConfiguration>

        val outputConfigsPhysical = OutputConfiguration(targets[1])
        outputConfigsPhysical.setPhysicalCameraId(physicalCameraID) // physical ID

        // Put all the output configurations into a single flat array
        outputConfigsAll = listOf(outputConfigsLogical, outputConfigsPhysical)

        val executor: Executor = Executors.newSingleThreadExecutor()

        val sessionConfiguration = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputConfigsAll, executor, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)
            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("Camera ${device.id} session configuration failed")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
        }) //
        device.createCaptureSession(sessionConfiguration)
    }

    /**
     * Helper function used to capture a still image using the [CameraDevice.TEMPLATE_STILL_CAPTURE]
     * template. It performs synchronization between the [CaptureResult] and the [Image] resulting
     * from the single capture, and outputs a [CombinedCaptureResult] object.
     */
    private suspend fun takeBurst(imageReader: ImageReader, cameraName: String, characteristicsP: CameraCharacteristics, numRecordedFrames: AtomicInteger) = coroutineScope {

        // Flush any images left in the image reader
        @Suppress("ControlFlowWithEmptyBody") while (imageReader.acquireNextImage() != null) {
        }
        Log.d(TAG, "Starting capture.") // Start a new image queue
        val imageQueue = ArrayBlockingQueue<Image>(imageBufferSize)
        val requestHashQueue = ConcurrentLinkedQueue<Int>()
        val requestsInFlight = AtomicInteger(0) // how many capture requests are in flight
        val dispatcherWorkStealing = Executors.newWorkStealingPool(Runtime.getRuntime().availableProcessors()).asCoroutineDispatcher()

        imageReader.setOnImageAvailableListener({ reader ->
                                                    val image = reader.acquireNextImage()
                                                    Log.d(TAG, "Image available in queue: ${image.timestamp}")
                                                    imageQueue.add(image)
                                                }, imageReaderHandler)

        while (capturingBurst.get()) {
            delay(((1/navMaxFPS) * 1000).toLong())
            while (requestsInFlight.get() > (imageBufferSize - 4)) { // don't exceed the buffer size
                delay(3)
                Log.d(TAG, "waiting on requests in flight")
            }
            Log.d(TAG, "Request in flight.")
            requestsInFlight.set(requestsInFlight.get() + 1)

            // Set the settings

            val captureRequest = session.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(imageReader.surface)
                addTarget(fragmentCameraBinding.viewFinder.holder.surface)
                set(CaptureRequest.CONTROL_AWB_LOCK, true) // always lock white balance
            }

            if (navManualExposure) {
                captureRequest.apply {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, navExposure)
                    set(CaptureRequest.SENSOR_SENSITIVITY, navISO)
                }
            } else if (navLockAE) {
                captureRequest.apply {
                    set(CaptureRequest.CONTROL_AE_LOCK, true)
                }
            }

            if (navManualFocus) {
                captureRequest.apply {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, navFocal)
                }
            } else if (navLockAF) {
                captureRequest.apply {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, currentFocal)
                }
            } else {
                captureRequest.apply {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                }
            }

            if (navLockOIS) {
                captureRequest.apply {
                    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                    set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                }
            }

            session.capture(captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {

                override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                    super.onCaptureStarted(session, request, timestamp, frameNumber)
                    requestHashQueue.add(request.hashCode()) // Add this request's hash to the queue
                    Log.d(TAG, "Queued: ${request.hashCode()}")
                }

                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    super.onCaptureCompleted(session, request, result)
                    val resultTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                    val requestHash = request.hashCode()

                    navFramesValue.text = numCapturedFrames.getAndAdd(1).toString()

                    if (!requestHashQueue.contains(requestHash)) {
                        Log.d(TAG, "Capture request missing: $requestHash")
                    } else if (capturingBurst.get()) { // only record if we're capturing a burst

                        Log.d(TAG, "Capture result received: $resultTimestamp")

                        while (requestHashQueue.peek() != requestHash) { // wait our turn in line to grab an image
                            Thread.sleep(Random.nextLong(3,6))
                            if (!requestHashQueue.contains(requestHash)) { // requests disappeared
                                requestsInFlight.set(requestsInFlight.get() - 1)
                                return
                            }
                        }

                        // Dequeue images in order to find matching captures
                        while (true) { // Dequeue images while timestamps don't match
                            val image = imageQueue.poll()
                            if (image == null) {
                                val dequeuedHash = requestHashQueue.poll()
                                Log.d(TAG, "Capture abandoned: ${dequeuedHash}")
                                break
                            } else if (image.timestamp < resultTimestamp!!) {
                                image.close()
                                continue
                            } else if (image.timestamp == resultTimestamp) {
                                Log.d(TAG, "Matching image dequeued: ${image.timestamp}")

                                val dequeuedHash = requestHashQueue.poll()
                                Log.d(TAG, "Dequeued: $dequeuedHash")

                                lifecycleScope.launch(dispatcherWorkStealing) {
//                                lifecycleScope.launch(Dispatchers.IO) {
                                    saveResult(CombinedCaptureResult(image, result, imageReader.imageFormat), numRecordedFrames, cameraName, characteristicsP)
                                    image.close()
                                    requestsInFlight.getAndDecrement()
                                }
                                break
                            } else { // something went wrong
                                Log.d(TAG, "No matching image found.")
                                val dequeuedHash = requestHashQueue.poll()
                                Log.d(TAG, "Dequeued: $dequeuedHash")
                                image.close()
                                requestsInFlight.getAndDecrement()
                                break
                            }
                        }
                    } else {
                        val dequeuedHash = requestHashQueue.poll()
                        Log.d(TAG, "Capture ended, dequeued: $dequeuedHash")
                        requestsInFlight.getAndDecrement()
                    }
                }
            }, cameraHandler)
        }

        imageReader.setOnImageAvailableListener(null, null)
        requestHashQueue.clear()

        while (requestsInFlight.get() > 0) { // wait until in flight requests are cleared
            delay(3)
        }

        while (imageQueue.isNotEmpty()) {
            imageQueue.take().close()
        }

        imageReader.close()
        Log.d(TAG, "Done capturing burst.")
    }

    private fun saveMotion() {
        var motionString = ""

        sensorAccValues.forEach { data ->
            motionString += "<ACC>"
            motionString += data[0].toLong().toString() + "," // timestamp
            motionString += data.slice(1 until data.size).joinToString(separator = ",")
            motionString += "<ENDACC>"
        }

        sensorRotValues.forEach { data ->
            motionString += "<ROT>"
            motionString += data[0].toLong().toString() + "," // timestamp
            motionString += data.slice(1 until data.size).joinToString(separator = ",")
            motionString += "<ENDROT>"
        }

        val outputMotion = createFile("MOTION", "bin")
        FileOutputStream(outputMotion).use { outStream ->
            outStream.write(motionString.toByteArray())
        }

        sensorAccValues.clear()
        sensorRotValues.clear()

    }

    private fun saveCharacteristics(characteristicsP: CameraCharacteristics) {
        var charactersticString = ""
        val filterKeys = listOf("StreamCombinations") // filter keys with these tags

        for (key in characteristicsP.keys) {
            if (!filterKeys.any { key.name.contains(it, true) }) {
                var value = characteristicsP.get(key)

                val valueString = when (value) {
                    is Array<*> -> value.joinToString()
                    is List<*> -> value.joinToString()
                    is FloatArray -> value.toList().joinToString() // handle float arrays
                    is Boolean, is Int, is Double, is Float, is Long, is Byte, is Short, is Char, is String -> value.toString()
                    else -> null
                }

                if (valueString != null) {
                    charactersticString += "<KEY>"
                    charactersticString += key.name
                    charactersticString += "<ENDKEY>"
                    charactersticString += "<VALUE>"
                    charactersticString += valueString
                    charactersticString += "<ENDVALUE>"
                }
            }
        }

        val outputCharacteristics = createFile("CHARACTERISTICS", "bin")
        FileOutputStream(outputCharacteristics).use { outStream ->
            outStream.write(charactersticString.toByteArray())
        }
    }

    private fun saveResult(result: CombinedCaptureResult, numRecordedFrames: AtomicInteger, cameraName: String, characteristicsP: CameraCharacteristics) {
        when (result.format) {

            // When the format is RAW we use the DngCreator utility library
            ImageFormat.RAW_SENSOR -> {
                val metadataString = createMetadataString(result.metadata)
                val dngCreator = DngCreator(characteristicsP, result.metadata)
                dngCreator.setOrientation(ExifInterface.ORIENTATION_ROTATE_90) // rotate to portrait
                try {
                    val currentFrame = numRecordedFrames.getAndIncrement()

                    val outputImage = createFile("IMG_${cameraName}_${
                        String.format("%03d", currentFrame)
                    }", "dng")
                    val outputMetadata = createFile("IMG_${cameraName}_${
                        String.format("%03d", currentFrame)
                    }", "bin")

                    val metadataByteArray = metadataString.toByteArray()

                    BufferedOutputStream(FileOutputStream(outputImage)).use {
                        dngCreator.writeImage(it, result.image)
                    }
                    BufferedOutputStream(FileOutputStream(outputMetadata)).use { outStream ->
                        outStream.write(metadataByteArray)
                    }

                    Log.d(TAG, "Image saved: ${outputImage.absolutePath}")
                } catch (exc: IOException) {
                    Log.e(TAG, "Unable to write DNG image to file", exc)
                }
            }

            // No other formats are supported by this sample
            else -> {
                val exc = RuntimeException("Unknown image format: ${result.image.format}")
                Log.e(TAG, exc.message, exc)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            camera.close()
        } catch (exc: Throwable) {
            Log.e(TAG, "Error closing camera", exc)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraThread.quitSafely()
        imageReaderThread.quitSafely()
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()
    }

    /** Helper data class used to hold capture metadata with their associated image */
    data class CombinedCaptureResult(val image: Image, val metadata: CaptureResult, val format: Int) : Closeable {
        override fun close() = image.close()
    }

    /**
     * Create a [File] named a using formatted timestamp with the current date and time.
     *
     * @return [File] created.
     */
    private fun createMetadataString(metadata: CaptureResult): String {
        var metadataString = ""
        val filterKeys = listOf("internal", "lyric", "vendor", "experimental", "com.google") // filter keys with these tags
        for (key in metadata.keys) {
            if (!filterKeys.any { key.name.contains(it, true) }) {
                metadataString += "<KEY>"
                metadataString += key.name
                metadataString += "<ENDKEY>"
                var value = metadata.get(key)
                metadataString += "<VALUE>"

                metadataString += when (value) {
                    is Array<*> -> value.joinToString()
                    is List<*> -> value.joinToString()
                    is FloatArray -> value.toList().joinToString() // handle float arrays
                    else -> value.toString()
                }
                metadataString += "<ENDVALUE>"
            }
        }
        return metadataString
    }

    private fun createDataFolder(descriptor: String = "") {
        val date = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US).format(Date())
        val documentFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        saveFolderDir = File(documentFolder, date + "-" + descriptor)
        saveFolderDir.mkdirs()
    }

    private fun createFile(descriptor: String, extension: String): File {
        return File(saveFolderDir, "$descriptor.$extension")
    }

}

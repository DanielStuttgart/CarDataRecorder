package com.example.cardatarecorder

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent.getActivity
import android.bluetooth.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.fragment.app.FragmentActivity
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


// tutorial see https://developer.android.com/codelabs/camerax-getting-started#1

class MainActivity : AppCompatActivity(), SensorEventListener, LocationListener {

    // sensor stuff
    private lateinit var sensorManager: SensorManager
    private lateinit var txt_gps: TextView
    private lateinit var lin_pos: TextView
    private lateinit var linear: TextView
    private lateinit var txt_info: TextView
    private var mLinear: Sensor? = null
    private var mLight: Sensor? = null
    private val grav: FloatArray = FloatArray(3)

    // camera stuff
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var preview_view: PreviewView? = null
    //private var overlay_view: ImageView? = null
    private var recording: Recording? = null
    private lateinit var cmd_start_record: ImageButton
    private lateinit var cmd_take_img: ImageButton
    private lateinit var cmd_change_resolution: ImageButton
    private lateinit var cmd_add_comment: ImageButton
    private lateinit var cmd_enable_ai: ImageButton
    private lateinit var cmd_add_text: ImageButton
    private lateinit var cmd_enable_obd: ImageButton
    private lateinit var prg_Speed: ProgressBar
    private lateinit var txt_prg_Speed: TextView

    private lateinit var locationManager: LocationManager
    private lateinit var objectDetector: ObjectDetector
    private lateinit var objectDetectorListener: DetectorListener
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var bitmapBuffer: Bitmap

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bluetoothChatService: BluetoothChatService
    // uuid from https://www.uuidgenerator.net/
    private val MY_UUID: UUID = UUID.fromString("e4332dbc-cd3f-4323-8d6a-70c604d90659")

    private lateinit var overlay: OverlayView

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        //viewBinding = ActivityMainBinding.inflate(layoutInflater)
        //setContentView(viewBinding.root)
        txt_gps = findViewById(R.id.gps)
        lin_pos = findViewById(R.id.linear_pos)
        linear = findViewById(R.id.linear)
        txt_info = findViewById(R.id.info)

        cmd_start_record = findViewById(R.id.cmd_start_record)
        cmd_take_img = findViewById(R.id.cmd_take_img)
        preview_view = findViewById(R.id.preview_view)
        overlay = findViewById(R.id.overlay_view)
        cmd_change_resolution = findViewById(R.id.cmd_change_resolution)
        cmd_add_comment = findViewById(R.id.cmd_add_comment)
        cmd_add_text = findViewById(R.id.cmd_add_text)
        cmd_enable_ai = findViewById(R.id.cmd_enable_ai)
        cmd_enable_obd = findViewById(R.id.cmd_enable_obd)

        prg_Speed = findViewById(R.id.prgSpeed_GPS)
        txt_prg_Speed = findViewById(R.id.progress_tv)

        // setup sensors
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        mLinear = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, SensorManager.SENSOR_DELAY_NORMAL)
        }
        mLight = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, SensorManager.SENSOR_DELAY_NORMAL)
        }

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // Set up the listeners for take photo and video capture buttons
        cmd_take_img.setOnClickListener{takePhoto()}
        cmd_start_record.setOnClickListener {captureVideo()}
        cmd_change_resolution.setOnClickListener {change_resolution()}
        cmd_add_comment.setOnClickListener {add_comment()}
        cmd_add_text.setOnClickListener {add_text()}
        cmd_enable_ai.setOnClickListener {enable_ai()}
        cmd_enable_obd.setOnClickListener{enable_obd()}

        // set up GPS
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "No permission for GPS - fine location")
        }
        else {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 5f, this)
        }

    }

    // function to connect with bluetooth
    private fun init_bluetooth() {
        // get bluetooth adapter
        val bluetoothManager: BluetoothManager = this.getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = bluetoothManager.getAdapter()
        if (bluetoothAdapter == null) {
            // Device doesn't support Bluetooth
            Log.d(TAG, "Device does not support Bluetooth")
        }

        // enable bluetooth
        if (bluetoothAdapter?.isEnabled == false) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val REQUEST_ENABLE_BT : Int = 0
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
        }

        // get paired devices
        val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter?.bondedDevices
        val pairedDevicesName: ArrayList<String> = ArrayList()
        val pairedDevicesAdr: ArrayList<String> = ArrayList()
        pairedDevices?.forEach { device ->
            pairedDevicesName.add(device.name)
            pairedDevicesAdr.add(device.address)
        }
        Log.d(TAG, "Found Paired Device: ${pairedDevicesName}")

        // add empty device to search for new devices
        pairedDevicesName.add("New")
        pairedDevicesAdr.add("None")

        // show dialog with detected devices
        val builder = AlertDialog.Builder(this)
        with(builder) {
            setTitle("Choose Bluetooth Device")
            setItems(pairedDevicesName.toTypedArray()) {dialog, which ->
                Log.d(TAG, "Clicked on ${which} device")
                chosen_bluetooth_name = pairedDevicesName[which]
                chosen_bluetooth_adr = pairedDevicesAdr[which]

                // call connect_bluetooth in order to connect to chosen bluetooth device
                connect_bluetooth()
            }
            show()
        }

        // make our own device discoverable
        /*val requestCode = 1;
        val discoverableIntent: Intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        }
        startActivityForResult(discoverableIntent, requestCode)        */
    }

    private fun connect_bluetooth() {
        bluetoothChatService = BluetoothChatService(this, mHandler)
        val device : BluetoothDevice = bluetoothAdapter.getRemoteDevice(chosen_bluetooth_adr)
        bluetoothChatService.connect(device, true)
    }

    class Own_handler : Handler() {
        // constants used within this class
        // Message types sent from the BluetoothChatService Handler
        var MESSAGE_STATE_CHANGE = 1
        var MESSAGE_READ = 2
        var MESSAGE_WRITE = 3
        var MESSAGE_DEVICE_NAME = 4
        var MESSAGE_TOAST = 5

        // Key names received from the BluetoothChatService Handler
        var DEVICE_NAME = "device_name"
        var TOAST = "toast"
        override fun handleMessage(msg: Message) {
            //val activity: FragmentActivity = getActivity()
            when (msg.what) {
                MESSAGE_STATE_CHANGE -> when (msg.arg1) {
                    BluetoothChatService.STATE_CONNECTED -> {
                        //setStatus(getString(R.string.title_connected_to, mConnectedDeviceName))
                        //mConversationArrayAdapter.clear()
                    }
                    /*BluetoothChatService.STATE_CONNECTING -> setStatus(R.string.title_connecting)
                    BluetoothChatService.STATE_LISTEN, BluetoothChatService.STATE_NONE -> setStatus(
                        R.string.title_not_connected
                    )*/
                }
                MESSAGE_WRITE -> {
                    val writeBuf = msg.obj as ByteArray
                    // construct a string from the buffer
                    val writeMessage = String(writeBuf)
                    //mConversationArrayAdapter.add("Me:  $writeMessage")
                }
                MESSAGE_READ -> {
                    val readBuf = msg.obj as ByteArray
                    // construct a string from the valid bytes in the buffer
                    val readMessage = String(readBuf, 0, msg.arg1)
                    //mConversationArrayAdapter.add(mConnectedDeviceName + ":  " + readMessage)
                }
                MESSAGE_DEVICE_NAME -> {
                    // save the connected device's name
                    //mConnectedDeviceName = msg.getData().getString(Constants.DEVICE_NAME)
                    /*if (null != activity) {
                        Toast.makeText(
                            activity, "Connected to "
                                    + mConnectedDeviceName, Toast.LENGTH_SHORT
                        ).show()
                    }*/
                }
                /*MESSAGE_TOAST -> if (null != activity) {
                    Toast.makeText(
                        activity, msg.getData().getString(TOAST),
                        Toast.LENGTH_SHORT
                    ).show()
                }*/
            }
        }
    }


    // The Handler that gets information back from the BluetoothChatService
    private val mHandler: Own_handler = Own_handler()

    // function to search for Bluetooth OBD devices
    private fun enable_obd() {
        if(obd_enabled == false) {
            obd_enabled = true
            cmd_enable_obd.setColorFilter(Color.GREEN)

            init_bluetooth()

        } else {
            obd_enabled = false
            cmd_enable_obd.setColorFilter(Color.WHITE)

        }

    }

    // Tutorial
    // https://www.tensorflow.org/lite/android/tutorials/object_detection
    private fun enable_ai() {
        val maxResults: Int = 5
        val minDetectionThreshold: Float = 0.5f
        val numThreads: Int = 2
        val currentDelegate: Int = 0

        if(ai_enabled == false) {
            // use ai now
            cmd_enable_ai.setColorFilter(Color.RED)
            ai_enabled = true

            // set threshold and max number of detections
            val optionsBuilder =
                ObjectDetector.ObjectDetectorOptions.builder()
                    .setScoreThreshold(minDetectionThreshold)
                    .setMaxResults(maxResults)

            // set max number of threads
            val baseOptionsBuilder = BaseOptions.builder().setNumThreads(numThreads)

            // use specified hardware for model
            when (currentDelegate) {
                DELEGATE_CPU -> {
                    // Default
                }
                DELEGATE_GPU -> {
                    if (CompatibilityList().isDelegateSupportedOnThisDevice) {
                        baseOptionsBuilder.useGpu()
                    } else {
                        objectDetectorListener?.onError("GPU is not supported on this device")
                    }
                }
                DELEGATE_NNAPI -> {
                    baseOptionsBuilder.useNnapi()
                }
            }

            try {
                objectDetector = ObjectDetector.createFromFileAndOptions(this, "lite-model_efficientdet_lite0_detection_metadata_1.tflite", optionsBuilder.build())

                // Initialize our background executor
                cameraExecutor = Executors.newSingleThreadExecutor()

            } catch (e: IllegalStateException) {
                objectDetectorListener?.onError(
                    "Object detector failed to initialize. See error logs for details"
                )
                Log.e("Test", "TFLite failed to load model with error: " + e.message)
            }

        } else {
            cmd_enable_ai.setColorFilter(Color.WHITE)
            ai_enabled = false


        }

        startCamera()
    }

    private fun detect(image: ImageProxy) {
        // Copy out RGB bits to the shared bitmap buffer
        image.use { bitmapBuffer.copyPixelsFromBuffer(image.planes[0].buffer) }
        val imageRotation = image.imageInfo.rotationDegrees
        // Pass Bitmap and rotation to the object detector helper for processing and detection
        detectObjects(bitmapBuffer, imageRotation)
    }

    private fun detectObjects(image: Bitmap, imageRotation: Int) {
        // Inference time is the difference between the system time at the start and finish of the
        // process
        var inferenceTime = SystemClock.uptimeMillis()

        // Create preprocessor for the image.
        // See https://www.tensorflow.org/lite/inference_with_metadata/
        //            lite_support#imageprocessor_architecture
        // image could be rotated by Rot90Op
        val imageProcessor =
            ImageProcessor.Builder()
                .build()

        // Preprocess the image and convert it into a TensorImage for detection.
        // and image is automatically resized
        val tensorImage = imageProcessor.process(TensorImage.fromBitmap(image))

        val results = objectDetector?.detect(tensorImage)
        inferenceTime = SystemClock.uptimeMillis() - inferenceTime

        if (results != null) {
            for(result in results) {
                Log.d(TAG,"Found ${result.categories[0].label} with accuracy ${result.categories[0].score}")
            }

            overlay.invalidate()
            overlay.setResults(results, image.height, image.width)

        }
    }


    private fun add_text() {
        sensor_entry.comment = "text"
        val selectedItems = ArrayList<Int>() // Where we track the selected items
        val builder = AlertDialog.Builder(this)
        var final_text = "text: "
        // Set the dialog title
        var editText = EditText(this)
        builder.setTitle("Set Comment")
            .setView(editText)

            // Set the action buttons
            .setPositiveButton("ok",
                DialogInterface.OnClickListener { dialog, id ->
                    // User clicked OK, so save the selectedItems results somewhere
                    // or return them to the component that opened the dialog
                    final_text += editText.text

                    Log.d(TAG, "Selected ${final_text}")
                    sensor_entry.comment = final_text
                })
            .setNegativeButton("cancel",
                DialogInterface.OnClickListener { dialog, id ->
                })

        builder.show()

    }

    // add a comment
    private fun add_comment() {
        sensor_entry.comment = "comment"
        val selectedItems = ArrayList<Int>() // Where we track the selected items
        val builder = AlertDialog.Builder(this)
        val comments = arrayOf("TrafficSigns ","Lanes ","Objects ","EgoBehaviour ", "Hot Spot")
        var final_comment = "comment: "
        // Set the dialog title
        builder.setTitle("Set Comment")

            .setMultiChoiceItems(comments, null,
                DialogInterface.OnMultiChoiceClickListener { dialog, which, isChecked ->
                    if (isChecked) {
                        // If the user checked the item, add it to the selected items
                        selectedItems.add(which)
                    } else if (selectedItems.contains(which)) {
                        // Else, if the item is already in the array, remove it
                        selectedItems.remove(which)
                    }
                })
            // Set the action buttons
            .setPositiveButton("ok",
                DialogInterface.OnClickListener { dialog, id ->
                    // User clicked OK, so save the selectedItems results somewhere
                    // or return them to the component that opened the dialog
                    for(i in selectedItems) {
                        final_comment += comments[i]
                    }
                    Log.d(TAG, "Selected ${final_comment}")
                    sensor_entry.comment = final_comment
                })
            .setNegativeButton("cancel",
                DialogInterface.OnClickListener { dialog, id ->
                })

        builder.show()

    }

    // possibility to change resolution during test drive
    // standard value is lowest possible
    private fun change_resolution() {
        val builder = AlertDialog.Builder(this)
        val resolutions = arrayOf("UHD","FHD","HD","Highest","SD","Lowest")
        val resolution_quality = arrayOf(Quality.UHD, Quality.FHD, Quality.HD,Quality.HIGHEST,Quality.SD,Quality.LOWEST)
        var chosen_resolution = 0
        with(builder) {
            setTitle("Choose image quality")
            setItems(resolutions) {dialog, which ->
                Log.d(TAG, "Inside Clicked on ${which}")
                chosen_resolution = which
                // set comp variable chosen_quality
                chosen_quality = resolution_quality[which]
                startCamera()
            }
            show()
        }
    }

    // function to bind camera use-cases
    // - image capture
    // - video capture
    // - image analysis with Tensorflow
    // ToDo: Add possibility to analyze and record simultaniously
    // at the moment problem regarding usage of images yuv???
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(preview_view?.surfaceProvider)
                    //it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder()
                .build()

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(chosen_quality))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            if(ai_enabled == true) {
                // ImageAnalysis. Using RGBA 8888 to match how our models work
                imageAnalyzer =
                    ImageAnalysis.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .setTargetRotation(preview_view?.display!!.rotation)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()
                        // The analyzer can then be assigned to the instance
                        .also {
                            it.setAnalyzer(cameraExecutor) { image ->
                                if (!::bitmapBuffer.isInitialized) {
                                    // The image rotation and RGB image buffer are initialized only once
                                    // the analyzer has started running
                                    bitmapBuffer = Bitmap.createBitmap(
                                        image.width,
                                        image.height,
                                        Bitmap.Config.ARGB_8888
                                    )
                                }

                                detect(image)
                            }
                        }
            }

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                if(ai_enabled == false) {
                    // Bind use cases to camera
                    cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, imageCapture, videoCapture
                    )
                } else {
                    cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, imageAnalyzer
                    )
                }

            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))

    }

    // photo can be taken even during recording of a video for hotspots o.a.
    private fun takePhoto() {
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return

        // Create time stamped name and MediaStore entry.
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if(Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues)
            .build()

        // Set up image capture listener, which is triggered after photo has
        // been taken
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun
                        onImageSaved(output: ImageCapture.OutputFileResults){
                    val msg = "Photo capture succeeded: ${output.savedUri}"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)
                }
            }
        )
    }

    // capture video --> record video stream and store frame numbers for sync
    private fun captureVideo() {
        val videoCapture = this.videoCapture ?: return

        //viewBinding.videoCaptureButton.isEnabled = false

        val curRecording = recording
        if (curRecording != null) {
            // Stop the current recording session.
            curRecording.stop()
            recording = null
            return
        }

        // create and start a new recording session
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video")
            }
        }

        // function to write recorder images to video
        // video frame number is written to sensor_entry, s.t. this info can be
        // used to sync video and sensor data aufterwards
        // with every arriving frame new sensor-data is written to list
        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()
        recording = videoCapture.output
            .prepareRecording(this, mediaStoreOutputOptions)
            .apply {
                if (PermissionChecker.checkSelfPermission(this@MainActivity,
                        Manifest.permission.RECORD_AUDIO) == PermissionChecker.PERMISSION_GRANTED)
                {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                // within this section camera data is added to sensor_entry
                when(recordEvent) {
                    is VideoRecordEvent.Start -> {
                        cmd_start_record.setImageResource(R.drawable.ic_recording)
                        frame_number = 0
                        val recordingStats = recordEvent.recordingStats
                        sensor_entry.timestamp_videoframe = recordingStats.recordedDurationNanos
                        // write to csv --> start
                        write_csv(is_start = true, is_end = false)
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            val msg = "Video capture succeeded: " +
                                    "${recordEvent.outputResults.outputUri}"
                            Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT)
                                .show()
                            Log.d(TAG, msg)
                        } else {
                            recording?.close()
                            recording = null
                            Log.e(TAG, "Video capture ends with error: " +
                                    "${recordEvent.error}")
                        }
                        frame_number ++
                        sensor_entry.frame = frame_number
                        val recordingStats = recordEvent.recordingStats
                        sensor_entry.timestamp_videoframe = recordingStats.recordedDurationNanos
                        cmd_start_record.setImageResource(R.drawable.ic_record)
                        // write to csv --> end
                        write_csv(is_start = false, is_end = true)
                    }
                    is VideoRecordEvent.Status -> {
                        frame_number ++
                        sensor_entry.frame = frame_number
                        val recordingStats = recordEvent.recordingStats
                        sensor_entry.timestamp_videoframe = recordingStats.recordedDurationNanos

                        // write to csv --> continuous
                        write_csv(is_start = false, is_end = false)
                    }
                }
            }

    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>, grantResults: IntArray
        ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        }

    }

    override fun onResume() {
        super.onResume()
        sensorManager.registerListener(this, mLinear, SensorManager.SENSOR_DELAY_NORMAL, SensorManager.SENSOR_DELAY_NORMAL)
        sensorManager.registerListener(this, mLight, SensorManager.SENSOR_DELAY_NORMAL, SensorManager.SENSOR_DELAY_NORMAL)
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)

        // Shut down our background executor
        cameraExecutor.shutdown()

        super.onDestroy()

    }

    // whenever sensor (accelerometer or light) are changing, values are updated
    // and stored within sensor-entry
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val roll = event.values[0]     // sides
            val pitch = event.values[1]    // upDown
            val yaw = event.values[2]      //

            val lin_acc: FloatArray = FloatArray(3)
            val alpha: Float = 0.8f
            grav[0] = alpha * grav[0] + (1-alpha) * roll
            grav[1] = alpha * grav[1] + (1-alpha) * pitch
            grav[2] = alpha * grav[1] + (1-alpha) * yaw
            lin_acc[0] = roll - grav[0]
            lin_acc[1] = pitch - grav[1]
            lin_acc[2] = yaw - grav[2]

            linear.apply{
                rotationY = pitch * 3f
                rotationX = roll * 3f
                rotation = -roll
                translationY = roll * -10
                translationX = pitch * 10
            }

            lin_pos.apply{
                translationX = lin_acc[1] * 10f
                translationY = lin_acc[2] * 10f
            }

            //val color = if(pitch.toInt() == 9 && roll.toInt() == 0) Color.GREEN else Color.RED
            val color = if(roll.toInt() == 9 && pitch.toInt() == 0) Color.GREEN else Color.RED
            linear.setBackgroundColor(color)
            //square.text = "up/down ${pitch.toInt()}\nleft/right ${roll.toInt()}"
            linear.text = "[${lin_acc[0].toInt()}, ${lin_acc[1].toInt()}, ${lin_acc[2].toInt()}]"

            // data to record
            sensor_entry = sensor_data(event.timestamp, lin_acc[0], lin_acc[1], lin_acc[2], 0, 0, 0f,0f, 0f, 0f,"")

        }
        else if (event?.sensor?.type == Sensor.TYPE_LIGHT) {
            sensor_entry.light = event.values[0]
            txt_info.text = "Light: ${sensor_entry.light}"
        }
    }

    // load intent for creating csv file
    // see https://developer.android.com/training/data-storage/shared/documents-files
    // and https://www.mongodb.com/developer/languages/kotlin/realm-startactivityforresult-registerforactivityresult-deprecated-android-kotlin/
    private val get_csv_file = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {}

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if(resultCode == Activity.RESULT_OK) {
            Log.d(TAG, "Writing csv file...")
            new_uri = data?.data
            val take_flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            data?.data?.let {
                contentResolver.takePersistableUriPermission(it, take_flags)
                val os = contentResolver.openOutputStream(it)
                val writer: BufferedWriter? = os?.bufferedWriter()

                // get csv from list: list_sensor_entry.joinToString{it -> "\'${it.accel_x}\'"}
                //list_sensor_entry.joinToString{it -> "\'${it.accel_x}\',\'${it.accel_y}\'\n"}
                // see https://medium.com/@SindkarP/a-simple-use-of-jointostring-kotlin-function-to-get-comma-separated-strings-for-sqlite-cbece2bcb499
                val str_header = "timestamp,accel_x,accel_y,accel_z,timestamp_video,frame_no,longitude,latitude,speed,light,comment\n"
                val str_csv = list_sensor_entry.joinToString(separator = "\n"){entry -> "${entry.timestamp},${entry.accel_x},${entry.accel_y},${entry.accel_z},${entry.timestamp_videoframe},${entry.frame},${entry.long},${entry.lat},${entry.speed},${entry.light},${entry.comment}"}

                writer?.write(str_header + str_csv)
                writer?.close()
            }
        }
    }

    // function to write (= append) sensor data to the sensor list
    // at start, list is initialized
    // at end, list is finalized and converted to a csv text string (within get_csv_file)
    //    and its intent
    // in between, sensor entry is added to the end of the list
    fun write_csv(is_start: Boolean, is_end: Boolean): Int {

        val msg = "External media mounted correctly. Writing csv..."
        Log.d(TAG, msg)

        try {
            if (is_start) {
                // initialize new list at start
                list_sensor_entry = mutableListOf<sensor_data>(sensor_entry)

            } else if (is_end) {
                // need to correct timestamp --> subtract timestamp of first measurement as offset
                //sensor_entry.timestamp -= list_sensor_entry[0].timestamp

                // add new entry at end
                list_sensor_entry.add(sensor_entry)

                // change first timestamp to 0
                //list_sensor_entry[0].timestamp = 0

                // create file with Intent
                var name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                    .format(System.currentTimeMillis())
                name = name.replace(":", ".") + ".csv"
                val strText: String = "Diest ist ein langer Test"
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/comma-separated-values"
                    putExtra(Intent.EXTRA_TITLE, name)
                }
                get_csv_file.launch(intent)

            } else {
                // need to correct timestamp --> subtract timestamp of first measurement as offset
                //sensor_entry.timestamp -= list_sensor_entry[0].timestamp
                // add new entry
                list_sensor_entry.add(sensor_entry)
            }
            return 0
        } catch(ffe:FileNotFoundException) {
            println(ffe.message)
        } catch(ioe:IOException) {
            println(ioe.message)
        }

        return -3
    }


    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {

    }

    companion object {
        private const val TAG = "Car Data Recorder"
        private var new_uri : Uri? = null
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()

        data class sensor_data(
            var timestamp: Long,
            var accel_x: Float,
            var accel_y: Float,
            var accel_z: Float,
            var timestamp_videoframe: Long,
            var frame: Long,
            var long: Float,
            var lat: Float,
            var speed: Float,
            var light: Float,
            var comment: String
        )

        private var sensor_entry = sensor_data(0,0f,0f,0f,0, 0, 0f, 0f, 0f,  0f,"")
        private var list_sensor_entry = mutableListOf<sensor_data>()
        private var frame_number: Long = 0
        private var start_frame: Long = 0
        private var chosen_quality = Quality.LOWEST
        private var chosen_bluetooth_name = ""
        private var chosen_bluetooth_adr = ""
        private var ai_enabled = false
        private var obd_enabled = false

        const val DELEGATE_CPU = 0
        const val DELEGATE_GPU = 1
        const val DELEGATE_NNAPI = 2
        const val MODEL_MOBILENETV1 = 0
        const val MODEL_EFFICIENTDETV0 = 1
        const val MODEL_EFFICIENTDETV1 = 2
        const val MODEL_EFFICIENTDETV2 = 3

    }

        interface DetectorListener {
        fun onError(error: String)
        fun onResults(
            results: MutableList<Detection>?,
            inferenceTime: Long,
            imageHeight: Int,
            imageWidth: Int
        )
    }

    override fun onLocationChanged(p0: Location) {
        sensor_entry.long = p0.longitude.toFloat()
        sensor_entry.lat = p0.latitude.toFloat()
        sensor_entry.speed = p0.speed
        val maxSpeed = 80   // max range of speed in m/s
        val maxRange = 80   // max range of progress bar

        txt_gps.setText("Long: ${sensor_entry.long}\n Lat: ${sensor_entry.lat}\n speed: ${sensor_entry.speed}")
        txt_prg_Speed.setText(String.format("%.2f", sensor_entry.speed))
        prg_Speed.progress = ((sensor_entry.speed / maxSpeed) * maxRange).toInt()
    }

}
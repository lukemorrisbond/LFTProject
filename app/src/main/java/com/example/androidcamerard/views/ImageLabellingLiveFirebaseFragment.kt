package com.example.androidcamerard.views

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.*
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.example.androidcamerard.R
import com.example.androidcamerard.camera.GraphicOverlay
import com.example.androidcamerard.camera.CameraViewModel
import com.example.androidcamerard.recognition.Recognition
import com.example.androidcamerard.recognition.RecognitionListViewModel
import com.example.androidcamerard.utils.BitmapUtils.cropBitmapToTest
import com.example.androidcamerard.utils.BitmapUtils.imageProxyToBitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.automl.AutoMLImageLabelerLocalModel
import com.google.mlkit.vision.label.automl.AutoMLImageLabelerOptions
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit


/**
 * Main source of live image analysis within the app
 */
class ImageLabellingLiveFirebaseFragment : Fragment(), View.OnClickListener {

    // CameraX variables
    private lateinit var preview: Preview
    private lateinit var imageCapture: ImageCapture
    private lateinit var imageAnalyzer: ImageAnalysis
    private lateinit var camera: Camera
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    // UI Variables
    private lateinit var previewView: PreviewView
    private lateinit var graphicOverlay: GraphicOverlay
    private lateinit var flashButton: ImageView
    private lateinit var closeButton: ImageView
    private lateinit var photoCaptureButton: ImageButton
    private lateinit var resultTextView: TextView

    // ViewModel variables
    private val recogViewModel: RecognitionListViewModel by activityViewModels()
    private val cameraViewModel: CameraViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater,
                              container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {

        val view = inflater.inflate(R.layout.fragment_image_labelling_live, container, false)

        setUpUI(view)

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions(
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun setUpUI(view: View) {
        previewView = view.findViewById(R.id.preview_view)
        graphicOverlay = view.findViewById(R.id.graphic_overlay)
        resultTextView = view.findViewById(R.id.overlay_results_textview)
        flashButton = view.findViewById<ImageView>(R.id.flash_button).apply {
            setOnClickListener(this@ImageLabellingLiveFirebaseFragment)
        }
        closeButton = view.findViewById<ImageView>(R.id.close_button).apply {
            setOnClickListener(this@ImageLabellingLiveFirebaseFragment)
        }
        photoCaptureButton = view.findViewById<ImageButton>(R.id.photo_capture_button).apply {
            setOnClickListener(this@ImageLabellingLiveFirebaseFragment)
            // Begin session with capture button disabled - should only be enabled when valid object detected
            isEnabled = false
        }

        recogViewModel.recognitionList.observe(viewLifecycleOwner, {
            if (it.isNotEmpty()) {
                if (it[0].label == "lateral_flow_test" && it[0].confidence >= 0.8f) {
                    graphicOverlay.drawBlueRect = true
                    photoCaptureButton.isEnabled = true
                    photoCaptureButton.setImageResource(R.drawable.ic_photo_camera_24)
                    resultTextView.text =
                        String.format("Lateral Flow Test: %.1f", it[0].confidence * 100.0f)
                } else {
                    graphicOverlay.drawBlueRect = false
                    photoCaptureButton.isEnabled = false
                    photoCaptureButton.setImageResource(R.drawable.ic_photo_camera_disabled_v24)
                    resultTextView.text = getString(R.string.align_the_test_device_inside_the_box)
                }
            }
        })
    }

    @SuppressLint("UnsafeExperimentalUsageError")
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider = cameraProviderFuture.get()

            // Preview
            preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build()

            // Image Capture
            imageCapture = ImageCapture.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build()

            // Image Analysis
            val localModel = AutoMLImageLabelerLocalModel.Builder()
                .setAssetFilePath("manifest.json")
                // or .setAbsoluteFilePath(absolute file path to manifest file)
                .build()

            val autoMLImageLabelerOptions = AutoMLImageLabelerOptions.Builder(localModel)
                .setConfidenceThreshold(0.7f)  // Evaluate your model in the Firebase console
                // to determine an appropriate value.
                .build();
            val labeler = ImageLabeling.getClient(autoMLImageLabelerOptions)

            imageAnalyzer = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                // How the Image Analyser should pipe in input, 1. every frame but drop no frame, or
                // 2. go to the latest frame and may drop some frame. The default is 2.
                // STRATEGY_KEEP_ONLY_LATEST. The following line is optional, kept here for clarity
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysisUseCase: ImageAnalysis ->
                    analysisUseCase.setAnalyzer(cameraExecutor, ImageAnalyzer(labeler) { items ->
                        // updating the list of recognised objects
                        recogViewModel.updateData(items)
                    })
                }

            // Select camera, back is the default. If it is not available, choose front camera
            val cameraSelector =
                if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA))
                    CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA

            // Set viewport as equal to preview view size to allow for WYSIWYG-style analysis
            // (prevents imageProxy being cropped to 720 x 720)
            val viewport = previewView.viewPort




            try {
                // Unbind use cases before rebinding
                cameraProvider?.unbindAll()

                // Bind use cases to camera - try to bind everything at once and CameraX will find
                // the best combination. Use case group also allows viewport to be set
                val useCaseGroup = UseCaseGroup.Builder()
                    .addUseCase(preview)
                    .addUseCase(imageCapture)
                    .addUseCase(imageAnalyzer)
                    .setViewPort(viewport!!)
                    .build()

                camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    useCaseGroup
                )

                // Attach the preview to preview view, aka View Finder
                preview.setSurfaceProvider(previewView.surfaceProvider)
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

            setupAutoFocus()
        }, ContextCompat.getMainExecutor(context))
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.photo_capture_button -> takePhoto()
            R.id.close_button -> findNavController().popBackStack()
            R.id.flash_button -> updateFlashMode(flashButton.isSelected)
        }
    }

    private fun updateFlashMode(flashMode: Boolean) {
        flashButton.isSelected = !flashMode
        if (camera.cameraInfo.hasFlashUnit()) {
            camera.cameraControl.enableTorch(!flashMode)
        }

    }

    private fun takePhoto() {
        // Disable the photo capture button to prevent errors when the camera closes
        photoCaptureButton.isClickable = false
        // Get a stable reference of the modifiable image capture use case
        // Create a time-stamped output file to hold the image

        imageCapture.takePicture(cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                }

                @SuppressLint("UnsafeExperimentalUsageError")
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    val imageBitmap = imageProxyToBitmap(imageProxy)
                    val croppedBitmap = cropBitmapToTest(imageBitmap)

                    cameraViewModel.capturedImageProxy.postValue(imageProxy)
                    cameraViewModel.capturedImageBitmap.postValue(croppedBitmap)

                    // Inform analysis fragment of source to determine which UI to show
                    val args = bundleOf("SOURCE" to SOURCE)
                    findNavController()
                        .navigate(
                            R.id.action_imageLabellingLiveFirebaseFragment_to_imageAnalysisFragment,
                            args
                        )

                    imageProxy.close()
                }
            }
        )
    }

    private fun setupAutoFocus() {
        previewView.afterMeasured {
            val factory: MeteringPointFactory = SurfaceOrientedMeteringPointFactory(
                previewView.width.toFloat(), previewView.height.toFloat()
            )
            val centerWidth = previewView.width.toFloat() / 2
            val centerHeight = previewView.height.toFloat() / 2
            //create a point on the center of the view
            val autoFocusPoint = factory.createPoint(centerWidth, centerHeight)
            try {
                camera.cameraControl.startFocusAndMetering(
                    FocusMeteringAction.Builder(
                        autoFocusPoint,
                        FocusMeteringAction.FLAG_AF
                    ).apply {
                        //auto-focus every 1 seconds
                        setAutoCancelDuration(1, TimeUnit.SECONDS)
                    }.build()
                )
            } catch (e: CameraInfoUnavailableException) {
            }
        }
    }

    private inline fun View.afterMeasured(crossinline block: () -> Unit) {
        viewTreeObserver.addOnGlobalLayoutListener(object :
            OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (measuredWidth > 0 && measuredHeight > 0) {
                    viewTreeObserver.removeOnGlobalLayoutListener(this)
                    block()
                }
            }
        })
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        activity?.baseContext?.let { it1 ->
            ContextCompat.checkSelfPermission(
                it1, it
            )
        } == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(
                    activity,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                findNavController().popBackStack()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    class ImageAnalyzer(private val labeler: ImageLabeler, private val listener: RecognitionListener)
        : ImageAnalysis.Analyzer {

        @SuppressLint("UnsafeExperimentalUsageError")
        override fun analyze(imageProxy: ImageProxy) {
            val analysisTimeStart = System.currentTimeMillis()
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image =
                    InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

                val items = mutableListOf<Recognition>()

                labeler.process(image)
                    .addOnSuccessListener { labels ->

                        labels.sortByDescending { it.confidence }
                        for (label in labels) {
                            items.add(Recognition(label.text, label.confidence))
                        }
                        listener(items.toList())

                        //Log for testing speed of analysis
                        val analysisTime = System.currentTimeMillis() - analysisTimeStart
                        Log.d(TAG, "Total analysis time: $analysisTime ms")

                        // Close the image,this tells CameraX to feed the next image to the analyzer
                        imageProxy.close()
                    }

                    .addOnFailureListener { e ->
                        Log.e(TAG, e.message.toString())
                        imageProxy.close()
                    }
            }
        }
    }



    companion object {
        // Constants
        private const val TAG = "ImageLabellingLiveFB"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private const val SOURCE = "ImageCapture"
        private const val MAX_RESULT_DISPLAY = 1 // Maximum number of results displayed
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)


    }
}
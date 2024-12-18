package com.jdsanchez.pokecardscanner.analyzer

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.widget.Toast
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.lifecycle.Lifecycle
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.jdsanchez.pokecardscanner.utils.ImageUtils

/**
 * Analyzes the frames passed in from the camera and returns any detected text within the requested
 * crop region.
 */
class CardAnalyzer(
    private val context: Context,
    lifecycle: Lifecycle,
//    executor: Executor,
//    private val result: MutableLiveData<String>,
//    private val imageCropPercentages: MutableLiveData<Pair<Int, Int>>
) : ImageAnalysis.Analyzer {
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)


    val options = ObjectDetectorOptions.Builder()
        .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
        .build()
    private val objectDetector: ObjectDetector = ObjectDetection.getClient(options)

    private lateinit var objectBoundingBox: Rect;

    private var imageCropPercentages: Pair<Int, Int> = Pair(
        DESIRED_HEIGHT_CROP_PERCENT,
        DESIRED_WIDTH_CROP_PERCENT
    )

    init {
        lifecycle.addObserver(textRecognizer)
    }

    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: return

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        // We requested a setTargetAspectRatio, but it's not guaranteed that's what the camera
        // stack is able to support, so we calculate the actual ratio from the first frame to
        // know how to appropriately crop the image we want to analyze.
        val imageHeight = mediaImage.height
        val imageWidth = mediaImage.width

        val actualAspectRatio = imageWidth / imageHeight

//        val convertImageToBitmap = ImageUtils.convertYuv420888ImageToBitmap(mediaImage)
        val convertImageToBitmap = imageProxy.toBitmap()
        val cropRect = Rect(0, 0, imageWidth, imageHeight)

        // If the image has a way wider aspect ratio than expected, crop less of the height so we
        // don't end up cropping too much of the image. If the image has a way taller aspect ratio
        // than expected, we don't have to make any changes to our cropping so we don't handle it
        // here.
        val currentCropPercentages = imageCropPercentages ?: return
        if (actualAspectRatio > 3) {
            val originalHeightCropPercentage = currentCropPercentages.first
            val originalWidthCropPercentage = currentCropPercentages.second
            imageCropPercentages =
                Pair(originalHeightCropPercentage / 2, originalWidthCropPercentage)
        }

        // If the image is rotated by 90 (or 270) degrees, swap height and width when calculating
        // the crop.
        val cropPercentages = imageCropPercentages ?: return
        val heightCropPercent = cropPercentages.first
        val widthCropPercent = cropPercentages.second
        val (widthCrop, heightCrop) = when (rotationDegrees) {
            90, 270 -> Pair(heightCropPercent / 100f, widthCropPercent / 100f)
            else -> Pair(widthCropPercent / 100f, heightCropPercent / 100f)
        }

        // TODO refine this area
        cropRect.inset(
            (imageWidth * widthCrop / 2).toInt(),
            (imageHeight * heightCrop / 2).toInt()
        )
        val croppedBitmap = ImageUtils.rotateAndCrop(convertImageToBitmap, rotationDegrees, cropRect)
        recognizeTextOnDevice(InputImage.fromBitmap(croppedBitmap, 0)).addOnCompleteListener {
            imageProxy.close()
        }
    }

    private fun detectCard(image: InputImage) {
        objectDetector.process(image)
            .addOnSuccessListener { detectedObjects ->
                if ((detectedObjects == null) ||
                    (detectedObjects.size == 0) ||
                    (detectedObjects.first() == null)
                ) {
//                    previewView.overlay.clear()
//                    previewView.setOnTouchListener { _, _ -> false } //no-op
                    return@addOnSuccessListener
                }
                objectBoundingBox = detectedObjects[0].boundingBox
            }
            .addOnFailureListener { exception ->
                // Task failed with an exception
                Log.e(TAG, "Object detection error", exception)
                val message = getErrorMessage(exception)
                message?.let {
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun recognizeTextOnDevice(
        image: InputImage
    ): Task<Text> {
        // Pass image to an ML Kit Vision API
        return textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                // Task completed successfully
//                result.value = visionText.text
                logExtrasForTesting(visionText)
            }
            .addOnFailureListener { exception ->
                // Task failed with an exception
                Log.e(TAG, "Text recognition error", exception)
                val message = getErrorMessage(exception)
                message?.let {
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun getErrorMessage(exception: Exception): String? {
        val mlKitException = exception as? MlKitException ?: return exception.message
        return if (mlKitException.errorCode == MlKitException.UNAVAILABLE) {
            "Waiting for text recognition model to be downloaded"
        } else exception.message
    }

    companion object {
        private const val TAG = "TextAnalyzer"
        // We only need to analyze the part of the image that has text, so we set crop percentages
        // to avoid analyze the entire image from the live camera feed.
        const val DESIRED_WIDTH_CROP_PERCENT = 8
        const val DESIRED_HEIGHT_CROP_PERCENT = 74

        private fun logExtrasForTesting(text: Text?) {
            if (text != null) {
//                Log.v(TAG, "Detected text has : " + text.textBlocks.size + " blocks")
                for (i in text.textBlocks.indices) {
                    val lines = text.textBlocks[i].lines
//                    Log.v(TAG, String.format("Detected text block %d has %d lines", i, lines.size))
                    for (j in lines.indices) {
                        val elements = lines[j].elements
//                        Log.v(TAG, String.format("Detected text line %d has %d elements", j, elements.size))
                        for (k in elements.indices) {
                            val element = elements[k]
                            Log.v(
                                TAG,
                                String.format("Detected text element %d says: %s", k, element.text)
                            )
//                            Log.v(
//                                TAG,
//                                String.format(
//                                    "Detected text element %d has a bounding box: %s",
//                                    k,
//                                    element.boundingBox!!.flattenToString()
//                                )
//                            )
//                            Log.v(
//                                TAG,
//                                String.format(
//                                    "Expected corner point size is 4, get %d",
//                                    element.cornerPoints!!.size
//                                )
//                            )
//                            for (point in element.cornerPoints!!) {
//                                Log.v(
//                                    TAG,
//                                    String.format(
//                                        "Corner point for element %d is located at: x - %d, y = %d",
//                                        k,
//                                        point.x,
//                                        point.y
//                                    )
//                                )
//                            }
                        }
                    }
                }
            }
        }
    }
}
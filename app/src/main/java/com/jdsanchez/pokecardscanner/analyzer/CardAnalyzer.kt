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
import com.google.mlkit.vision.objects.DetectedObject
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

    private val options = ObjectDetectorOptions.Builder()
        .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
        .build()
    private val objectDetector: ObjectDetector = ObjectDetection.getClient(options)

    private lateinit var objectBoundingBox: Rect;

    init {
        lifecycle.addObserver(textRecognizer)
    }

    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: return

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        // TODO figure out rotation... 0 works
        detectCard(InputImage.fromMediaImage(mediaImage, 0))
            .continueWithTask { task ->
                if (!task.isSuccessful) {
                    task.exception?.let {
                        imageProxy.close()
                        throw it
                    }
                }

                val convertImageToBitmap = imageProxy.toBitmap()

                val cropRect = getRectForCardInfo(objectBoundingBox)

                val croppedBitmap = ImageUtils.rotateAndCrop(convertImageToBitmap, rotationDegrees, cropRect)
                recognizeTextOnDevice(InputImage.fromBitmap(croppedBitmap, 0))
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun getRectForCardInfo(rect: Rect): Rect {
        // TODO this assumes the pic is rotated 90 degrees, fix it
        val widthFraction = 9 * rect.width() / 10
        val heightFraction = rect.height() / 2
        return Rect(rect.left + widthFraction, rect.top + heightFraction, rect.right, rect.bottom)
    }

    private fun detectCard(image: InputImage): Task<List<DetectedObject>> {
         return objectDetector.process(image)
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
                        }
                    }
                }
            }
        }
    }
}
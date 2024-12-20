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
import okhttp3.HttpUrl

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
                val apiUrl = analyzeCardText(visionText)
                if (apiUrl.isNotEmpty()) {
                    Toast.makeText(context, apiUrl, Toast.LENGTH_SHORT).show()
                }

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

        private val setNumberPattern = Regex("\\d{3}/(\\d{3})")
        private val tripleDigitPattern = Regex("\\d{3}")
        private val setCodeMatcher = Regex("(G|)?([A-Z]{3})(EN)?")

        private const val BASE_API_URL = "https://1dj438lpp7.execute-api.us-east-2.amazonaws.com/api/cards"

        private fun buildApiUrl(cardNumber: String, setCode: String): String {
            val base = HttpUrl.parse(BASE_API_URL);
            return base.newBuilder()
                .addQueryParameter("setCode", setCode)
                .addQueryParameter("cardNumber", cardNumber)
                .build()
                .toString()
        }

        private fun analyzeCardText(text: Text?): String {
            if (text != null) {
                var firstMatch: String = ""
                for (i in text.textBlocks.indices) {
                    val lines = text.textBlocks[i].lines
                    for (j in lines.indices) {
                        val elements = lines[j].elements
                        for (k in elements.indices) {
                            val element = elements[k]
                            Log.v(TAG, String.format("Detected text element %d says: %s", k, element.text))

                            val setNumberMatchResult = setNumberPattern.find(element.text)
                            if (setNumberMatchResult != null) {
                                val cardNumber = setNumberMatchResult.groups[0]?.value
                                Log.v(TAG, "Found card number: $cardNumber")
//                                val totalCards = setNumberMatchResult.groups[1]?.value

                                var previousText = ""
                                // TODO handle case where previousText is "EN" (need to get previous previous)
                                // look for setCode in either the previous element or the previous line
                                if (k > 0) {
                                    previousText = elements[k - 1].text
                                } else if (j > 0) {
                                    previousText = lines[j - 1].elements[0].text
                                }

                                if (previousText.isNotEmpty()) {
                                    val maybeSetCode = setCodeMatcher.find(previousText)
                                    if (maybeSetCode != null) {
                                        val setCode = maybeSetCode.groups[2]?.value
                                        Log.v(TAG, "Found set code: $setCode")

                                        val apiUrl = buildApiUrl(cardNumber!!, setCode!!)
                                        Log.v(TAG, "API URL: $apiUrl")
                                        return apiUrl
                                    }
                                }
                            }

                            // TODO use this for energy cards
                            tripleDigitPattern.findAll(element.text).forEach { r ->
                                if (firstMatch.isEmpty()) {
                                    firstMatch = r.value
                                    Log.v(TAG, "First triple digit match: $firstMatch")
                                }
                            }
                        }
                    }
                }
            }
            return ""
        }
    }
}
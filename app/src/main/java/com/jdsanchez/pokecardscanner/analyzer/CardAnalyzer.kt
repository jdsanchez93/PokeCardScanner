package com.jdsanchez.pokecardscanner.analyzer

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import androidx.core.graphics.toRect
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
import com.jdsanchez.pokecardscanner.GraphicOverlay
import com.jdsanchez.pokecardscanner.utils.ImageUtils
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull


/**
 * Analyzes the frames passed in from the camera and returns any detected text within the requested
 * crop region.
 */
class CardAnalyzer(
    private val context: Context,
    lifecycle: Lifecycle,
    private val graphicOverlay: GraphicOverlay,
    private val previewView: PreviewView
) : ImageAnalysis.Analyzer {
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val options = ObjectDetectorOptions.Builder()

        .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
        .build()
    private val objectDetector: ObjectDetector = ObjectDetection.getClient(options)

    private lateinit var objectBoundingBox: Rect;
    private lateinit var transformedRect: Rect;

    init {
        lifecycle.addObserver(textRecognizer)
    }

    @OptIn(TransformExperimental::class)
    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: return

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        // imageProxy the output of an ImageAnalysis.
        val source: OutputTransform = ImageProxyTransformFactory().getOutputTransform(imageProxy)
        val target: OutputTransform? = previewView.outputTransform
        // Build the transform from ImageAnalysis to PreviewView
        val coordinateTransform = target?.let { CoordinateTransform(source, it) }

        detectCard(InputImage.fromMediaImage(mediaImage, 0))
            .continueWithTask { task ->
                if (!task.isSuccessful) {
                    task.exception?.let {
                        imageProxy.close()
                        throw it
                    }
                }
                // TODO think about how we use objectBoundingBox. e.g. the result of the previous task

                // 1. generate rect for PreviewView
                val detectedRect = RectF(objectBoundingBox)
                coordinateTransform?.mapRect(detectedRect)
                transformedRect = detectedRect.toRect()

                // 2. generate rect for text recognition
                val convertImageToBitmap = imageProxy.toBitmap()
                val cropRect = getRectForCardInfo(objectBoundingBox)

                val croppedBitmap = ImageUtils.rotateAndCrop(convertImageToBitmap, rotationDegrees, cropRect)
                recognizeTextOnDevice(InputImage.fromBitmap(croppedBitmap, 0))
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    @Override
    override fun getTargetCoordinateSystem(): Int {
        return ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED
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
                val apiUrl = analyzeCardText(visionText)
                if (apiUrl.isNotEmpty()) {
                    graphicOverlay.clear()
//                    previewView.setOnTouchListener { _, _ -> false } //no-op

                    val touchCallback = { v: View, e: MotionEvent ->
                        if (e.action == MotionEvent.ACTION_DOWN && transformedRect.contains(e.getX().toInt(), e.getY().toInt())) {
                            val openBrowserIntent = Intent(Intent.ACTION_VIEW)
                            openBrowserIntent.data = Uri.parse(apiUrl)
                            v.context.startActivity(openBrowserIntent)
                        }
                        true // return true from the callback to signify the event was handled
                    }

                    previewView.setOnTouchListener(touchCallback)
                    val graphic = GraphicOverlay.RectGraphic(graphicOverlay, transformedRect, apiUrl)
                    graphicOverlay.add(graphic)
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
        private val setCodeMatcher = Regex("(G)?([A-Z]{3})(EN)?")

        private const val BASE_API_URL = "https://1dj438lpp7.execute-api.us-east-2.amazonaws.com/api/cards"

        private fun buildApiUrl(cardNumber: String, setCode: String): String {
            val base = BASE_API_URL.toHttpUrlOrNull();
            if (base != null) {
                return base.newBuilder()
                    .addQueryParameter("setCode", setCode)
                    .addQueryParameter("cardNumber", cardNumber)
                    .build()
                    .toString()
            }
            return ""
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
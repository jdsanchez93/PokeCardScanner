package com.jdsanchez.pokecardscanner

import android.content.Intent
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import com.google.mlkit.vision.objects.DetectedObject

class DetectedObjectViewModel(detectedObject: DetectedObject) {
    val boundingBox = detectedObject.boundingBox
    var content: String = ""
    var touchCallback: (v: View, e: MotionEvent) -> Boolean = { v: View, e: MotionEvent -> false} //no-op

    init {
        content = "https://www.tcgcollector.com/cards/46340/exeggcute-surging-sparks-002-191"
        touchCallback = { v: View, e: MotionEvent ->
            if (e.action == MotionEvent.ACTION_DOWN && boundingBox.contains(e.getX().toInt(), e.getY().toInt())) {
                val openBrowserIntent = Intent(Intent.ACTION_VIEW)
                openBrowserIntent.data = Uri.parse(content)
                v.context.startActivity(openBrowserIntent)
            }
            true // return true from the callback to signify the event was handled
        }
    }
}
package org.fossify.messages.helpers

import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.IOException

object AttachmentUtils {
    private const val TAG = "AttachmentUtils"
    private const val ELEMENT_TAG_IMAGE: String = "img"
    private const val ELEMENT_TAG_AUDIO: String = "audio"
    private const val ELEMENT_TAG_VIDEO: String = "video"
    private const val ELEMENT_TAG_VCARD: String = "vcard"
    private const val ELEMENT_TAG_REF: String = "ref"

    private val ELEMENT_TAGS = arrayOf(
        ELEMENT_TAG_IMAGE, ELEMENT_TAG_VIDEO, ELEMENT_TAG_AUDIO, ELEMENT_TAG_VCARD, ELEMENT_TAG_REF
    )

    private val loggedMalformedMmsIds = mutableSetOf<Long>()

    fun parseAttachmentNames(text: String, mmsId: Long? = null): List<String> {
        if (text.isBlank()) return emptyList()
        
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(text.reader())
            parser.nextTag()
            return readSmil(parser)
        } catch (e: Exception) {
            // Requirement: Catch XmlPullParserException, IOException, and RuntimeException (all covered by Exception)
            // Requirement: Only one concise warning log per mmsId in the process lifecycle.
            if (mmsId == null || loggedMalformedMmsIds.add(mmsId)) {
                if (loggedMalformedMmsIds.size > 500) {
                    loggedMalformedMmsIds.clear() // Prevent memory bloat
                }
                val idInfo = if (mmsId != null) "mmsId=$mmsId" else "unknown mmsId"
                // Requirement: NO printStackTrace, NO System.err, NO Log.e with throwable.
                Log.w(TAG, "Malformed SMIL ignored, $idInfo: ${e.message}")
            }
        } catch (t: Throwable) {
            // Extreme fallback for any other non-Exception Throwable
            if (mmsId == null || loggedMalformedMmsIds.add(mmsId)) {
                Log.w(TAG, "Fatal SMIL parsing error ignored, mmsId=$mmsId")
            }
        }
        
        return emptyList()
    }

    private fun readSmil(parser: XmlPullParser): List<String> {
        parser.require(XmlPullParser.START_TAG, null, "smil")
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) {
                continue
            }

            if (parser.name == "body") {
                return readBody(parser)
            } else {
                skip(parser)
            }
        }

        return emptyList()
    }

    private fun readBody(parser: XmlPullParser): List<String> {
        val names = mutableListOf<String>()
        parser.require(XmlPullParser.START_TAG, null, "body")
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) {
                continue
            }

            if (parser.name == "par") {
                parser.require(XmlPullParser.START_TAG, null, "par")
                while (parser.next() != XmlPullParser.END_TAG) {
                    if (parser.eventType != XmlPullParser.START_TAG) {
                        continue
                    }

                    if (parser.name in ELEMENT_TAGS) {
                        val src = parser.getAttributeValue(null, "src")
                        if (!src.isNullOrBlank()) {
                            names.add(src)
                        }
                        skip(parser)
                    } else {
                        skip(parser)
                    }
                }
            } else {
                skip(parser)
            }
        }
        return names
    }

    private fun skip(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) {
            return 
        }

        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.START_TAG -> depth++
            }
        }
    }
}

package com.jn90z.openskylight

import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

internal object FeedXml {
    private data class Element(val name: String, val fields: JSONObject, val text: StringBuilder = StringBuilder()) {
        fun add(name: String, value: Any) {
            when (val prior = fields.opt(name)) {
                null -> fields.put(name, value)
                is JSONArray -> prior.put(value)
                else -> fields.put(name, JSONArray().put(prior).put(value))
            }
        }

        fun value(): Any {
            val content = text.toString().trim()
            if (fields.length() == 0) return content
            if (content.isNotEmpty()) fields.put("#text", content)
            return fields
        }
    }

    private val allowed = setOf("rss", "rdf:RDF", "channel", "item", "feed", "entry", "title",
        "link", "pubDate", "dc:date", "published", "updated")

    fun parse(xml: String): JSONObject {
        require(xml.length <= 2 * 1024 * 1024)
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        parser.setInput(StringReader(xml))
        val root = Element("", JSONObject())
        val stack = ArrayDeque<Element>().apply { addLast(root) }
        var skipped = 0
        var nodes = 0
        while (true) {
            when (parser.nextToken()) {
                XmlPullParser.END_DOCUMENT -> return root.fields
                XmlPullParser.DOCDECL -> error("Feed document types are disabled")
                XmlPullParser.START_TAG -> {
                    if (skipped > 0 || parser.name !in allowed) {
                        skipped++
                        continue
                    }
                    require(++nodes <= 8192 && stack.size < 32)
                    val fields = JSONObject()
                    if (parser.name == "link") {
                        for (key in listOf("href", "rel")) {
                            parser.getAttributeValue(null, key)?.let { fields.put("@_$key", it) }
                        }
                    }
                    stack.addLast(Element(parser.name, fields))
                }
                XmlPullParser.END_TAG -> {
                    if (skipped > 0) {
                        skipped--
                        continue
                    }
                    val closed = stack.removeLast()
                    stack.last().add(closed.name, closed.value())
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> {
                    if (skipped > 0 || stack.size <= 1) continue
                    val text = stack.last().text
                    if (text.length < 4096) text.append(parser.text.orEmpty().take(4096 - text.length))
                }
            }
        }
    }
}

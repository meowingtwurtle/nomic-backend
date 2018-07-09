package web
import com.sun.net.httpserver.HttpExchange
import org.json.JSONObject
import java.io.InputStream
import java.util.*

private val requestTextMap = Collections.synchronizedMap(WeakHashMap<HttpExchange, String>())

fun HttpExchange.setResponseTypeJSON() {
    setResponseType("application/json")
}

fun HttpExchange.setResponseType(type: String) {
    responseHeaders.add("Content-Type", type)
}

fun HttpExchange.getToken(): String? {
    return getBodyJSON()?.optString("token", null)
}

fun HttpExchange.getBodyJSON(): JSONObject? {
    return try {
        JSONObject(text())
    } catch (e: Exception) {
        null
    }
}

fun HttpExchange.sendResponse(code: Int, text: String = "") {
    val response = text.toByteArray(charset = Charsets.UTF_8)
    sendResponseHeaders(code, response.size.toLong())
    responseBody.write(response)
}

fun HttpExchange.text(): String {
    if (!requestTextMap.containsKey(this)) requestTextMap[this] = requestBody.text()
    return requestTextMap[this]!!
}

private fun InputStream.text(): String {
    return this.reader().readText()
}
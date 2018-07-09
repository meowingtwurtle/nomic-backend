package web
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import org.json.JSONObject

typealias Response = Pair<Int, JSONObject?>
typealias RequestHandler = (List<String>, JSONObject?) -> Response

object APIHandler: HttpHandler {
    val handlerMap = mapOf<String, Map<HttpMethod, RequestHandler>>()

    enum class HttpMethod {
        GET, POST, PUT, DELETE
    }

    fun parseMethod(method: String) = HttpMethod.valueOf(method)

    override fun handle(exchange: HttpExchange) {
        val cleanRequestPath = exchange.requestURI.path.replace(Regex("/$"), "").replace(Regex("^/"), "")
        val splitSections = cleanRequestPath.split("/")

        val response = internalHandle(exchange, splitSections)
        val responseText = response.second?.toString() ?: ""

        exchange.sendResponse(response.first, responseText)
    }

    private fun internalHandle(httpExchange: HttpExchange, urlSections: List<String>): Pair<Int, JSONObject?> {
        val primarySection = urlSections[0]

        val method = try {
            parseMethod(httpExchange.requestMethod)
        } catch (e: IllegalArgumentException) {
            return invalidMethod()
        }

        val prefixMap = handlerMap[primarySection]
        prefixMap ?: return notFound()

        val handler = prefixMap[method]
        handler ?: return invalidMethod()

        return handler(urlSections.subList(1, urlSections.size), httpExchange.getBodyJSON())
    }
}
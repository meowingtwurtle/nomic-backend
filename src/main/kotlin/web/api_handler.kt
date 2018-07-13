package web
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import org.json.JSONObject
import web.APIHandler.HttpMethod.*
import web.object_management.*

typealias Response = Pair<Int, JSONObject?>
typealias RequestHandler = (List<String>, JSONObject?) -> Response

object APIHandler: HttpHandler {
    val handlerMap = mapOf<String, Map<HttpMethod, RequestHandler>>(
            "proposal" to mapOf(
                    GET to ProposalRequestHandler::getProposal,
                    POST to ProposalRequestHandler::createProposal,
                    PUT to ProposalRequestHandler::editProposal
            ),
            "proposals" to mapOf(
                    GET to ProposalRequestHandler::getAllProposals
            ),
            "vote" to mapOf(
                    GET to VoteRequestHandler::getVote,
                    POST to VoteRequestHandler::setVote,
                    PUT to VoteRequestHandler::setVote
            ),
            "standing" to mapOf(
                    GET to StandingRequestHandler::getStandingRules
            ),
            "user" to mapOf(
                    POST to UserRequestHandler::handleRequest
            ),
            "points" to mapOf(
                    GET to PointsRequestHandler::getPoints
            )
    )

    enum class HttpMethod {
        GET, POST, PUT, DELETE
    }

    fun parseMethod(method: String) = HttpMethod.valueOf(method)

    override fun handle(exchange: HttpExchange) {
        try {
            val cleanRequestPath = exchange.requestURI.path.replace(Regex("/$"), "").replace(Regex("^/"), "")
            val splitSections = cleanRequestPath.split("/")

            var response: Response

            try {
                response = internalHandle(exchange, splitSections)
            } catch (e: Exception) {
                response = internalError(e.message ?: "unknown internal error")
                e.printStackTrace()
            }

            println("Response: $response")

            val responseText = response.second?.toString()?.trim() ?: ""

            exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")

            exchange.setResponseTypeJSON()

            exchange.sendResponse(response.first, responseText)
        } finally {
            exchange.close()
        }
    }

    private fun internalHandle(httpExchange: HttpExchange, urlSections: List<String>): Response {
        val primarySection = urlSections[0]

        val method = try {
            parseMethod(httpExchange.requestMethod)
        } catch (e: IllegalArgumentException) {
            return invalidMethod()
        }

        println("Received request")
        println("URL: ${httpExchange.requestURI}")
        println("Body: ${httpExchange.text()}")

        val prefixMap = handlerMap[primarySection]
        prefixMap ?: return notFound()

        val handler = prefixMap[method]
        handler ?: return invalidMethod()

        val bodyJSON = httpExchange.getBodyJSON()
        if (httpExchange.text().isNotBlank() && bodyJSON == null) {
            return badRequest("invalid JSON")
        }

        return handler(urlSections.subList(1, urlSections.size), bodyJSON)
    }
}
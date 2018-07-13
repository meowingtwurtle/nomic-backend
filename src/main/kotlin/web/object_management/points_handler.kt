package web.object_management

import org.json.JSONObject
import persistence.PointsPersistence
import persistence.UserPersistence
import web.Response
import web.badRequest
import web.success

object PointsRequestHandler {
    fun getPoints(urlParameters: List<String>, body: JSONObject?): Response {
        if (urlParameters.size != 1) return badRequest("expected 1 url parameter, the username")

        val username = urlParameters[0]
        if (!UserPersistence.userExists(username)) return badRequest("user does not exist")

        val points = PointsPersistence.getUserPoints(username)
        return success(JSONObject().put("points", points))
    }
}
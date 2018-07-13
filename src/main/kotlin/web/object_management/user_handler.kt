package web.object_management

import org.json.JSONObject
import persistence.UserPersistence
import web.*
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit

object UserRequestHandler {
    fun handleRequest(urlParameters: List<String>, body: JSONObject?): Response {
        if (body == null) return badRequest("need body")

        val invalidParameter = badRequest("expected 1 url parameter: login, logout, or check_session")

        if (urlParameters.size != 1) return invalidParameter

        return when (urlParameters[0]) {
            "login" -> handleLogin(body)
            "logout" -> handleLogout(body)
            "check_token" -> handleCheckSession(body)
            else -> invalidParameter
        }
    }

    private fun handleLogin(body: JSONObject): Response {
        if (!body.has("username") || !body.has("password")) return badRequest("need username and password JSON fields")

        val username = body.getString("username")!!
        val password = body.getString("password")!!

        if (!UserPersistence.verifyPassword(username, password)) return success(JSONObject().put("valid", false))

        UserPersistence.clearUserTokens(username)

        return success(JSONObject().put("valid", true).put("token", UserPersistence.createToken(username, Timestamp.from(Instant.now().plus(14, ChronoUnit.DAYS)))))
    }

    private fun handleLogout(body: JSONObject): Response {
        if (!body.isAuthorized()) return unauthorized("must be logged in to be logged out")

        UserPersistence.clearUserTokens(body.getUser()!!)

        return success()
    }

    private fun handleCheckSession(body: JSONObject): Response {
        if (body.getToken() == null) return badRequest("need token")

        val user = body.getUser()

        val response = JSONObject().put("valid", user != null)
        if (user != null) response.put("username", user)

        return success(response)
    }
}
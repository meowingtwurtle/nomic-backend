package web

import org.json.JSONObject

fun notFound() = 404 to JSONObject().put("success", false).put("reason", "not found")
fun unauthorized() = 404 to JSONObject().put("success", false).put("reason", "web.unauthorized")
fun invalidMethod() = 404 to JSONObject().put("success", false).put("reason", "invalid method")
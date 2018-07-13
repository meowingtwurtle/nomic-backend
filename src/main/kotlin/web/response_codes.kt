package web

import org.json.JSONObject

fun success(json: JSONObject? = JSONObject().put("success", true)) = 200 to json?.put("success", true)
fun badRequest(reason: String = "bad request", jsonObject: JSONObject? = JSONObject().put("success", false).put("reason", reason)) = 400 to jsonObject?.put("success", false)
fun notFound(reason: String = "not found", jsonObject: JSONObject? = JSONObject().put("success", false).put("reason", reason)) = 404 to jsonObject?.put("success", false)
fun unauthorized(reason: String = "unauthorized", jsonObject: JSONObject? = JSONObject().put("success", false).put("reason", reason)) = 401 to jsonObject?.put("success", false)
fun invalidMethod(reason: String = "invalid HTTP method", jsonObject: JSONObject? = JSONObject().put("success", false).put("reason", reason)) = 405 to jsonObject?.put("success", false)
fun internalError(reason: String = "internal error", jsonObject: JSONObject? = JSONObject().put("success", false).put("reason", reason)) = 500 to jsonObject?.put("success", false)
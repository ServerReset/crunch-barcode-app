package com.crunchbarcode.app.data.model

import org.json.JSONObject

data class LoginResponse(
    val uuid: String, val sessionId: String, val verified: Boolean,
    val firstName: String?, val lastName: String?
) {
    companion object {
        fun fromJson(json: JSONObject, sessionId: String): LoginResponse {
            val name = json.optJSONArray("name")
            return LoginResponse(uuid = json.getString("uuid"), sessionId = sessionId,
                verified = json.getBoolean("verified"),
                firstName = name?.optString(0), lastName = name?.optString(1))
        }
    }
}

data class UserCredentials(
    val login: String, val password: String, val uuid: String,
    val sessionId: String, val firstName: String?, val lastName: String?
)

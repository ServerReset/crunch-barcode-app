package com.crunchbarcode.app.data.model

import org.json.JSONObject

data class LoginResponse(
    val uuid: String,
    val sessionId: String,
    val verified: Boolean,
    val homeClubUuid: String?,
    val homeClubChainName: String?,
    val chainUuid: String?,
    val profileCompleted: Boolean,
    val firstName: String?,
    val lastName: String?,
    val membershipType: String?,
    val barcode: String?,
    val barcodeExpiresAt: String?
) {
    companion object {
        fun fromJson(json: JSONObject, sessionId: String): LoginResponse {
            val nameArr = json.optJSONArray("name")
            return LoginResponse(
                uuid = json.getString("uuid"),
                sessionId = sessionId,
                verified = json.getBoolean("verified"),
                homeClubUuid = json.optString("homeClubUuid"),
                homeClubChainName = json.optString("homeClubChainName"),
                chainUuid = json.optString("chainUuid"),
                profileCompleted = json.optBoolean("profileCompleted", true),
                firstName = nameArr?.optString(0),
                lastName = nameArr?.optString(1),
                membershipType = json.optString("membershipType"),
                barcode = json.optString("barcode"),
                barcodeExpiresAt = json.optString("barcodeExpiresAt")
            )
        }
    }
}

data class BarcodeResponse(
    val barcode: String?,
    val barcodeExpiresAt: String?,
    val errorMessage: String?
) {
    companion object {
        fun fromJson(json: JSONObject): BarcodeResponse {
            return BarcodeResponse(
                barcode = json.optString("barcode"),
                barcodeExpiresAt = json.optString("barcodeExpiresAt"),
                errorMessage = json.optString("errorMessage")
            )
        }
    }
}

data class UserCredentials(
    val login: String,
    val password: String,
    val uuid: String,
    val sessionId: String,
    val firstName: String?,
    val lastName: String?
)

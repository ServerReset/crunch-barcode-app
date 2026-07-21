package com.crunchbarcode.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdate(
    val latestVersion: String,
    val downloadUrl: String,
    val releaseNotes: String,
    val isNewer: Boolean
)

class UpdateChecker(
    private val currentVersion: String,
    private val repoOwner: String = "ServerReset",
    private val repoName: String = "crunch-barcode-app"
) {
    suspend fun checkForUpdate(): Result<AppUpdate> = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.github.com/repos/$repoOwner/$repoName/releases/latest")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "CrunchBarcode/1.0")
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val code = conn.responseCode
            if (code != 200) {
                return@withContext Result.failure(Exception("GitHub API returned $code"))
            }

            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)

            val tagName = json.getString("tag_name").removePrefix("v")
            val releaseNotes = json.optString("body", "")
            val assets = json.getJSONArray("assets")

            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                if (name.endsWith(".apk")) {
                    apkUrl = asset.getString("browser_download_url")
                    break
                }
            }

            if (apkUrl == null) {
                return@withContext Result.failure(Exception("No APK found in latest release"))
            }

            val isNewer = compareSemver(tagName, currentVersion) > 0
            Result.success(
                AppUpdate(
                    latestVersion = tagName,
                    downloadUrl = apkUrl,
                    releaseNotes = releaseNotes,
                    isNewer = isNewer
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun compareSemver(a: String, b: String): Int {
        val aParts = a.split(".").map { it.toIntOrNull() ?: 0 }
        val bParts = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(aParts.size, bParts.size)) {
            val aVal = aParts.getOrElse(i) { 0 }
            val bVal = bParts.getOrElse(i) { 0 }
            if (aVal != bVal) return aVal - bVal
        }
        return 0
    }
}

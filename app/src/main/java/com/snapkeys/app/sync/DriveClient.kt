package com.snapkeys.app.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Minimal Google Drive v3 client for exactly one file in the hidden
 * appDataFolder (scope drive.appdata — no access to the user's own files).
 * Plain HTTPS + JSON, so the keyboard app stays free of the heavyweight
 * Google API client libraries.
 */
class DriveClient(private val accessToken: String) {

    data class RemoteFile(val id: String, val modifiedAtMillis: Long)

    fun find(): RemoteFile? {
        val q = URLEncoder.encode("name = '$FILE_NAME'", "UTF-8")
        val body = request("GET", "$API/files?spaces=appDataFolder&q=$q&fields=files(id,modifiedTime)")
        val files = JSONObject(body.decodeToString()).getJSONArray("files")
        if (files.length() == 0) return null
        val file = files.getJSONObject(0)
        return RemoteFile(file.getString("id"), parseTime(file.getString("modifiedTime")))
    }

    fun download(id: String): ByteArray = request("GET", "$API/files/$id?alt=media")

    /** Create ([existingId] null) or overwrite; returns the file's new modifiedTime. */
    fun upload(existingId: String?, bytes: ByteArray): Long {
        val body = if (existingId == null) {
            val metadata = JSONObject()
                .put("name", FILE_NAME)
                .put("parents", JSONArray().put("appDataFolder"))
            multipartCreate(metadata, bytes)
        } else {
            request(
                "PATCH",
                "$UPLOAD/files/$existingId?uploadType=media&fields=modifiedTime",
                payload = bytes,
                contentType = "application/octet-stream",
            )
        }
        return parseTime(JSONObject(body.decodeToString()).getString("modifiedTime"))
    }

    private fun multipartCreate(metadata: JSONObject, bytes: ByteArray): ByteArray {
        val boundary = "snapkeys-sync-boundary"
        val payload =
            ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n" +
                "--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n").toByteArray() +
                bytes + "\r\n--$boundary--".toByteArray()
        return request(
            "POST",
            "$UPLOAD/files?uploadType=multipart&fields=modifiedTime",
            payload = payload,
            contentType = "multipart/related; boundary=$boundary",
        )
    }

    private fun request(
        method: String,
        url: String,
        payload: ByteArray? = null,
        contentType: String? = null,
    ): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = if (method == "PATCH") "POST" else method
            // HttpURLConnection has no native PATCH.
            if (method == "PATCH") connection.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            if (payload != null) {
                connection.doOutput = true
                contentType?.let { connection.setRequestProperty("Content-Type", it) }
                connection.outputStream.use { it.write(payload) }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val error = connection.errorStream?.readBytes()?.decodeToString() ?: ""
                throw IOException("Drive request failed: HTTP $code ${error.take(200)}")
            }
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseTime(rfc3339: String): Long =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(rfc3339)?.time ?: 0L

    private companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val FILE_NAME = "snapkeys-shortcuts.enc"
        const val TIMEOUT_MS = 15_000
    }
}

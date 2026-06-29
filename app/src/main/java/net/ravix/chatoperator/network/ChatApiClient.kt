package net.ravix.chatoperator.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.ravix.chatoperator.BuildConfig
import net.ravix.chatoperator.data.SecureSessionStore
import net.ravix.chatoperator.model.LoginResult
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class ChatApiClient {

    suspend fun login(
        serverUrl: String,
        username: String,
        password: String,
    ): LoginResult = withContext(Dispatchers.IO) {
        val baseUrl = SecureSessionStore
            .normalizeBaseUrl(serverUrl)
            .trimEnd('/')

        require(baseUrl.isNotBlank()) {
            "آدرس سرور معتبر نیست. مثال: https://socket.ravix.net"
        }
        require(username.trim().isNotBlank()) {
            "نام کاربری را وارد کنید."
        }
        require(password.isNotBlank()) {
            "رمز عبور را وارد کنید."
        }

        requestLogin(
            baseUrl = baseUrl,
            username = username.trim(),
            password = password,
        )
    }

    private fun requestLogin(
        baseUrl: String,
        username: String,
        password: String,
    ): LoginResult {
        val connection = (
            URL("$baseUrl/api/operator/login")
                .openConnection() as HttpURLConnection
            ).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 16_000
            instanceFollowRedirects = false
            doInput = true
            doOutput = true

            setRequestProperty("Accept", "application/json")
            setRequestProperty("Origin", baseUrl)
            setRequestProperty(
                "Content-Type",
                "application/json; charset=utf-8",
            )
            setRequestProperty(
                "User-Agent",
                "RavixChatOperator-Android",
            )
        }

        val requestBody = JSONObject()
            .put("username", username)
            .put("password", password)
            .toString()

        try {
            connection.outputStream.use { output ->
                output.write(requestBody.toByteArray(Charsets.UTF_8))
                output.flush()
            }

            val statusCode = connection.responseCode
            val responseStream = if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val responseText = responseStream
                ?.use {
                    BufferedReader(
                        InputStreamReader(it, Charsets.UTF_8),
                    ).readText()
                }
                .orEmpty()

            val payload = runCatching {
                JSONObject(responseText)
            }.getOrElse {
                JSONObject()
            }

            val data = payload.optJSONObject("data") ?: payload

            val cookie = connection.headerFields.entries
                .filter {
                    it.key?.equals(
                        "Set-Cookie",
                        ignoreCase = true,
                    ) == true
                }
                .flatMap {
                    it.value ?: emptyList()
                }
                .map {
                    it.substringBefore(';').trim()
                }
                .filter {
                    it.isNotBlank()
                }
                .joinToString("; ")

            val token = firstString(
                data,
                "token",
                "access_token",
                "accessToken",
                "operator_token",
                "operatorToken",
                "authToken",
                "session_token",
                "sessionToken",
                "jwt",
            ).ifBlank {
                firstString(
                    payload,
                    "token",
                    "access_token",
                    "accessToken",
                    "operator_token",
                    "operatorToken",
                    "authToken",
                )
            }

            if (statusCode !in 200..299) {
                val serverMessage = firstString(
                    payload,
                    "message",
                    "error",
                    "detail",
                    "reason",
                )

                val message = when (statusCode) {
                    401, 403 -> serverMessage.ifBlank {
                        "نام کاربری یا رمز عبور اشتباه است."
                    }
                    404 -> "مسیر ورود سرور پیدا نشد: /api/operator/login"
                    else -> serverMessage.ifBlank {
                        "خطای سرور با کد $statusCode"
                    }
                }

                error(message)
            }

            val success = payload.optBoolean(
                "ok",
                payload.optBoolean("success", true),
            )

            if (!success) {
                error(
                    firstString(
                        payload,
                        "message",
                        "error",
                        "detail",
                    ).ifBlank {
                        "اطلاعات ورود پذیرفته نشد."
                    },
                )
            }

            if (token.isBlank() && cookie.isBlank()) {
                error("سرور توکن یا نشست ورود برنگرداند.")
            }

            val socketUrl = firstString(
                data,
                "socket_url",
                "socketUrl",
                "server_url",
                "serverUrl",
            ).ifBlank {
                baseUrl
            }

            val socketPath = firstString(
                data,
                "socket_path",
                "socketPath",
            ).ifBlank {
                BuildConfig.DEFAULT_SOCKET_PATH
            }

            return LoginResult(
                token = token,
                cookie = cookie,
                socketUrl = SecureSessionStore
                    .normalizeBaseUrl(socketUrl)
                    .ifBlank { baseUrl },
                socketPath = SecureSessionStore
                    .normalizePath(socketPath),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun firstString(
        json: JSONObject,
        vararg keys: String,
    ): String {
        for (key in keys) {
            val value = json.opt(key)

            if (value != null && value != JSONObject.NULL) {
                val text = value.toString().trim()
                if (text.isNotEmpty()) {
                    return text
                }
            }
        }

        return ""
    }
}

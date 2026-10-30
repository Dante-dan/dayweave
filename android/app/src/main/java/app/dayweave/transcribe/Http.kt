package app.dayweave.transcribe

import java.net.HttpURLConnection
import java.net.URL

class HttpException(val code: Int, body: String) : Exception("HTTP $code: ${body.take(300)}") {
    val body = body
}

/** POST 一段 JSON，返回响应文本；非 2xx 抛 [HttpException]，网络问题抛 IOException。 */
fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap(), readTimeoutMs: Int = 60_000): String =
    request("POST", url, body, headers, readTimeoutMs)

fun getJson(url: String, headers: Map<String, String> = emptyMap()): String = request("GET", url, null, headers, 20_000)

private fun request(method: String, url: String, body: String?, headers: Map<String, String>, readTimeoutMs: Int): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.requestMethod = method
    conn.connectTimeout = 15_000
    conn.readTimeout = readTimeoutMs
    conn.setRequestProperty("Content-Type", "application/json")
    headers.forEach(conn::setRequestProperty)
    if (body != null) {
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray()) }
    }
    val code = conn.responseCode
    if (code !in 200..299) throw HttpException(code, conn.errorStream?.bufferedReader()?.readText().orEmpty())
    return conn.inputStream.bufferedReader().readText()
}

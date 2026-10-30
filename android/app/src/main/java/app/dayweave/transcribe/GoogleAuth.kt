package app.dayweave.transcribe

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec

/**
 * 服务账号认证。凭证放在 App 私有目录 files/google-sa.json，由 android/scripts/push-credentials.sh 写入，
 * 不打包进 APK。令牌有效期 1 小时，提前 5 分钟刷新。
 */
class GoogleAuth(private val file: File) {
    private var token: String? = null
    private var expiresAt = 0L

    val available get() = file.exists()

    @Synchronized
    fun token(): String {
        val now = System.currentTimeMillis()
        token?.takeIf { now < expiresAt - 5 * 60_000 }?.let { return it }
        val sa = Json.parseToJsonElement(file.readText()).jsonObject
        val email = sa.getValue("client_email").jsonPrimitive.content
        val tokenUri = sa.getValue("token_uri").jsonPrimitive.content
        val pem = sa.getValue("private_key").jsonPrimitive.content

        val iat = now / 1000
        val header = b64("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val claims = b64(
            """{"iss":"$email","scope":"https://www.googleapis.com/auth/cloud-platform","aud":"$tokenUri","iat":$iat,"exp":${iat + 3600}}"""
                .toByteArray(),
        )
        val key = KeyFactory.getInstance("RSA").generatePrivate(
            PKCS8EncodedKeySpec(
                Base64.decode(pem.replace(Regex("-----[A-Z ]+-----|\\s"), ""), Base64.DEFAULT),
            ),
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(key)
            update("$header.$claims".toByteArray())
            sign()
        }
        val body = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", "UTF-8") +
            "&assertion=$header.$claims.${b64(signature)}"

        val conn = URL(tokenUri).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write(body.toByteArray()) }
        if (conn.responseCode != 200) {
            throw HttpException(conn.responseCode, conn.errorStream?.bufferedReader()?.readText().orEmpty())
        }
        val response = Json.parseToJsonElement(conn.inputStream.bufferedReader().readText()).jsonObject
        token = response.getValue("access_token").jsonPrimitive.content
        expiresAt = now + response.getValue("expires_in").jsonPrimitive.content.toLong() * 1000
        return token!!
    }

    private fun b64(bytes: ByteArray) =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}

class HttpException(val code: Int, body: String) : Exception("HTTP $code: ${body.take(300)}")

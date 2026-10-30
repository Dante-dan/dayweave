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
import app.dayweave.core.tr

/**
 * 用户自己的 Google Cloud 服务账号（源码版自带密钥）。凭证放在 App 私有目录 files/google-sa.json，
 * 在 App 里导入，或用 android/scripts/push-credentials.sh 写入；不打包进 APK、不上传。令牌有效期 1 小时，提前 5 分钟刷新。
 */
class GoogleAuth(private val file: File) {
    private var token: String? = null
    private var expiresAt = 0L

    val available get() = file.exists()

    /** 服务账号所属的项目，转写和 Vertex AI 都在这个项目下计费。 */
    val projectId: String
        get() = runCatching {
            Json.parseToJsonElement(file.readText()).jsonObject.getValue("project_id").jsonPrimitive.content
        }.getOrDefault("")

    val email: String?
        get() = runCatching {
            Json.parseToJsonElement(file.readText()).jsonObject.getValue("client_email").jsonPrimitive.content
        }.getOrNull()

    /** 校验并保存服务账号 JSON。格式不对时抛 IllegalArgumentException。 */
    @Synchronized
    fun import(text: String) {
        val sa = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
        require(sa != null && sa["type"]?.jsonPrimitive?.content == "service_account") { tr("这不是服务账号 JSON", "This isn't a service account JSON") }
        require(listOf("project_id", "private_key", "client_email", "token_uri").all { sa.containsKey(it) }) { tr("服务账号 JSON 缺少字段", "The service account JSON is missing fields") }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        tmp.renameTo(file)
        token = null
        expiresAt = 0
    }

    @Synchronized
    fun clear() {
        file.delete()
        token = null
        expiresAt = 0
    }

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

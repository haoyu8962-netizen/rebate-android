package com.couli.app

import com.couli.app.clientcore.AppConfig
import com.couli.core.bridge.ConfigSignKeys
import com.couli.core.clientcore.BridgeOriginsLastGood
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * N-22（D10-2539）App 测试的签名白名单：可信源只认验签通过的 bridge_origins.jws。测试钥（env test）在本进程临时生成，
 * 不进任何构建配置；需要可信 H5 源的测试把 [jws] 放进配置 `{"bridge_origins":{"jws":…}}`，并给 ConfigStore 注入 [verifier]。
 */
internal object N22TestSigning {
    const val ENV = "test"
    const val KID = "n22-app-test-signer"
    private const val HALF = 32

    private val keyPair: KeyPair by lazy {
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    }

    /** 只含测试钥的公钥表（env test）。 */
    val keys: ConfigSignKeys by lazy { ConfigSignKeys(listOf(Triple(ENV, KID, keyPair.public.encoded))) }

    /** 生产同一个验签闭包（AppConfig.bridgeOriginsVerifier），只把运行环境换成 test、公钥表换成测试钥。 */
    fun verifier(): (JsonObject, Long?) -> BridgeOriginsLastGood? = AppConfig.bridgeOriginsVerifier(environmentId = { ENV }, keys = keys)

    /** 原夹具里的可信源 https://h5.example.test 的签名白名单（version 1）：配置里写 `"bridge_origins":$h5BridgeOrigins`。 */
    val h5BridgeOrigins: String by lazy { """{"jws":"${jws(listOf("https://h5.example.test"))}"}""" }

    /** 测试钥现签的 JWS compact（签名 64 字节 R‖S）；[badSignature] 时翻转签名末位（仍是合法 base64url、64 字节）。 */
    fun jws(
        origins: List<String>,
        version: Long = 1,
        badSignature: Boolean = false,
    ): String {
        val header = """{"alg":"ES256","kid":"$KID","typ":"couli-bridge-origins+jws"}"""
        val originsJson = origins.joinToString(",", "[", "]") { "\"$it\"" }
        val payload = """{"v":1,"app_id":"${AppConfig.APP_ID}","env":"$ENV","version":$version,"origins":$originsJson}"""
        val input = b64(header.encodeToByteArray()) + "." + b64(payload.encodeToByteArray())
        val der =
            Signature.getInstance("SHA256withECDSA").run {
                initSign(keyPair.private)
                update(input.toByteArray(Charsets.US_ASCII))
                sign()
            }
        val signature = derToP1363(der)
        if (badSignature) signature[signature.size - 1] = (signature[signature.size - 1].toInt() xor 1).toByte()
        return input + "." + b64(signature)
    }

    /** 仓库根（向上找 contract.lock，照现有读源码的守卫写法）。 */
    val root: File by lazy {
        val start = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
    }

    /**
     * 三端共用向量里某一条的 jws（core:bridge 测试资源，gitleaks 只对这一个文件放行 jwt 规则；staging 实测回包不另存进仓库）。
     */
    fun vectorJws(name: String): String =
        Json
            .parseToJsonElement(File(root, VECTORS).readText())
            .jsonObject
            .getValue("cases")
            .jsonArray
            .map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == name }
            .getValue("jws")
            .jsonPrimitive.content

    const val VECTORS = "core/bridge/src/test/resources/n22/n22-bridge-origins-vectors.json"

    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** JCA 的 DER（SEQUENCE { INTEGER r, INTEGER s }）→ 64 字节 R‖S。 */
    private fun derToP1363(der: ByteArray): ByteArray {
        var i = 2
        val out = ByteArray(HALF * 2)
        repeat(2) { index ->
            val len = der[i + 1].toInt()
            val value = der.copyOfRange(i + 2, i + 2 + len).dropWhile { it == 0.toByte() }.toByteArray()
            System.arraycopy(value, 0, out, index * HALF + HALF - value.size, value.size)
            i += 2 + len
        }
        return out
    }
}

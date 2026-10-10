package com.couli.app.clipboard

import com.couli.core.network.SecretCipher

/** 样例盐（明显的样例字节 00 01 … 1f，不是密钥）。 */
internal val SAMPLE_SALT: ByteArray = ByteArray(32) { it.toByte() }

/** 另一份样例盐（20 21 … 3f，人为换盐用）。 */
internal val OTHER_SAMPLE_SALT: ByteArray = ByteArray(32) { (it + 0x20).toByte() }

/**
 * 样例加密（Robolectric 没有 AndroidKeyStore）：逐字节异或一个样例常量再反转。只为让存储里的值不是明文、能往返；[failing] 时模拟 Keystore 暂时
 * 不可用（加解密都抛错）。
 */
internal class SampleCipherImpl : SecretCipher {
    @Volatile
    var failing = false

    override fun encrypt(plain: ByteArray): ByteArray {
        check(!failing) { "样例：Keystore 不可用" }
        return plain.map { (it.toInt() xor MASK).toByte() }.reversed().toByteArray()
    }

    override fun decrypt(sealed: ByteArray): ByteArray {
        if (failing) {
            throw com.couli.core.network
                .SecureStorageReadException("样例：Keystore 不可用")
        }
        return sealed.reversed().map { (it.toInt() xor MASK).toByte() }.toByteArray()
    }

    private companion object {
        const val MASK = 0x5a
    }
}

/** 共用的样例加密（不置失败）。 */
internal val SampleCipher = SampleCipherImpl()

package dev.cmfopen.protocol

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-128-CBC with a fixed IV, CRC32-LE and the SHA-256 key derivations (port of crypto.py). */
object Crypto {
    val AES_IV: ByteArray = Bytes.fromHex("5051525354555657606162636465665A")
    private val rng = SecureRandom()

    fun random(n: Int) = ByteArray(n).also { rng.nextBytes(it) }

    fun crc32le(data: ByteArray): ByteArray = CRC32().run { update(data); Bytes.u32le(value) }

    fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
        parts.forEach { update(it) }
        digest()
    }

    fun deriveAuthkey(rnd1: ByteArray, rnd2: ByteArray, secret: ByteArray) = sha256(rnd1, rnd2, secret).copyOf(16)
    fun deriveSessionKey(nonce: ByteArray, authkey: ByteArray) = sha256(nonce, authkey).copyOf(16)
    fun sign(rnd: ByteArray, secret: ByteArray) = sha256(rnd, secret)

    private fun cipher(mode: Int, key: ByteArray) = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
        init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(AES_IV))
    }

    fun aesEncrypt(key: ByteArray, plaintext: ByteArray): ByteArray = cipher(Cipher.ENCRYPT_MODE, key).doFinal(plaintext)
    fun aesDecrypt(key: ByteArray, ciphertext: ByteArray): ByteArray = cipher(Cipher.DECRYPT_MODE, key).doFinal(ciphertext)
}

package com.callguard.app.caller

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Key never leaves Android Keystore. Key failure propagates; never fall back to raw/hash-only numbers. */
object AndroidNumberTokenizer {
    private const val ALIAS = "callguard-number-token-v1"
    @Synchronized fun create(): NumberTokenizer {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(ALIAS, null) as? SecretKey
        val key = existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256).setUserAuthenticationRequired(false).build())
            generateKey()
        }
        return HmacNumberTokenizer(key)
    }
}

package com.callguard.app.ai
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import com.callguard.app.BuildConfig

/** Only our backend access key; DeepSeek credentials must never enter the app. */
class AiSettings(context:Context) {
    private val prefs=context.applicationContext.getSharedPreferences("cloud-ai",Context.MODE_PRIVATE)
    var enabled:Boolean
        get()=prefs.getBoolean("enabled",false)
        set(value) {prefs.edit().putBoolean("enabled",value).commit()}
    val endpoint:String get()=prefs.getString("endpoint","")!!
    val configured:Boolean get()=endpoint.isNotEmpty() && prefs.contains("encryptedKey")
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        (store.getKey("callguard-backend-key",null) as? SecretKey)?.let {return it}
        val generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder("callguard-backend-key",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }
    fun save(endpoint:String,backendKey:String) {
        val value=endpoint.trim().trimEnd('/')
        validateEndpoint(value);require(backendKey.length>=32)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
        val bytes=cipher.doFinal(backendKey.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("endpoint",value).putString("encryptedKey",Base64.encodeToString(bytes,Base64.NO_WRAP))
            .putString("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP)).commit())
    }
    fun backendKey():String {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(prefs.getString("iv","")!!,Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(prefs.getString("encryptedKey","")!!,Base64.NO_WRAP)),Charsets.UTF_8)
    }
    companion object {
        fun validateEndpoint(value:String) {
            val url=URL(value)
            require(url.userInfo==null && url.query==null && url.ref==null && url.path.isEmpty())
            require(url.protocol=="https" || (BuildConfig.DEBUG && url.protocol=="http" && url.host in setOf("127.0.0.1","localhost")))
        }
    }
}

package com.callguard.app.caller

import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.NumberParseException
import java.util.Locale
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.SecretKey

enum class NormalizationStatus { NORMALIZED, UNKNOWN }
enum class UnknownReason { EMPTY, HIDDEN, NON_TELEPHONE_URI, SHORT_CODE, INVALID, REGION_REQUIRED }
/** E.164 exists in memory only; do not log this object or persist the number. */
class NormalizedNumber(val numberToken: String, val status: NormalizationStatus,
    val e164: String?, val unknownReason: UnknownReason?)

fun interface NumberTokenizer { fun token(e164: String): String }
class HmacNumberTokenizer(private val key: SecretKey) : NumberTokenizer {
    override fun token(e164: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        return "n1:" + mac.doFinal(e164.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

class PhoneNumberNormalizer(private val tokenizer: NumberTokenizer) {
    private val util = PhoneNumberUtil.getInstance()
    fun normalize(raw: String?, defaultRegion: String?, presentationAllowed: Boolean = true): NormalizedNumber {
        fun unknown(reason: UnknownReason) = NormalizedNumber("u1:" + UUID.randomUUID(), NormalizationStatus.UNKNOWN, null, reason)
        if (!presentationAllowed) return unknown(UnknownReason.HIDDEN)
        var value = raw?.trim().orEmpty()
        if (value.isEmpty()) return unknown(UnknownReason.EMPTY)
        if (value.lowercase(Locale.ROOT) in setOf("anonymous", "unknown", "private", "restricted", "-1", "-2")) return unknown(UnknownReason.HIDDEN)
        if (value.startsWith("tel:", ignoreCase = true)) value = value.substring(4)
        else if (':' in value || '@' in value) return unknown(UnknownReason.NON_TELEPHONE_URI)
        if (value.isBlank()) return unknown(UnknownReason.EMPTY)
        // Reject extensions, URI parameters, vanity text and post-dial strings rather than merge identities.
        if (value.length > 80 || !value.matches(Regex("[+0-9() .\\-]+"))) return unknown(UnknownReason.INVALID)
        if (value.count { it == '+' } > 1 || ('+' in value && !value.startsWith("+"))) return unknown(UnknownReason.INVALID)
        val digits = value.count { it in '0'..'9' }
        if (digits in 1..6) return unknown(UnknownReason.SHORT_CODE)
        val region = defaultRegion?.uppercase(Locale.ROOT)?.takeIf { it in util.supportedRegions }
        if (!value.startsWith("+") && region == null) return unknown(UnknownReason.REGION_REQUIRED)
        return try {
            val parsed = util.parse(value, region ?: "ZZ")
            if (parsed.hasExtension() || !util.isValidNumber(parsed)) unknown(UnknownReason.INVALID)
            else {
                val e164 = util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
                NormalizedNumber(tokenizer.token(e164), NormalizationStatus.NORMALIZED, e164, null)
            }
        } catch (_: NumberParseException) { unknown(UnknownReason.INVALID) }
    }
}

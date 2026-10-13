package com.callguard.app.caller
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import javax.crypto.spec.SecretKeySpec

@RunWith(Parameterized::class)
class PhoneNumberNormalizerTest(private val raw: String?, private val region: String?, private val allowed: Boolean, private val expected: String?) {
    @Test fun normalizesOrUsesUnknownWithoutRawFallback() {
        val normalizer = PhoneNumberNormalizer(HmacNumberTokenizer(SecretKeySpec(ByteArray(32) { 7 }, "HmacSHA256")))
        val result = normalizer.normalize(raw, region, allowed)
        assertEquals(expected, result.e164)
        assertEquals(if (expected == null) NormalizationStatus.UNKNOWN else NormalizationStatus.NORMALIZED, result.status)
        assertTrue(result.numberToken.startsWith(if (expected == null) "u1:" else "n1:"))
        if (expected != null) assertFalse(result.numberToken.contains(expected))
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "case_{index}") fun cases(): List<Array<Any?>> = listOf(
            arrayOf("+12025550123", null, true, "+12025550123"),
            arrayOf("2025550123", "US", true, "+12025550123"),
            arrayOf("(202) 555-0123", "US", true, "+12025550123"),
            arrayOf("202.555.0123", "us", true, "+12025550123"),
            arrayOf("1 202 555 0123", "US", true, "+12025550123"),
            arrayOf("tel:+12025550123", null, true, "+12025550123"),
            arrayOf("TEL:+1-202-555-0123", null, true, "+12025550123"),
            arrayOf("  +1 202 555 0123  ", "GB", true, "+12025550123"),
            arrayOf("0012025550123", "GB", true, "+12025550123"),
            arrayOf("011442079460018", "US", true, "+442079460018"),
            arrayOf("+442079460018", null, true, "+442079460018"),
            arrayOf("020 7946 0018", "GB", true, "+442079460018"),
            arrayOf("tel:02079460018", "GB", true, "+442079460018"),
            arrayOf("+41 44 668 18 00", null, true, "+41446681800"),
            arrayOf("044 668 18 00", "CH", true, "+41446681800"),
            arrayOf(null, "CN", true, null), arrayOf("", "CN", true, null),
            arrayOf("   ", "CN", true, null), arrayOf("tel:", "CN", true, null),
            arrayOf("anonymous", "CN", true, null), arrayOf("PRIVATE", "CN", true, null),
            arrayOf("unknown", "CN", true, null), arrayOf("restricted", "CN", true, null),
            arrayOf("-1", "CN", true, null), arrayOf("-2", "CN", true, null),
            arrayOf("+12025550123", "US", false, null),
            arrayOf("110", "CN", true, null), arrayOf("112", "DE", true, null),
            arrayOf("911", "US", true, null), arrayOf("10086", "CN", true, null),
            arrayOf("sip:user@example.com", "US", true, null), arrayOf("https://example.com", "US", true, null),
            arrayOf("user@example.com", "US", true, null), arrayOf("+999123456789", "US", true, null),
            arrayOf("2025550123", null, true, null), arrayOf("2025550123", "ZZ", true, null),
            arrayOf("+12025550123;ext=5", "US", true, null), arrayOf("2025550123,123", "US", true, null),
            arrayOf("1-800-FLOWERS", "US", true, null), arrayOf("++12025550123", "US", true, null),
            arrayOf("tel:%2B12025550123", "US", true, null), arrayOf("123456789012345678901", "US", true, null),
        )
    }
}

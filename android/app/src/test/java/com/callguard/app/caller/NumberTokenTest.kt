package com.callguard.app.caller
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test
class NumberTokenTest {
    private fun normalizer(byte: Byte) = PhoneNumberNormalizer(HmacNumberTokenizer(SecretKeySpec(ByteArray(32) { byte }, "HmacSHA256")))
    @Test fun equivalentFormatsShareTokenAndDifferentKeysDoNot() {
        val a=normalizer(1); val b=normalizer(2)
        assertEquals(a.normalize("2025550123", "US").numberToken, a.normalize("tel:+12025550123", null).numberToken)
        assertNotEquals(a.normalize("+12025550123", null).numberToken, b.normalize("+12025550123", null).numberToken)
    }
    @Test fun unknownCallsNeverShareProfileIdentity() {
        val n=normalizer(1)
        assertNotEquals(n.normalize(null, "US").numberToken,n.normalize(null, "US").numberToken)
        assertEquals(UnknownReason.HIDDEN, n.normalize("+12025550123", "US", false).unknownReason)
    }
}

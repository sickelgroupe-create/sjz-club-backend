package com.ruoyi.club.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClubVirtualPaySignerTest
{
    // Public test vectors from WeChat's official virtual-payment guide, not production credentials.
    private static final String BODY = "{\"openid\": \"xxx\", \"user_ip\": \"127.0.0.1\", \"env\": 0}";

    @Test void matchesOfficialPaymentSignature()
    {
        assertEquals("c37809f27c6d7fd1837ad2500a04512b66b34fd793a39a385fade56dca89a4b5",
                ClubVirtualPaySigner.paymentSignature("12345", "/xpay/query_user_balance", BODY));
    }

    @Test void matchesOfficialUserSignature()
    {
        assertEquals("089d9e8dc5d308977360c4b79ec600a93d736802802a807d634192328032f6c7",
                ClubVirtualPaySigner.userSignature("9hAb/NEYUlkaMBEsmFgzig==", BODY));
    }

    @Test void bindsExactBodyPathAndEnvironmentKey()
    {
        String original = ClubVirtualPaySigner.paymentSignature("test-sandbox", "requestVirtualPayment", BODY);
        assertNotEquals(original, ClubVirtualPaySigner.paymentSignature("test-live", "requestVirtualPayment", BODY));
        assertNotEquals(original, ClubVirtualPaySigner.paymentSignature("test-sandbox", "requestVirtualPayment", BODY.replace(" ", "")));
        assertNotEquals(original, ClubVirtualPaySigner.paymentSignature("test-sandbox", "/xpay/query_order", BODY));
    }

    @Test void refusesInvalidInputsWithoutLeakingCredentials()
    {
        assertThrows(IllegalArgumentException.class, () -> ClubVirtualPaySigner.paymentSignature("test-secret", "/xpay/query_order?access_token=secret", BODY));
        assertThrows(IllegalArgumentException.class, () -> ClubVirtualPaySigner.userSignature("", BODY));
        assertThrows(IllegalArgumentException.class, () -> ClubVirtualPaySigner.userSignature("test-secret", null));
        Exception error = assertThrows(IllegalArgumentException.class, () -> ClubVirtualPaySigner.paymentSignature("test-secret", "https://evil.example", BODY));
        assertFalse(error.getMessage().contains("test-secret"));
    }
}

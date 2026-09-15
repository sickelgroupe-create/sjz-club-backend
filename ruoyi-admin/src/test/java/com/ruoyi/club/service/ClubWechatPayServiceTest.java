package com.ruoyi.club.service;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.wechat.pay.java.service.payments.model.Transaction;

class ClubWechatPayServiceTest
{
    @Test void yuanIsConvertedToExactIntegerCents()
    {
        assertEquals(1, ClubWechatPayService.cents(new BigDecimal("0.01")));
        assertEquals(12345, ClubWechatPayService.cents(new BigDecimal("123.45")));
        assertThrows(ServiceException.class, () -> ClubWechatPayService.cents(new BigDecimal("1.001")));
    }

    @Test void callbackMustMatchConfiguredAppAndMerchant()
    {
        ClubWechatPayService service = new ClubWechatPayService();
        ReflectionTestUtils.setField(service, "appId", "wx-test");
        ReflectionTestUtils.setField(service, "merchantId", "mch-test");
        Transaction tx = new Transaction(); tx.setAppid("wx-test"); tx.setMchid("mch-test");
        assertDoesNotThrow(() -> service.validateIdentity(tx));
        tx.setMchid("attacker");
        assertThrows(ServiceException.class, () -> service.validateIdentity(tx));
    }
}

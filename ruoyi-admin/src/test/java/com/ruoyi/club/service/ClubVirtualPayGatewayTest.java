package com.ruoyi.club.service;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClubVirtualPayGatewayTest
{
    private JSONObject receipt(int type,int paid) {
        JSONObject value=new JSONObject();value.put("order_type",type);value.put("paid_fee",paid);value.put("wx_order_id","wx-real-receipt");return value;
    }
    @Test void ordinaryConfirmedAmountAccepted() { assertDoesNotThrow(()->ClubVirtualPayGateway.validatePaid(receipt(0,9900),9900)); }
    @Test void appleConfirmedAmountAccepted() { assertDoesNotThrow(()->ClubVirtualPayGateway.validatePaid(receipt(7,9900),9900)); }
    @Test void wrongAmountRejected() { assertThrows(ServiceException.class,()->ClubVirtualPayGateway.validatePaid(receipt(0,1),9900)); }
    @Test void refundReceiptCannotGrantService() { assertThrows(ServiceException.class,()->ClubVirtualPayGateway.validatePaid(receipt(1,9900),9900)); }
    @Test void missingChannelRejected() { JSONObject value=receipt(0,9900);value.remove("order_type");assertThrows(ServiceException.class,()->ClubVirtualPayGateway.validatePaid(value,9900)); }
    @Test void missingAmountRejected() { JSONObject value=receipt(0,9900);value.remove("paid_fee");assertThrows(ServiceException.class,()->ClubVirtualPayGateway.validatePaid(value,9900)); }
    @Test void missingTransactionRejected() { JSONObject value=receipt(0,9900);value.remove("wx_order_id");assertThrows(ServiceException.class,()->ClubVirtualPayGateway.validatePaid(value,9900)); }
    @Test void zeroCannotGrantService() { assertThrows(ServiceException.class,()->ClubVirtualPayGateway.validatePaid(receipt(0,0),0)); }
    @Test void historicalPaymentsRemainOnOriginalChannel() { assertFalse(ClubVirtualPayGateway.owns("WX123456789"));assertFalse(ClubVirtualPayGateway.owns("RC123456789"));assertFalse(ClubVirtualPayGateway.owns(null));assertTrue(ClubVirtualPayGateway.owns("VP123456789")); }
}

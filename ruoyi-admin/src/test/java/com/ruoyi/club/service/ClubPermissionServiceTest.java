package com.ruoyi.club.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class ClubPermissionServiceTest
{
    private final ClubPermissionService service = new ClubPermissionService();

    @Test
    void mapsEverySensitiveDomainToFineGrainedPermission()
    {
        assertEquals("club:user:edit", service.permission("users", "edit"));
        assertEquals("club:product:edit", service.permission("skus", "edit"));
        assertEquals("club:order:list", service.permission("paymentAudits", "list"));
        assertEquals("club:receivable:list", service.permission("receivables", "list"));
        assertEquals("club:withdrawal:edit", service.permission("withdrawals", "edit"));
        assertEquals("club:finance-config:edit", service.permission("rechargeTiers", "edit"));
        assertEquals("club:customer-service:edit", service.permission("customerConfig", "edit"));
        assertEquals("club:teen-config:edit", service.permission("teenSettings", "edit"));
        assertEquals("club:audit:list", service.permission("adminAudits", "list"));
        assertEquals("club:denied", service.permission("unknown", "list"));
    }
}

package com.ruoyi.club.service;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ClubProductionViewsTest {
    @Test void visibilityProtectsOrdersWithAnyRealOrBalancePaymentAndDoesNotUseDateOrAmount(){
        String sql=ClubOrderVisibility.operational("o");
        assertTrue(sql.contains("mode in ('mock','mock_wechat')"));
        assertTrue(sql.contains("or exists"));
        assertTrue(sql.contains("mode in ('wechat','balance')"));
        assertFalse(sql.contains("amount"));assertFalse(sql.contains("created_at"));
        assertThrows(IllegalArgumentException.class,()->ClubOrderVisibility.operational("o;delete"));
    }
    @Test void dashboardUsesActualPaymentEvidenceAndNoLegacyRevenueKey(){
        JdbcTemplate db=mock(JdbcTemplate.class);
        ClubAdminService service=new ClubAdminService(db,mock(ClubBusinessService.class),mock(ClubAfterSaleService.class),mock(ClubCatalogService.class),mock(ClubRoleLifecycleService.class),mock(ClubIdentityCryptoService.class));
        Map<String,Object> result=service.dashboard();
        assertTrue(result.containsKey("paidRevenue"));assertFalse(result.containsKey("mockRevenue"));
        verify(db).queryForObject(contains("sum(o.total_amount)"),eq(java.math.BigDecimal.class));
    }
}

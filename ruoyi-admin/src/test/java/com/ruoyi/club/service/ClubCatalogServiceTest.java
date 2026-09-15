package com.ruoyi.club.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.common.exception.ServiceException;

class ClubCatalogServiceTest
{
    @Test
    void omittedPlayerIsAssignedFromProductAndCannotBeOverridden()
    {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        org.mockito.Mockito.when(jdbc.queryForList(org.mockito.ArgumentMatchers.contains("select bound_player_id"), org.mockito.ArgumentMatchers.eq(2L)))
            .thenReturn(java.util.Collections.singletonList(java.util.Collections.singletonMap("bound_player_id",7L)));
        java.util.Map<String,Object> row=new java.util.HashMap<>();
        row.put("provider_id",7L);row.put("provider_user_id",70L);
        org.mockito.Mockito.when(jdbc.queryForList(org.mockito.ArgumentMatchers.contains("select pp.id provider_id"), org.mockito.ArgumentMatchers.eq(7L)))
            .thenReturn(java.util.Collections.singletonList(row));
        ClubCatalogService service=new ClubCatalogService(jdbc);
        java.util.Map<String,Object> provider=service.resolveProvider(2L,3L,null);
        org.junit.jupiter.api.Assertions.assertEquals("player",provider.get("provider_type"));
        org.junit.jupiter.api.Assertions.assertEquals(70L,provider.get("provider_user_id"));
        assertThrows(ServiceException.class,()->service.resolveProvider(2L,3L,99L));
    }

    @Test
    void unavailableProductAndExplicitUnavailablePlayerAreStillRejected()
    {
        ClubCatalogService catalog = new ClubCatalogService(org.mockito.Mockito.mock(JdbcTemplate.class));
        assertThrows(ServiceException.class, () -> catalog.resolveProvider(2L, 3L, null));
        assertThrows(ServiceException.class, () -> catalog.resolveProvider(2L, 3L, 99L));
    }

    private final ClubCatalogService service = new ClubCatalogService(new JdbcTemplate());

    @Test
    void acceptsPositivePriceWithTwoDecimalsAndNonNegativeStock()
    {
        assertDoesNotThrow(() -> service.validatePriceAndStock(new BigDecimal("0.01"), 0));
        assertDoesNotThrow(() -> service.validatePriceAndStock(new BigDecimal("999.99"), 5));
    }

    @Test
    void rejectsInvalidTradingPriceAndStock()
    {
        assertThrows(ServiceException.class, () -> service.validatePriceAndStock(BigDecimal.ZERO, 1));
        assertThrows(ServiceException.class, () -> service.validatePriceAndStock(new BigDecimal("-1"), 1));
        assertThrows(ServiceException.class, () -> service.validatePriceAndStock(new BigDecimal("1.001"), 1));
        assertThrows(ServiceException.class, () -> service.validatePriceAndStock(new BigDecimal("1.00"), -1));
    }
}

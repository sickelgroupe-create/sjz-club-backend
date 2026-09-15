package com.ruoyi.club.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import com.ruoyi.common.exception.ServiceException;

class ClubWorkbenchMetricTest
{
    private JdbcTemplate database()
    {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(contains("select user_type"), any(RowMapper.class), eq(7L))).thenReturn(Collections.singletonList("player"));
        Map<String,Object> summary = new HashMap<>(); summary.put("total", 51L); summary.put("amount", new BigDecimal("12.00"));
        when(jdbc.queryForMap(anyString(), eq(7L))).thenReturn(summary);
        when(jdbc.queryForMap(anyString(), eq(new BigDecimal("80.00")), eq(7L))).thenReturn(summary);
        return jdbc;
    }
    @Test void expectedUsesRoundedPerOrderEstimateAndOwnedScopeOnBothQueries()
    {
        JdbcTemplate jdbc=database();
        Map<String,Object> result=new ClubBusinessService(jdbc,mock(ClubCatalogService.class)).workbenchMetric(7L,"expected",2);
        assertEquals(false,result.get("hasMore"));
        verify(jdbc).queryForMap(contains("round(o.total_amount*?/100,2)"),eq(new BigDecimal("80.00")),eq(7L));
        verify(jdbc).queryForList(contains("o.provider_user_id=?"),eq(new BigDecimal("80.00")),eq(7L),eq(50),eq(50));
    }
    @Test void todayUsesHalfOpenServerDateAndPageOneHasMore()
    {
        JdbcTemplate jdbc=database();
        Map<String,Object> result=new ClubBusinessService(jdbc,mock(ClubCatalogService.class)).workbenchMetric(7L,"today",1);
        assertEquals(true,result.get("hasMore"));
        verify(jdbc).queryForMap(contains("o.created_at>=curdate() and o.created_at<date_add(curdate(),interval 1 day)"),eq(7L));
    }
    @Test void invalidTypeAndPageAreRejectedBeforeDataQueries()
    {
        JdbcTemplate jdbc=database();ClubBusinessService service=new ClubBusinessService(jdbc,mock(ClubCatalogService.class));
        assertThrows(ServiceException.class,()->service.workbenchMetric(7L,"total' OR 1=1",1));
        assertThrows(ServiceException.class,()->service.workbenchMetric(7L,"total",0));
        assertThrows(ServiceException.class,()->service.workbenchMetric(7L,"total",Integer.MAX_VALUE));
        verify(jdbc,never()).queryForMap(anyString(),any(Object[].class));
    }
    @Test void customerCannotReadProviderMetrics()
    {
        JdbcTemplate jdbc=database();when(jdbc.query(contains("select user_type"),any(RowMapper.class),eq(8L))).thenReturn(Collections.singletonList("customer"));
        assertThrows(ServiceException.class,()->new ClubBusinessService(jdbc,mock(ClubCatalogService.class)).workbenchMetric(8L,"total",1));
    }
}

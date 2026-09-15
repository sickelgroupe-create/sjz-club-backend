package com.ruoyi.club.service;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClubAcceptedOccupancyTest {
 @Test void acceptedAndServingOccupyUntilProviderFinishes() {
  String sql=ClubPlayerCapacity.occupied("o");
  assertTrue(sql.contains("o.status in ('accepted','serving')"));
  assertTrue(sql.contains("o.provider_completed_at is null"));
  assertTrue(sql.contains("order by capacity_log.id desc limit 1"));
 }
 @Test void ownAcceptedOrderIsExcludedFromConflictQuery() {
  String sql=ClubPlayerCapacity.busy("o.provider_user_id","o.id");
  assertTrue(sql.contains("busy_order.id<>o.id"));
  assertFalse(ClubPlayerCapacity.busy("p.user_id").contains("busy_order.id<>"));
  ClubBusinessService business=new ClubBusinessService(mock(JdbcTemplate.class),mock(ClubCatalogService.class));
  Map<String,Object> order=new HashMap<>();order.put("status","accepted");order.put("playerBusy",false);
  assertEquals(Collections.singletonList("start"),ReflectionTestUtils.invokeMethod(business,"allowedActions",order));
 }
 @Test void elapsedStartsAtLatestAcceptanceOrDirectStartNotPayment() {
  String sql=ClubPlayerCapacity.elapsed("o");
  assertTrue(sql.contains("clock_log.from_status='pending'"));
  assertTrue(sql.contains("clock_log.to_status in ('accepted','serving')"));
  assertTrue(sql.contains("order by clock_log.id desc limit 1"));
  assertTrue(sql.contains("else null end"));
  assertFalse(sql.contains("paid_at"));
  assertFalse(sql.contains("o.created_at"));
 }
}

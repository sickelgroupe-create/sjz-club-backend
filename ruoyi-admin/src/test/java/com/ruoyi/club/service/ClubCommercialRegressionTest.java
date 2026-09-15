package com.ruoyi.club.service;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import com.ruoyi.framework.web.exception.GlobalExceptionHandler;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
class ClubCommercialRegressionTest {
 @Test void hugePageUsesLongOffset(){
  JdbcTemplate db=mock(JdbcTemplate.class);
  ClubAdminService service=new ClubAdminService(db,mock(ClubBusinessService.class),mock(ClubAfterSaleService.class),mock(ClubCatalogService.class),mock(ClubRoleLifecycleService.class),mock(ClubIdentityCryptoService.class));
  service.list("users","","",Integer.MAX_VALUE,100);
  verify(db).queryForList(contains("limit ? offset ?"),eq(100),eq(214748364600L));
 }
 @Test void runtimeFailureDoesNotExposeDatabaseDetails(){
  Object message=new GlobalExceptionHandler().handleRuntimeException(new RuntimeException("SQL SELECT password_hash FROM club_user"),new MockHttpServletRequest()).get("msg");
  assertEquals("系统处理失败，请稍后重试",message);
 }
 @Test void checkedFailureDoesNotExposeInternalPaths(){
  Object message=new GlobalExceptionHandler().handleException(new Exception("/etc/private-key.pem"),new MockHttpServletRequest()).get("msg");
  assertEquals("系统处理失败，请稍后重试",message);
 }
}

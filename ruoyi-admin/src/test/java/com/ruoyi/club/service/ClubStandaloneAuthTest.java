package com.ruoyi.club.service;
import java.util.*;
import java.sql.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class ClubStandaloneAuthTest {
 final JdbcTemplate db=mock(JdbcTemplate.class);
 final BCryptPasswordEncoder encoder=new BCryptPasswordEncoder();
 final ClubAuthService auth=new ClubAuthService(db,encoder);
 final ClubWechatPhoneService phone=mock(ClubWechatPhoneService.class);
 ClubStandaloneAuthTest(){
  ReflectionTestUtils.setField(auth,"authMode","standalone");
  ReflectionTestUtils.setField(auth,"accessTokenMinutes",30);
  ReflectionTestUtils.setField(auth,"refreshTokenDays",14);
  ReflectionTestUtils.setField(auth,"wechatPhone",phone);
 }
 static Map<String,Object> row(Object... a){Map<String,Object> r=new HashMap<>();for(int i=0;i<a.length;i+=2)r.put((String)a[i],a[i+1]);return r;}
 void view(){when(db.queryForList(startsWith("select id,account,phone,email,nickname"),eq(39L))).thenReturn(Arrays.asList(row("id",39L,"account","test_user","status","active")));}
 @Test void independentRegistrationHashesPasswordAndIssuesUnrestrictedSessionWithoutWechat() throws Exception {
  view();Connection c=mock(Connection.class);PreparedStatement p=mock(PreparedStatement.class);
  when(c.prepareStatement(anyString(),eq(Statement.RETURN_GENERATED_KEYS))).thenReturn(p);
  when(db.update(any(PreparedStatementCreator.class),any(KeyHolder.class))).thenAnswer(a->{a.<PreparedStatementCreator>getArgument(0).createPreparedStatement(c);a.<KeyHolder>getArgument(1).getKeyList().add(row("id",39L));return 1;});
  Map<String,Object> session=auth.register(row("account","test_user","password","Testing123"));
  assertEquals("standalone",session.get("authMode"));assertEquals(1800,session.get("expiresIn"));
  assertEquals(false,session.get("wechatBound"));assertNotNull(session.get("accessToken"));
  verify(p).setObject(eq(4),argThat((String v)->encoder.matches("Testing123",v)));
  verify(db).update(contains("insert into club_user_token"),eq(39L),eq("access"),eq(auth.sha256((String)session.get("accessToken"))),any(Timestamp.class));
  verifyNoInteractions(phone);
 }
 @Test void independentLoginUsesOnlyAccountNotUnverifiedPhoneOrEmail() {
  view();when(db.queryForList("select * from club_user where account=? limit 1","test_user")).thenReturn(Arrays.asList(row("id",39L,"status","active","password_hash",encoder.encode("Testing123"))));
  assertEquals("standalone",auth.login(row("account","test_user","password","Testing123")).get("authMode"));
  verify(db,never()).queryForList(contains("or phone=?"),any(Object[].class));
  verifyNoInteractions(phone);
 }
 @Test void wrongPasswordCannotIssueTokens() {
  when(db.queryForList("select * from club_user where account=? limit 1","test_user")).thenReturn(Arrays.asList(row("id",39L,"status","active","password_hash",encoder.encode("Testing123"))));
  assertThrows(ServiceException.class,()->auth.login(row("account","test_user","password","Wrong123")));
  verify(db,never()).update(contains("insert into club_user_token"),any(Object[].class));
 }
 @Test void contactChangeRequiresCurrentPasswordAndKeepsSameUser() {
  view();when(db.queryForObject("select password_hash from club_user where id=? for update",String.class,39L)).thenReturn(encoder.encode("Testing123"));
  assertThrows(ServiceException.class,()->auth.setContactPhone(39L,row("phone","13800000000","password","wrong")));
  verify(db,never()).update("update club_user set phone=? where id=?","13800000000",39L);
  assertEquals("standalone",auth.setContactPhone(39L,row("phone","13800000000","password","Testing123")).get("authMode"));
  verify(db).update("update club_user set phone=? where id=?","13800000000",39L);
  verifyNoInteractions(phone);
 }
 @Test void nativePhoneRegistrationIsClosedInStandaloneMode() {
  assertThrows(ServiceException.class,()->auth.phoneRegister(row("phone","13800000000","password","Testing123","phoneCode","anything")));
  verifyNoInteractions(db,phone);
 }
}

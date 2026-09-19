package com.ruoyi.club.service;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.ruoyi.club.web.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ClubWechatBindingGateTest {
 @Test void noPhoneBlocksCheckoutButAllowsBrowsingIncludingOldTokens() throws Exception {
  token("access",1);when(db.queryForObject("select phone from club_user where id=?",String.class,68L)).thenReturn(null);
  assertThrows(ClubPhoneBindingRequiredException.class,()->auth.requireContactUserId(request()));
  MockMvc m=mvc();
  for(String url:Arrays.asList("/app/orders","/app/orders/8/balance-pay","/app/orders/8/wechat-prepay"))
   m.perform(post(url).header("Authorization","Bearer token").contentType("application/json").content("{}")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(4602));
  assertEquals(68L,auth.requireUserId(request()));
  m.perform(get("/app/workbench").header("Authorization","Bearer token")).andExpect(status().isOk());
  verifyNoInteractions(app);
 }
 @Test void noPhoneCanStillBindVerifiedPhone() throws Exception {
  token("access",1);when(db.queryForObject("select phone from club_user where id=?",String.class,68L)).thenReturn(null);
  doReturn(row("id",68L,"phone","13800138000","phoneBound",true)).when(auth).bindWechatPhone(eq(68L),anyMap());
  mvc().perform(post("/app/auth/bind-phone").header("Authorization","Bearer token").contentType("application/json").content("{\"phoneCode\":\"proof\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(68));
  verify(auth).bindWechatPhone(eq(68L),anyMap());
 }
 @Test void limitedWechatSessionCannotSkipWechatProofViaPhoneBinding() throws Exception {
  token("binding",1);
  mvc().perform(post("/app/auth/bind-phone").header("Authorization","Bearer token").contentType("application/json").content("{}")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(4601));
  verify(auth,never()).bindWechatPhone(anyLong(),anyMap());
 }
 @Test void blankPhoneIsNotBound() {
  token("access",1);when(db.queryForObject("select phone from club_user where id=?",String.class,68L)).thenReturn("  ");
  assertThrows(ClubPhoneBindingRequiredException.class,()->auth.requireContactUserId(request()));
 }
 @Test void unboundFullSessionCannotStartWechatPaymentsOrWithdraw() throws Exception {
  token("access",0);
  for(String url:Arrays.asList("/app/orders/8/wechat-prepay","/app/recharges/8/wechat-prepay","/app/withdrawals"))
   mvc().perform(post(url).header("Authorization","Bearer token").contentType("application/json").content("{}")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(4601));
  verifyNoInteractions(app);
 }
 @Test void unboundFullSessionStillNeedsVerifiedContact() {
  token("access",0);when(db.queryForObject("select phone from club_user where id=?",String.class,68L)).thenReturn(null);
  assertThrows(ClubPhoneBindingRequiredException.class,()->auth.requireContactUserId(request()));
 }
 final JdbcTemplate db=mock(JdbcTemplate.class);
 final ClubAuthService auth=spy(new ClubAuthService(db,new BCryptPasswordEncoder()));
 final ClubAppService app=mock(ClubAppService.class);
 Map<String,Object> row(Object... pairs){Map<String,Object> r=new HashMap<>();for(int i=0;i<pairs.length;i+=2)r.put((String)pairs[i],pairs[i+1]);return r;}
 MockHttpServletRequest request(){MockHttpServletRequest r=new MockHttpServletRequest();r.addHeader("Authorization","Bearer token");return r;}
 void token(String kind,int bound){when(db.queryForObject("select phone from club_user where id=?",String.class,68L)).thenReturn("13800138000");when(db.queryForList(contains("select t.user_id from club_user_token"),anyString())).thenReturn(Arrays.asList(row("user_id",68L)));when(db.queryForObject("select token_type from club_user_token where token_hash=?",String.class,auth.sha256("token"))).thenReturn(kind);when(db.queryForObject("select count(1) from club_wechat_identity where user_id=?",Integer.class,68L)).thenReturn(bound);}
 MockMvc mvc(){return MockMvcBuilders.standaloneSetup(new ClubAppController(auth,app,mock(ClubBusinessService.class),mock(ClubAfterSaleService.class),mock(ClubTeenPolicyService.class),mock(ClubWechatPayService.class))).setControllerAdvice(new ClubExceptionHandler()).build();}

 @Test void limitedTokenCannotUseBusinessEvenWhenAccountWasPreviouslyBound(){token("binding",1);assertThrows(ClubWechatBindingRequiredException.class,()->auth.requireUserId(request()));assertEquals(68L,auth.requireAuthenticatedUserId(request()));}
 @Test void unboundFullSessionAllowsOrdinaryAccess(){token("access",0);assertEquals(68L,auth.requireUserId(request()));assertThrows(ClubWechatBindingRequiredException.class,()->auth.requireWechatUserId(request()));}
 @Test void boundFullSessionPreservesUid(){token("access",1);assertEquals(68L,auth.requireUserId(request()));}
 @Test void unboundRequestsCannotCreateOrderPayOrOpenWorkbench() throws Exception {token("binding",0);MockMvc m=mvc();for(String url:Arrays.asList("/app/orders","/app/orders/8/balance-pay","/app/orders/8/wechat-prepay"))m.perform(post(url).header("Authorization","Bearer token").contentType("application/json").content("{}")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(4601));m.perform(get("/app/workbench").header("Authorization","Bearer token")).andExpect(status().isForbidden());verifyNoInteractions(app);}
 @Test void limitedSessionCanInspectBindingState() throws Exception {token("binding",0);doReturn(row("wechatBound",false)).when(auth).bindingState(68L);mvc().perform(get("/app/auth/bindings").header("Authorization","Bearer token")).andExpect(status().isOk()).andExpect(jsonPath("$.data.wechatBound").value(false));}
 @Test void bindingEndpointUsesLimitedIdentityWithoutOpeningBusiness() throws Exception {token("binding",0);doReturn(row("wechatBound",true)).when(auth).bindWechatSession(eq(68L),anyMap());mvc().perform(post("/app/auth/bind-wechat").header("Authorization","Bearer token").contentType("application/json").content("{\"code\":\"wx-code\"}")).andExpect(status().isOk());verify(auth).bindWechatSession(eq(68L),anyMap());verifyNoInteractions(app);}
 @Test void mergeConflictCannotPromoteLimitedSession(){doReturn(row("mergeRequired",true)).when(auth).bindWechat(68L,Collections.emptyMap());assertTrue((Boolean)auth.bindWechatSession(68L,Collections.emptyMap()).get("mergeRequired"));verify(db,never()).update(contains("club_user_token"),any(Object[].class));}
 @Test void successfulBindingIssuesFullSessionOnlyAfterProof(){doReturn(row("wechatBound",true)).when(auth).bindWechat(68L,Collections.emptyMap());doReturn(row("id",68L)).when(auth).userView(68L);when(db.queryForObject("select count(1) from club_wechat_identity where user_id=?",Integer.class,68L)).thenReturn(1);Map<String,Object> r=auth.bindWechatSession(68L,Collections.emptyMap());assertEquals(true,r.get("wechatBound"));assertNotNull(r.get("accessToken"));verify(db).update(contains("insert into club_user_token"),eq(68L),eq("access"),anyString(),any(java.sql.Timestamp.class));}
 @Test void restrictedRefreshCannotUpgradeBeforeBinding(){when(db.queryForList(contains("select t.id,t.user_id,t.token_type,u.status"),anyString())).thenReturn(Arrays.asList(row("id",2L,"user_id",68L,"token_type","bind_refresh","status","active")));when(db.update("update club_user_token set revoked_at=now() where id=? and revoked_at is null and expires_at>now()",2L)).thenReturn(1);doReturn(row("id",68L)).when(auth).userView(68L);Map<String,Object> r=auth.refresh(row("refreshToken","limited-refresh"));assertEquals(false,r.get("wechatBound"));verify(db).update(contains("insert into club_user_token"),eq(68L),eq("binding"),anyString(),any(java.sql.Timestamp.class));verify(db,never()).update(contains("insert into club_user_token"),eq(68L),eq("access"),anyString(),any(java.sql.Timestamp.class));}
 @Test void passwordLoginIssuesFullAccountSession(){when(db.queryForList(contains("select * from club_user where account=?"),anyString(),anyString(),anyString())).thenReturn(Arrays.asList(row("id",68L,"status","active","password_hash",new BCryptPasswordEncoder().encode("Password123"))));doReturn(row("id",68L)).when(auth).userView(68L);auth.login(row("account","13800000000","password","Password123"));verify(db).update(contains("insert into club_user_token"),eq(68L),eq("access"),anyString(),any(java.sql.Timestamp.class));verify(db,never()).update(contains("insert into club_user_token"),eq(68L),eq("binding"),anyString(),any(java.sql.Timestamp.class));}
}

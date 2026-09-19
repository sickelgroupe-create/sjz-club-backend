package com.ruoyi.club.service;

import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClubSandboxGuardTest {
 private ClubSandboxGuard guard(MockEnvironment env,DataSource source,String auth,String payment) {
  ClubSandboxGuard guard=new ClubSandboxGuard(env,source);
  ReflectionTestUtils.setField(guard,"authMode",auth);
  ReflectionTestUtils.setField(guard,"paymentMode",payment);
  return guard;
 }
 private MockEnvironment sandbox() {
  MockEnvironment env=new MockEnvironment();
  env.setActiveProfiles("druid","sandbox");
  return env.withProperty("club.wechat-login-mode","disabled").withProperty("ruoyi.profile","/opt/sjz-club-sandbox/uploads");
 }
 @Test void liveModeDoesNotRequireSandboxDatabase() throws Exception {
  DataSource source=mock(DataSource.class);
  guard(new MockEnvironment(),source,"wechat","wechat").afterPropertiesSet();
  verifyNoInteractions(source);
 }
 @Test void simulationCannotRunWithoutExplicitSandboxProfile() {
  DataSource source=mock(DataSource.class);
  assertThrows(IllegalStateException.class,()->guard(new MockEnvironment(),source,"standalone","simulation").afterPropertiesSet());
  verifyNoInteractions(source);
 }
 @Test void sandboxCannotUseLiveDatabase() throws Exception {
  DataSource source=mock(DataSource.class);Connection c=mock(Connection.class);
  when(source.getConnection()).thenReturn(c);when(c.getCatalog()).thenReturn("sjz_club");
  assertThrows(IllegalStateException.class,()->guard(sandbox(),source,"standalone","simulation").afterPropertiesSet());
  verify(c).close();
 }
 @Test void differentlyCasedSimulationCannotBypassProductionGuard() {
  for(String payment:new String[]{"SIMULATION","Simulation","sImUlAtIoN"}) {
   DataSource source=mock(DataSource.class);
   assertThrows(IllegalStateException.class,()->guard(new MockEnvironment(),source,"wechat",payment).afterPropertiesSet());
   verifyNoInteractions(source);
  }
 }
 @Test void differentlyCasedStandaloneCannotBypassProductionGuard() {
  DataSource source=mock(DataSource.class);
  assertThrows(IllegalStateException.class,()->guard(new MockEnvironment(),source,"STANDALONE","disabled").afterPropertiesSet());
  verifyNoInteractions(source);
 }
 @Test void upperCaseSimulationStillRequiresAnIsolatedDatabase() throws Exception {
  DataSource source=mock(DataSource.class);Connection c=mock(Connection.class);
  when(source.getConnection()).thenReturn(c);when(c.getCatalog()).thenReturn("production_db");
  assertThrows(IllegalStateException.class,()->guard(sandbox(),source,"standalone","SIMULATION").afterPropertiesSet());
  verify(c).close();
 }
 @Test void sandboxRejectsWechatCredentialsBeforeDatabaseAccess() {
  for(String key:new String[]{"club.wechat-app-id","club.wechat-app-secret","club.wechat-pay-mch-id","club.wechat-pay-api-v3-key","club.wechat-pay-private-key-path"}) {
   DataSource source=mock(DataSource.class);
   assertThrows(IllegalStateException.class,()->guard(sandbox().withProperty(key,"not-empty"),source,"standalone","simulation").afterPropertiesSet());
   verifyNoInteractions(source);
  }
 }
 @Test void sandboxRejectsRealLoginOrRealPayment() {
  DataSource source=mock(DataSource.class);
  assertThrows(IllegalStateException.class,()->guard(sandbox().withProperty("club.wechat-login-mode","real"),source,"standalone","simulation").afterPropertiesSet());
  assertThrows(IllegalStateException.class,()->guard(sandbox(),source,"standalone","wechat").afterPropertiesSet());
  verifyNoInteractions(source);
 }
 @Test void isolatedConfigurationCanStartAndClosesConnection() throws Exception {
  DataSource source=mock(DataSource.class);Connection c=mock(Connection.class);
  when(source.getConnection()).thenReturn(c);when(c.getCatalog()).thenReturn("sjz_club_sandbox");
  guard(sandbox(),source,"standalone","simulation").afterPropertiesSet();
  verify(c).close();
 }
 @Test void uploadDirectoryMustAlsoBeIsolated() throws Exception {
  DataSource source=mock(DataSource.class);Connection c=mock(Connection.class);
  when(source.getConnection()).thenReturn(c);when(c.getCatalog()).thenReturn("sjz_club_sandbox");
  assertThrows(IllegalStateException.class,()->guard(sandbox().withProperty("ruoyi.profile","/opt/sjz-club/uploads"),source,"standalone","simulation").afterPropertiesSet());
 }
}

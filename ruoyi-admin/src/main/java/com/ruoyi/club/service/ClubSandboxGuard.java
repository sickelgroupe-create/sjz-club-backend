package com.ruoyi.club.service;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Arrays;

/** Simulation must never share a database or WeChat credentials with the live service. */
@Component
public class ClubSandboxGuard implements InitializingBean {
 private final Environment env; private final DataSource source;
 @Value("${club.payment-mode:disabled}") private String paymentMode;
 @Value("${club.auth-mode:wechat}") private String authMode;
 public ClubSandboxGuard(Environment env,DataSource source){this.env=env;this.source=source;}
 @Override public void afterPropertiesSet() throws Exception {
  boolean sandbox=Arrays.asList(env.getActiveProfiles()).contains("sandbox");
  // Payment execution accepts case-insensitive mode names. The startup safety
  // boundary must match that behavior, otherwise SIMULATION bypasses isolation.
  if(!sandbox && ("simulation".equalsIgnoreCase(paymentMode)||"standalone".equalsIgnoreCase(authMode)))
   throw new IllegalStateException("独立模拟模式只能在 sandbox 配置中运行");
  if(!sandbox)return;
  if(!"simulation".equalsIgnoreCase(paymentMode)||!"standalone".equals(authMode))
   throw new IllegalStateException("联调环境必须使用独立账号和模拟支付");
  if(!"disabled".equals(env.getProperty("club.wechat-login-mode","disabled")))
   throw new IllegalStateException("联调环境必须关闭微信登录");
  for(String key:new String[]{"club.wechat-app-id","club.wechat-app-secret","club.wechat-pay-mch-id","club.wechat-pay-api-v3-key","club.wechat-pay-private-key-path"})
   if(!env.getProperty(key,"").trim().isEmpty())throw new IllegalStateException("联调环境不得配置微信凭据："+key);
  try(Connection c=source.getConnection()){
   String db=c.getCatalog();
   if(db==null||!db.endsWith("_sandbox"))throw new IllegalStateException("模拟支付必须使用独立的 _sandbox 数据库");
   if(!env.getProperty("ruoyi.profile","").contains("sandbox"))throw new IllegalStateException("联调上传目录必须隔离");
  }
 }
}

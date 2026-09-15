package com.ruoyi.club.service;
import java.net.URI;
import java.util.*;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** Server-only exchange of the one-time code returned by getPhoneNumber. */
@Service
public class ClubWechatPhoneService {
 private final ClubMiniProgramCodeService tokens;
 private final RestTemplate rest;
 @Value("${club.wechat-app-id:}") private String appId;
 public ClubWechatPhoneService(ClubMiniProgramCodeService tokens){this.tokens=tokens;SimpleClientHttpRequestFactory f=new SimpleClientHttpRequestFactory();f.setConnectTimeout(10000);f.setReadTimeout(10000);rest=new RestTemplate(f);}
 @SuppressWarnings("unchecked")
 public String verifiedPhone(String code){
  if(code==null||!code.matches("[A-Za-z0-9_-]{8,256}"))throw new ServiceException("请重新点击微信授权手机号");
  if(!tokens.isConfigured())throw new ServiceException("微信手机号授权尚未配置，请联系客服");
  try{
   URI uri=UriComponentsBuilder.fromHttpUrl("https://api.weixin.qq.com/wxa/business/getuserphonenumber").queryParam("access_token",tokens.accessToken()).build().encode().toUri();
   HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
   String body=rest.postForObject(uri,new HttpEntity<>(JSON.toJSONString(Collections.singletonMap("code",code)),headers),String.class);
   Map<String,Object> response=JSON.parseObject(body,Map.class);
   if(response==null||!"0".equals(String.valueOf(response.get("errcode"))))throw new ServiceException("微信手机号授权未成功，请重新授权；如持续失败，请管理员检查微信后台的手机号能力权限及额度");
   Map<String,Object> info=(Map<String,Object>)response.get("phone_info");
   Map<String,Object> watermark=info==null?null:(Map<String,Object>)info.get("watermark");
   if(watermark==null||!appId.equals(String.valueOf(watermark.get("appid"))))throw new ServiceException("手机号授权来源不正确，请重新授权");
   String phone=String.valueOf(info.get("purePhoneNumber"));
   if(!"86".equals(String.valueOf(info.get("countryCode")))||!phone.matches("1[3-9]\\d{9}"))throw new ServiceException("当前仅支持中国大陆手机号");
   return phone;
  }catch(ServiceException e){throw e;}catch(Exception e){throw new ServiceException("微信手机号服务暂时不可用，请稍后重试");}
 }
}

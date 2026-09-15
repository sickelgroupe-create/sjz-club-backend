package com.ruoyi.club.service;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.MediaType;
import com.ruoyi.common.exception.ServiceException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ClubUidPhoneTest {
 final JdbcTemplate db=mock(JdbcTemplate.class);
 ClubAdminService admin(){return new ClubAdminService(db,mock(ClubBusinessService.class),mock(ClubAfterSaleService.class),mock(ClubCatalogService.class),mock(ClubRoleLifecycleService.class),mock(ClubIdentityCryptoService.class));}
 @Test void uidPrefixesAreExactAndRejectInvalidRange(){for(String value:Arrays.asList("68","ID68","UID68","uid: 68"," UID：68 "))assertEquals(68L,ClubUidSearch.parse(value));assertNull(ClubUidSearch.parse("昵称"));assertThrows(ServiceException.class,()->ClubUidSearch.parse("UID0"));assertThrows(ServiceException.class,()->ClubUidSearch.parse("ID999999999999999999999999"));}
 @Test void userSearchUsesUidNotNameSubstring(){admin().list("users","ID68","active",1,20);verify(db).queryForObject("select count(1) from club_user where id=? and status=?",Integer.class,68L,"active");}
 @Test void playerSearchUsesUserIdNotProfileId(){admin().list("players","UID75","active",1,20);assertTrue(mockingDetails(db).getInvocations().stream().anyMatch(i->String.valueOf((Object)i.getArgument(0)).contains("and p.user_id=?")&&Arrays.asList(i.getArguments()).contains(75L)));assertFalse(mockingDetails(db).getInvocations().stream().anyMatch(i->String.valueOf((Object)i.getArgument(0)).contains("and p.id=?")));}
 @Test void nicknameSearchStillWorks(){admin().list("users","顾客","",1,20);verify(db).queryForObject(contains("nickname like ?"),eq(Integer.class),eq("%顾客%"),eq("%顾客%"),eq("%顾客%"),eq("%顾客%"));}
 ClubAuthService auth(ClubWechatPhoneService phone,String current){ClubAuthService a=spy(new ClubAuthService(db,new BCryptPasswordEncoder()));ReflectionTestUtils.setField(a,"wechatPhone",phone);Map<String,Object> row=new HashMap<>();row.put("phone",current);row.put("status","active");when(db.queryForList("select phone,status from club_user where id=? for update",68L)).thenReturn(Collections.singletonList(row));doReturn(Collections.singletonMap("phone","13800138000")).when(a).bindingState(68L);return a;}
 Map<String,Object> request(){Map<String,Object> r=new HashMap<>();r.put("phoneCode","verified-code");r.put("phone","13900000000");r.put("userId",99L);return r;}
 @Test void bindsOnlyServerVerifiedPhoneToAuthenticatedUid(){ClubWechatPhoneService phone=mock(ClubWechatPhoneService.class);when(phone.verifiedPhone("verified-code")).thenReturn("13800138000");auth(phone,"").bindWechatPhone(68L,request());verify(db).update("update club_user set phone=? where id=?","13800138000",68L);assertEquals(1,mockingDetails(db).getInvocations().stream().filter(i->i.getMethod().getName().equals("update")).count());}
 @Test void failedVerificationCannotWritePhone(){ClubWechatPhoneService phone=mock(ClubWechatPhoneService.class);when(phone.verifiedPhone(anyString())).thenThrow(new ServiceException("授权失败"));assertThrows(ServiceException.class,()->auth(phone,"").bindWechatPhone(68L,request()));assertFalse(mockingDetails(db).getInvocations().stream().anyMatch(i->i.getMethod().getName().equals("update")));}
 @Test void conflictDoesNotMergeOrMoveAnyBalances(){ClubWechatPhoneService phone=mock(ClubWechatPhoneService.class);when(phone.verifiedPhone(anyString())).thenReturn("13800138000");when(db.queryForList("select id from club_user where phone=? and id<>? limit 1 for update","13800138000",68L)).thenReturn(Collections.singletonList(Collections.singletonMap("id",99L)));assertThrows(ServiceException.class,()->auth(phone,"").bindWechatPhone(68L,request()));assertFalse(mockingDetails(db).getInvocations().stream().anyMatch(i->i.getMethod().getName().equals("update")));}
 @Test void existingDifferentPhoneCannotBeSilentlyReplaced(){ClubWechatPhoneService phone=mock(ClubWechatPhoneService.class);when(phone.verifiedPhone(anyString())).thenReturn("13800138000");assertThrows(ServiceException.class,()->auth(phone,"13900000000").bindWechatPhone(68L,request()));assertFalse(mockingDetails(db).getInvocations().stream().anyMatch(i->i.getMethod().getName().equals("update")));}
 ClubWechatPhoneService remote(String response){ClubMiniProgramCodeService token=mock(ClubMiniProgramCodeService.class);when(token.isConfigured()).thenReturn(true);when(token.accessToken()).thenReturn("fixture-token");ClubWechatPhoneService service=new ClubWechatPhoneService(token);ReflectionTestUtils.setField(service,"appId","wx-app");RestTemplate rest=(RestTemplate)ReflectionTestUtils.getField(service,"rest");MockRestServiceServer server=MockRestServiceServer.bindTo(rest).build();server.expect(requestTo("https://api.weixin.qq.com/wxa/business/getuserphonenumber?access_token=fixture-token")).andExpect(content().json("{\"code\":\"verified-code\"}")).andRespond(withSuccess(response,MediaType.APPLICATION_JSON));return service;}
 @Test void wechatExchangeChecksApplicationAndReturnsPureNumber(){assertEquals("13800138000",remote("{\"errcode\":0,\"phone_info\":{\"purePhoneNumber\":\"13800138000\",\"countryCode\":\"86\",\"watermark\":{\"appid\":\"wx-app\"}}}").verifiedPhone("verified-code"));}
 @Test void foreignApplicationAndWechatErrorAreRejected(){for(String response:Arrays.asList("{\"errcode\":40029,\"errmsg\":\"sensitive diagnostic\"}","{\"errcode\":0,\"phone_info\":{\"purePhoneNumber\":\"13800138000\",\"countryCode\":\"86\",\"watermark\":{\"appid\":\"wrong-app\"}}}")){String message=assertThrows(ServiceException.class,()->remote(response).verifiedPhone("verified-code")).getMessage();assertFalse(message.contains("sensitive"));assertFalse(message.contains("fixture-token"));}}
}

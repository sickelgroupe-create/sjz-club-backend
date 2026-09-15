package com.ruoyi.club.service;
import org.junit.jupiter.api.Test;
import java.util.*;
import com.ruoyi.common.exception.ServiceException;
import static org.junit.jupiter.api.Assertions.*;
class ClubSensitiveConsentTest {
 @Test void rejectsMissing(){assertThrows(ServiceException.class,()->ClubAppService.requireSensitiveConsent(new HashMap<>()));}
 @Test void rejectsStringAndOldVersion(){
  Map<String,Object> m=new HashMap<>();m.put("sensitiveConsent","true");m.put("sensitiveConsentVersion","IDENTITY-20260910-v1");
  assertThrows(ServiceException.class,()->ClubAppService.requireSensitiveConsent(m));
  m.put("sensitiveConsent",true);m.put("sensitiveConsentVersion","old");
  assertThrows(ServiceException.class,()->ClubAppService.requireSensitiveConsent(m));
 }
 @Test void acceptsExplicitCurrentVersion(){
  Map<String,Object> m=new HashMap<>();m.put("sensitiveConsent",true);m.put("sensitiveConsentVersion","IDENTITY-20260910-v1");
  assertDoesNotThrow(()->ClubAppService.requireSensitiveConsent(m));
 }
}

package com.ruoyi.club.service;
import java.util.regex.*;
import com.ruoyi.common.exception.ServiceException;
/** UID is always club_user.id, never a player profile's primary key. */
final class ClubUidSearch {
 static Long parse(String value){
  String text=value==null?"":value.trim();
  Matcher m=Pattern.compile("^(?i)(?:UID|ID)?\\s*[:：#]?\\s*(\\d+)$").matcher(text);
  if(!m.matches())return null;
  try{long id=Long.parseLong(m.group(1));if(id<=0)throw new NumberFormatException();return id;}
  catch(NumberFormatException e){throw new ServiceException("请输入有效的用户 UID");}
 }
}

package com.ruoyi.club.service;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.common.exception.ServiceException;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
class ClubReviewTest {
 final JdbcTemplate db=mock(JdbcTemplate.class);
 final ClubAppService app=spy(new ClubAppService(db,mock(ClubAuthService.class),mock(ClubBusinessService.class),mock(ClubCatalogService.class),mock(ClubTeenPolicyService.class),mock(ClubMiniProgramCodeService.class),mock(ClubIdentityCryptoService.class),mock(ClubWechatPayService.class)));
 ClubReviewTest(){
  when(db.queryForList("select id from club_user where id=? for update",68L)).thenReturn(Arrays.asList(row("id",68L)));
  doReturn(row("id",8L)).when(app).order(68L,8L);
 }
 static Map<String,Object> row(Object... a){Map<String,Object> r=new HashMap<>();for(int i=0;i<a.length;i+=2)r.put((String)a[i],a[i+1]);return r;}
 void setup(String status,Object completed){
  when(db.queryForList("select product_id,status,completed_at from club_order where id=? and user_id=? for update",8L,68L)).thenReturn(Arrays.asList(row("product_id",1L,"status",status,"completed_at",completed)));
 }
 @Test void completedCanReview(){setup("completed","time");app.review(68L,8L,row("rating",5,"content","很好"));verify(db).update(startsWith("insert into club_review"),eq(8L),eq(1L),eq(68L),eq(5),eq("很好"));}
 @Test void completedRefundCanReviewWithoutChangingRefundState(){setup("refunded","time");app.review(68L,8L,row("rating",2,"content","体验不足"));verify(db).update(startsWith("insert into club_order_log"),eq(8L),eq("refunded"),eq("refunded"),eq("user"),eq(68L),eq("用户完成评价"));verify(db,never()).update(startsWith("update club_order"),any(Object[].class));}
 @Test void completedRefundInProgressCanReview(){setup("refunding","time");assertDoesNotThrow(()->app.review(68L,8L,row("rating",3,"content","真实评价")));}
 @Test void refundWithoutCompletionCannotReview(){setup("refunded",null);assertThrows(ServiceException.class,()->app.review(68L,8L,row("rating",5,"content","评价")));verify(db,never()).update(anyString(),any(Object[].class));}
 @Test void servingCannotReview(){setup("serving","time");assertThrows(ServiceException.class,()->app.review(68L,8L,row("rating",5,"content","评价")));}
 @Test void foreignOrderCannotReview(){assertThrows(ServiceException.class,()->app.review(68L,8L,row("rating",5,"content","评价")));verify(db,never()).update(anyString(),any(Object[].class));}
 @Test void duplicateIsIdempotentAndCannotOverwriteExistingReview(){setup("completed","time");when(db.queryForList("select id from club_review where order_id=?",8L)).thenReturn(Arrays.asList(row("id",1L)));app.review(68L,8L,row("rating",1,"content","不能覆盖"));verify(db,never()).update(anyString(),any(Object[].class));}
 @Test void invalidContentAndRatingRejected(){setup("completed","time");for(Map<String,Object> input:Arrays.asList(row("rating",0,"content","评价"),row("rating",6,"content","评价"),row("rating",5,"content","  "),row("rating",5,"content",String.join("",Collections.nCopies(501,"字"))))){assertThrows(ServiceException.class,()->app.review(68L,8L,input));}verify(db,never()).update(anyString(),any(Object[].class));}
}

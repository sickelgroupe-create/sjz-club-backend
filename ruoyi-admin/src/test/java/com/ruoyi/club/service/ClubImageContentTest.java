package com.ruoyi.club.service;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import com.ruoyi.common.utils.file.ImageContentValidator;
import com.ruoyi.common.exception.ServiceException;
import static org.junit.jupiter.api.Assertions.*;
class ClubImageContentTest {
 @Test void validPngAccepted() throws Exception {
  ByteArrayOutputStream b=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",b);
  assertDoesNotThrow(()->ImageContentValidator.validate(new MockMultipartFile("file","a.png","image/png",b.toByteArray()),"png"));
 }
 @Test void textDisguisedAsPngRejected(){
  assertThrows(ServiceException.class,()->ImageContentValidator.validate(new MockMultipartFile("file","a.png","image/png","not an image".getBytes()),"png"));
 }
 @Test void mismatchedExtensionRejected() throws Exception {
  ByteArrayOutputStream b=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",b);
  assertThrows(ServiceException.class,()->ImageContentValidator.validate(new MockMultipartFile("file","a.jpg","image/jpeg",b.toByteArray()),"jpg"));
 }
}

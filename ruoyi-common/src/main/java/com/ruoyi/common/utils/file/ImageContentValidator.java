package com.ruoyi.common.utils.file;
import java.io.InputStream;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.exception.ServiceException;
/** Validate image bytes before writing any uploaded image to public storage. */
public final class ImageContentValidator {
 private ImageContentValidator(){}
 public static void validate(MultipartFile file,String extension){
  if(!java.util.Arrays.asList(MimeTypeUtils.IMAGE_EXTENSION).contains(extension.toLowerCase()))return;
  try(InputStream input=file.getInputStream();ImageInputStream stream=ImageIO.createImageInputStream(input)){
   Iterator<ImageReader> readers=ImageIO.getImageReaders(stream);
   if(!readers.hasNext())throw new ServiceException("文件内容不是有效图片");
   ImageReader reader=readers.next();
   try{
    reader.setInput(stream,true,true);
    String actual=reader.getFormatName().toLowerCase(),expected=extension.toLowerCase();
    if("jpg".equals(expected))expected="jpeg";
    if(!actual.equals(expected))throw new ServiceException("图片格式与文件扩展名不一致");
    int width=reader.getWidth(0),height=reader.getHeight(0);
    if(width<1||height<1||width>12000||height>12000||(long)width*height>25000000L)throw new ServiceException("图片尺寸过大，请压缩后上传");
    if(reader.read(0)==null)throw new ServiceException("图片文件已损坏");
   }finally{reader.dispose();}
  }catch(ServiceException e){throw e;}catch(Exception e){throw new ServiceException("图片文件无效或已损坏");}
 }
}

package com.ruoyi.club.service;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.exception.ServiceException;

@Service
public class ClubVoiceFileService {
    public Map<String,Object> upload(MultipartFile file,int seconds) {
        if(file==null || file.isEmpty() || file.getSize()>5*1024*1024)throw new ServiceException("录音不能为空且不能超过5MB");
        if(seconds<1 || seconds>60)throw new ServiceException("录音时长必须为1至60秒");
        try {
            byte[] bytes=file.getBytes();String extension=extension(bytes);
            String name=LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)+"/"+UUID.randomUUID().toString().replace("-","")+"."+extension;
            Path root=Paths.get(RuoYiConfig.getUploadPath(),"voice").toAbsolutePath().normalize();
            Path destination=root.resolve(name).normalize();
            if(!destination.startsWith(root))throw new ServiceException("录音存储路径无效");
            Files.createDirectories(destination.getParent());
            Files.write(destination,bytes,StandardOpenOption.CREATE_NEW);
            Map<String,Object> result=new HashMap<>();result.put("url","/profile/upload/voice/"+name);result.put("seconds",seconds);return result;
        }catch(IOException e){throw new ServiceException("录音保存失败，请稍后重试");}
    }
    static String extension(byte[] b) {
        if(b.length<16)throw new ServiceException("录音文件损坏，请重新录制");
        if(b[0]=='I'&&b[1]=='D'&&b[2]=='3' || (b[0]&255)==255 && (b[1]&224)==224)return "mp3";
        if(b[0]=='R'&&b[1]=='I'&&b[2]=='F'&&b[3]=='F'&&b[8]=='W'&&b[9]=='A'&&b[10]=='V'&&b[11]=='E')return "wav";
        if(b[0]=='O'&&b[1]=='g'&&b[2]=='g'&&b[3]=='S')return "ogg";
        if((b[0]&255)==26&&(b[1]&255)==69&&(b[2]&255)==223&&(b[3]&255)==163)return "webm";
        if(b[4]=='f'&&b[5]=='t'&&b[6]=='y'&&b[7]=='p')return "m4a";
        throw new ServiceException("不支持的录音格式，请使用录音按钮重新录制");
    }
}

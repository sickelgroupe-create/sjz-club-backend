package com.ruoyi.club.service;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.mock.web.MockMultipartFile;

class ClubVoiceFileServiceTest {
    @Test void refusesEmptyFakeOrOutOfRangeRecording(){
        ClubVoiceFileService service=new ClubVoiceFileService();
        assertThrows(ServiceException.class,()->service.upload(new MockMultipartFile("file",new byte[0]),2));
        assertThrows(ServiceException.class,()->service.upload(new MockMultipartFile("file","<html>not audio at all</html>".getBytes()),2));
        for(int seconds:new int[]{0,-1,61})assertThrows(ServiceException.class,()->service.upload(new MockMultipartFile("file",new byte[20]),seconds));
    }
    @Test void recognizesRecorderWaveAndMp3BytesNotFilename(){
        byte[] wav=new byte[44];System.arraycopy("RIFF".getBytes(),0,wav,0,4);System.arraycopy("WAVE".getBytes(),0,wav,8,4);
        assertEquals("wav",ClubVoiceFileService.extension(wav));
        byte[] mp3=new byte[32];System.arraycopy("ID3".getBytes(),0,mp3,0,3);
        assertEquals("mp3",ClubVoiceFileService.extension(mp3));
    }
}

package com.ruoyi.club.web;
import javax.servlet.http.HttpServletRequest;
import com.ruoyi.club.service.*;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.annotation.RateLimiter;
import com.ruoyi.common.enums.LimitType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class ClubVoiceController {
    private final ClubVoiceFileService files;
    private final ClubAuthService auth;
    private final ClubBusinessService business;
    public ClubVoiceController(ClubVoiceFileService files,ClubAuthService auth,ClubBusinessService business){this.files=files;this.auth=auth;this.business=business;}
    @PostMapping("/app/voice-files") @RateLimiter(time=60,count=10,limitType=LimitType.IP)
    public AjaxResult app(HttpServletRequest request,@RequestParam MultipartFile file,@RequestParam int seconds){
        business.playerProfile(auth.requireUserId(request));return AjaxResult.success(files.upload(file,seconds));
    }
    @PostMapping("/club/admin/voice-files") @PreAuthorize("@ss.hasPermi('club:player:edit')")
    @RateLimiter(time=60,count=10,limitType=LimitType.IP)
    public AjaxResult admin(@RequestParam MultipartFile file,@RequestParam int seconds){return AjaxResult.success(files.upload(file,seconds));}
}

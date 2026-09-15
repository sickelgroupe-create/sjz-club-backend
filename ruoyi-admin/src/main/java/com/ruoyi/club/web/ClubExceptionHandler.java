package com.ruoyi.club.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;

@RestControllerAdvice(basePackages = "com.ruoyi.club")
public class ClubExceptionHandler
{
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    public ResponseEntity<AjaxResult> duplicate(org.springframework.dao.DuplicateKeyException exception)
    {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(AjaxResult.error(409, "记录已存在或已处理，请刷新后重试"));
    }
    @ExceptionHandler(ClubPhoneBindingRequiredException.class)
    public ResponseEntity<AjaxResult> phoneBindingRequired(ClubPhoneBindingRequiredException exception)
    {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(AjaxResult.error(4602, exception.getMessage()));
    }

    @ExceptionHandler(ClubWechatBindingRequiredException.class)
    public ResponseEntity<AjaxResult> wechatBindingRequired(ClubWechatBindingRequiredException exception)
    {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(AjaxResult.error(4601, exception.getMessage()));
    }

    @ExceptionHandler(ClubUnauthorizedException.class)
    public ResponseEntity<AjaxResult> unauthorized(ClubUnauthorizedException exception)
    {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(AjaxResult.error(401, exception.getMessage()));
    }

    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<AjaxResult> badRequest(ServiceException exception)
    {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(AjaxResult.error(400, exception.getMessage()));
    }

    @ExceptionHandler(ClubForbiddenException.class)
    public ResponseEntity<AjaxResult> forbidden(ClubForbiddenException exception)
    {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(AjaxResult.error(403, exception.getMessage()));
    }

    @ExceptionHandler(ClubConflictException.class)
    public ResponseEntity<AjaxResult> conflict(ClubConflictException exception)
    {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(AjaxResult.error(409, exception.getMessage()));
    }

    @ExceptionHandler(ClubPhoneCodeException.class)
    public ResponseEntity<AjaxResult> phoneCodeBadRequest(ClubPhoneCodeException exception)
    {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(AjaxResult.error(400, exception.getMessage()));
    }
}

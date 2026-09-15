package com.ruoyi.club.web;

/** 验证码校验失败；允许事务保留失败次数。 */
public class ClubPhoneCodeException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    public ClubPhoneCodeException(String message)
    {
        super(message);
    }
}

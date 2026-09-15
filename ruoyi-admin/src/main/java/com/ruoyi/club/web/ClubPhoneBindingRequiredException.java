package com.ruoyi.club.web;

public class ClubPhoneBindingRequiredException extends RuntimeException
{
    public ClubPhoneBindingRequiredException()
    {
        super("请先授权绑定手机号，绑定成功后才能使用小程序和下单");
    }
}

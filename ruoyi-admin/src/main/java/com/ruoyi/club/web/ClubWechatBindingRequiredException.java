package com.ruoyi.club.web;

/** Authenticated, but not yet eligible for app business operations. */
public class ClubWechatBindingRequiredException extends RuntimeException
{
    public ClubWechatBindingRequiredException()
    {
        super("请先完成微信一键绑定，绑定成功后才能使用小程序和下单");
    }
}

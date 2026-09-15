package com.ruoyi.club.web;

public class ClubUnauthorizedException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    public ClubUnauthorizedException(String message)
    {
        super(message);
    }
}

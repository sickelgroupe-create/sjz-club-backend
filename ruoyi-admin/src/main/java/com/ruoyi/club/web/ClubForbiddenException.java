package com.ruoyi.club.web;

/** Explicit 403 for authenticated users who do not own the requested business object. */
public class ClubForbiddenException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    public ClubForbiddenException(String message)
    {
        super(message);
    }
}

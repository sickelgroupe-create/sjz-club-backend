package com.ruoyi.club.web;

/** Same idempotency key was reused for a different business payload. */
public class ClubConflictException extends RuntimeException
{
    private static final long serialVersionUID = 1L;
    public ClubConflictException(String message) { super(message); }
}

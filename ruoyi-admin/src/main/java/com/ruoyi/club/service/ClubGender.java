package com.ruoyi.club.service;

import com.ruoyi.common.exception.ServiceException;

/** Shared value contract for app profiles and admin forms. */
final class ClubGender {
    private ClubGender() { }
    static String normalize(Object value) {
        String gender = value == null ? "" : String.valueOf(value).trim();
        if (gender.isEmpty() || "unknown".equals(gender)) return "unknown";
        if ("male".equals(gender) || "男".equals(gender)) return "male";
        if ("female".equals(gender) || "女".equals(gender)) return "female";
        throw new ServiceException("请选择男、女或未设置");
    }
}

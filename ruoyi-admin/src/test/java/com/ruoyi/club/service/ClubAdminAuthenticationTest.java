package com.ruoyi.club.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import com.ruoyi.framework.security.handle.AuthenticationEntryPointImpl;
import com.ruoyi.framework.web.exception.GlobalExceptionHandler;
import org.springframework.security.access.AccessDeniedException;
import static org.junit.jupiter.api.Assertions.*;

class ClubAdminAuthenticationTest
{
    @Test
    void forbiddenSensitiveDetailReturnsRealHttp403()
    {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/club/admin/identities/999");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertEquals(403, new GlobalExceptionHandler().handleAccessDeniedException(
                new AccessDeniedException("test"), request, response).get("code"));
        assertEquals(403, response.getStatus());
    }

    @Test
    void customerTokenCannotBecomeAnHttp200AdminResponse() throws Exception
    {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/club/admin/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new AuthenticationEntryPointImpl().commence(request, response, new InsufficientAuthenticationException("test"));
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"code\":401"));
    }
}

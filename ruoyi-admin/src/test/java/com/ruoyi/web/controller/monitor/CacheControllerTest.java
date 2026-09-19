package com.ruoyi.web.controller.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import com.ruoyi.common.exception.ServiceException;

class CacheControllerTest {
    private CacheController controller;
    private RedisTemplate<String,String> redis;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        controller=new CacheController();
        redis=mock(RedisTemplate.class);
        Field field=CacheController.class.getDeclaredField("redisTemplate");
        field.setAccessible(true);field.set(controller,redis);
    }

    @Test
    void destructiveEndpointsRequireAnIndependentPermission() throws Exception {
        assertEquals("@ss.hasPermi('monitor:cache:remove')",CacheController.class.getMethod("clearCacheAll").getAnnotation(PreAuthorize.class).value());
        for(String name:Arrays.asList("clearCacheName","clearCacheKey"))
            assertEquals("@ss.hasPermi('monitor:cache:remove')",CacheController.class.getMethod(name,String.class).getAnnotation(PreAuthorize.class).value());
    }

    @Test
    void rejectsTokensLocksAndWildcardNamesBeforeRedisAccess() {
        for(String name:Arrays.asList("","*","sys_*","login_tokens:","club:","captcha_codes:","rate_limit:")) {
            assertThrows(ServiceException.class,()->controller.clearCacheName(name));
            assertThrows(ServiceException.class,()->controller.getCacheKeys(name));
        }
        for(String key:Arrays.asList("login_tokens:secret","club:order:lock:1","captcha_codes:1","pwd_err_cnt:admin","*")) {
            assertThrows(ServiceException.class,()->controller.clearCacheKey(key));
            assertThrows(ServiceException.class,()->controller.getCacheValue("sys_config:",key));
        }
        assertThrows(ServiceException.class,()->controller.getCacheValue("sys_config:","sys_dict:sys_user_sex"));
        verifyNoInteractions(redis);
    }

    @Test
    void clearAllOnlyDeletesConfigurationAndDictionaryKeys() {
        HashSet<String> config=new HashSet<>(Collections.singleton("sys_config:sys.index.skinName"));
        HashSet<String> dict=new HashSet<>(Collections.singleton("sys_dict:sys_user_sex"));
        when(redis.keys("sys_config:*")).thenReturn(config);
        when(redis.keys("sys_dict:*")).thenReturn(dict);
        controller.clearCacheAll();
        verify(redis).keys("sys_config:*");verify(redis).keys("sys_dict:*");
        verify(redis).delete(config);verify(redis).delete(dict);verifyNoMoreInteractions(redis);
    }

    @Test
    void supportsSingleConfigurationAndEmptyDictionaryRefresh() {
        controller.clearCacheKey("sys_config:sys.index.skinName");
        controller.clearCacheName("sys_dict:");
        verify(redis).delete("sys_config:sys.index.skinName");verify(redis).keys("sys_dict:*");
        verifyNoMoreInteractions(redis);
    }
}

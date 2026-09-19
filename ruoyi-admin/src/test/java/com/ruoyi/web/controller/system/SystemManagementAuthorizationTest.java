package com.ruoyi.web.controller.system;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.ruoyi.common.core.domain.entity.SysMenu;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.framework.web.service.PermissionService;
import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.system.service.ISysDeptService;
import com.ruoyi.system.service.ISysMenuService;
import com.ruoyi.system.service.ISysPostService;
import com.ruoyi.system.service.ISysRoleService;
import com.ruoyi.system.service.ISysUserService;

/** Uses real method-security proxies and permission evaluation, without database or Redis access. */
class SystemManagementAuthorizationTest
{
    private static final List<Class<?>> CONTROLLERS = Arrays.asList(
            SysUserController.class, SysRoleController.class, SysMenuController.class);
    private AnnotationConfigApplicationContext context;

    @BeforeEach
    void setUp()
    {
        context = new AnnotationConfigApplicationContext();
        // Register concrete-service mocks as initialized singletons: @Bean mocks would still receive
        // the production class's @Value/@Autowired injection (token secrets and Redis dependencies).
        context.getBeanFactory().registerSingleton("tokenService", mock(TokenService.class));
        context.getBeanFactory().registerSingleton("permissionService", mock(SysPermissionService.class));
        context.register(TestConfiguration.class);
        context.refresh();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void tearDown()
    {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        context.close();
    }

    @Test
    void explicitlyDelegatedSystemPermissionsCannotMutateUsersRolesOrMenus() throws Exception
    {
        login(2L, 103L, "club_reviewer", new HashSet<>(Arrays.asList(
                "system:user:import", "system:user:add", "system:user:edit", "system:user:remove",
                "system:user:resetPwd", "system:role:add", "system:role:edit", "system:role:remove",
                "system:menu:add", "system:menu:edit", "system:menu:remove")));
        assertAllWritesDenied();
    }

    @Test
    void wildcardPermissionsAndAdminRoleDoNotMakeAnOrdinaryAccountASuperadmin() throws Exception
    {
        // Even role_id=1, role_key=admin and *:*:* must not substitute for the actual superadmin user ID.
        login(2L, 1L, "admin", Collections.singleton("*:*:*"));
        assertAllWritesDenied();
    }

    @Test
    void grantingWildcardThroughMenuEditIsDeniedBeforePersistence()
    {
        login(2L, 105L, "club_admin", Collections.singleton("system:menu:edit"));
        SysMenu menu = new SysMenu();
        menu.setMenuId(2332L);
        menu.setPerms("*:*:*");
        assertThrows(AccessDeniedException.class, () -> context.getBean(SysMenuController.class).edit(menu));
        verifyNoInteractions(context.getBean(ISysMenuService.class));
    }

    @Test
    void superadminIdentityDoesNotReplaceTheOriginalPermissionCheck() throws Exception
    {
        login(1L, 1L, "admin", Collections.emptySet());
        assertAllWritesDenied();
    }

    @Test
    void actualSuperadminPassesEveryWriteGuardAndCanReachPersistence() throws Exception
    {
        login(1L, 1L, "admin", Collections.singleton("*:*:*"));
        StandardEvaluationContext evaluation = new StandardEvaluationContext();
        evaluation.setBeanResolver((ignored, name) -> context.getBean(name));
        SpelExpressionParser parser = new SpelExpressionParser();
        for (Method method : writeEndpoints())
        {
            PreAuthorize authorization = method.getAnnotation(PreAuthorize.class);
            assertNotNull(authorization, method.toString());
            assertEquals(Boolean.TRUE, parser.parseExpression(authorization.value()).getValue(evaluation, Boolean.class),
                    method.toString());
        }
        ISysMenuService menus = context.getBean(ISysMenuService.class);
        when(menus.deleteMenuById(2332L)).thenReturn(1);
        assertNotNull(context.getBean(SysMenuController.class).remove(2332L));
        verify(menus).deleteMenuById(2332L);
    }

    @Test
    void readOnlySystemPermissionCanStillBeExplicitlyDelegated()
    {
        login(2L, 103L, "club_reviewer", Collections.singleton("system:menu:list"));
        SysMenu query = new SysMenu();
        ISysMenuService menus = context.getBean(ISysMenuService.class);
        when(menus.selectMenuList(query, 2L)).thenReturn(Collections.emptyList());
        assertNotNull(context.getBean(SysMenuController.class).list(query));
        verify(menus).selectMenuList(query, 2L);
    }

    private void assertAllWritesDenied() throws Exception
    {
        List<Method> endpoints = writeEndpoints();
        assertEquals(18, endpoints.size(), "Inventory must include every user, role and menu mutation endpoint");
        for (Method method : endpoints)
        {
            Object[] arguments = new Object[method.getParameterCount()];
            for (int i = 0; i < arguments.length; i++)
            {
                if (method.getParameterTypes()[i] == boolean.class)
                {
                    arguments[i] = false;
                }
            }
            Object proxy = context.getBean(method.getDeclaringClass());
            InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                    () -> method.invoke(proxy, arguments), method.toString());
            assertTrue(failure.getCause() instanceof AccessDeniedException, method + ": " + failure.getCause());
        }
        verifyNoInteractions(context.getBean(ISysUserService.class), context.getBean(ISysRoleService.class),
                context.getBean(ISysMenuService.class), context.getBean(ISysDeptService.class),
                context.getBean(ISysPostService.class));
    }

    private static List<Method> writeEndpoints()
    {
        List<Method> endpoints = new ArrayList<>();
        for (Class<?> controller : CONTROLLERS)
        {
            for (Method method : controller.getDeclaredMethods())
            {
                boolean mappedWrite = method.isAnnotationPresent(PostMapping.class)
                        || method.isAnnotationPresent(PutMapping.class) || method.isAnnotationPresent(DeleteMapping.class);
                // These POST endpoints only produce a download and do not alter persisted state.
                if (mappedWrite && !"export".equals(method.getName()) && !"importTemplate".equals(method.getName()))
                {
                    endpoints.add(method);
                }
            }
        }
        return endpoints;
    }

    private static void login(Long userId, Long roleId, String roleKey, Set<String> permissions)
    {
        SysRole role = new SysRole();
        role.setRoleId(roleId);
        role.setRoleKey(roleKey);
        role.setDataScope("1");
        SysUser user = new SysUser();
        user.setUserId(userId);
        user.setUserName("authorization-test");
        user.setRoles(Collections.singletonList(role));
        LoginUser principal = new LoginUser(userId, 100L, user, permissions);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, Collections.emptyList()));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity(prePostEnabled = true, securedEnabled = true)
    static class TestConfiguration
    {
        @Bean SysUserController users() { return new SysUserController(); }
        @Bean SysRoleController roles() { return new SysRoleController(); }
        @Bean SysMenuController menus() { return new SysMenuController(); }
        @Bean("ss") PermissionService permissionChecks() { return new PermissionService(); }
        @Bean ISysUserService userService() { return mock(ISysUserService.class); }
        @Bean ISysRoleService roleService() { return mock(ISysRoleService.class); }
        @Bean ISysMenuService menuService() { return mock(ISysMenuService.class); }
        @Bean ISysDeptService deptService() { return mock(ISysDeptService.class); }
        @Bean ISysPostService postService() { return mock(ISysPostService.class); }
    }
}

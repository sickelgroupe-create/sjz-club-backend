package com.ruoyi.club.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ruoyi.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ClubIdentityCryptoServiceTest
{
    @Test
    void encryptsWithRandomIvAndDecryptsExactly()
    {
        ClubIdentityCryptoService service = configured();
        String first = service.encrypt("张三|13800000000|110101199001011234");
        String second = service.encrypt("张三|13800000000|110101199001011234");

        assertNotEquals(first, second, "相同材料也必须使用不同随机IV");
        assertEquals("张三|13800000000|110101199001011234", service.decrypt(first));
        assertEquals("张三|13800000000|110101199001011234", service.decrypt(second));
    }

    @Test
    void refusesMissingKeyAndTamperedCiphertext()
    {
        ClubIdentityCryptoService missing = new ClubIdentityCryptoService();
        assertThrows(ServiceException.class, () -> missing.encrypt("敏感材料"));

        ClubIdentityCryptoService service = configured();
        String encrypted = service.encrypt("敏感材料");
        String tampered = encrypted.substring(0, encrypted.length() - 2) + "AA";
        assertThrows(ServiceException.class, () -> service.decrypt(tampered));
    }

    private ClubIdentityCryptoService configured()
    {
        ClubIdentityCryptoService service = new ClubIdentityCryptoService();
        ReflectionTestUtils.setField(service, "configuredKey", "test-only-identity-key-32-characters-minimum");
        return service;
    }
}

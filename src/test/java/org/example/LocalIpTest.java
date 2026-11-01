package org.example;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** 本机 IP 自动检测：显式配置优先，auto 必须返回可用地址。 */
public class LocalIpTest {

    @Test
    public void explicitAddressIsKept() {
        assertEquals("10.1.2.3", LocalIp.resolve("10.1.2.3", "10.1.2.1", 5060));
    }

    @Test
    public void autoReturnsUseableAddress() {
        String ip = LocalIp.resolve("auto", "127.0.0.1", 5060);
        assertNotNull(ip);
        assertFalse(ip.isEmpty());
        assertFalse("auto".equalsIgnoreCase(ip));
    }

    @Test
    public void blankAlsoMeansAuto() {
        String ip = LocalIp.resolve("  ", "127.0.0.1", 5060);
        assertNotNull(ip);
        assertFalse("auto".equalsIgnoreCase(ip));
    }

    @Test
    public void virtualInterfaceGuardHandlesNull() {
        assertTrue(LocalIp.isVirtual(null));
    }
}

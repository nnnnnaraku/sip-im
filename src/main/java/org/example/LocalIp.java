package org.example;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 本机 IPv4 自动检测。
 *
 * <p>配置 {@code sip.local.ip=auto}（或留空）时使用：
 * <ol>
 *   <li>优先选择与 SIP 服务器处于同一子网的物理网卡地址；</li>
 *   <li>否则选择第一个可用的站点本地 IPv4，跳过 VMware / VirtualBox / Hyper-V / VPN 等虚拟网卡；</li>
 *   <li>都无法判断时回退到 127.0.0.1，保证 Web 界面仍能启动（媒体可能不可用）。</li>
 * </ol>
 * 这样部署到老师电脑时不需要手工填写本机 IP。
 */
public final class LocalIp {

    private LocalIp() {
    }

    public static String resolve(String configured, String serverIp, int serverPort) {
        String value = configured == null ? "" : configured.trim();
        if (!value.isEmpty() && !"auto".equalsIgnoreCase(value)) {
            return value;
        }
        InetAddress server = parse(serverIp);
        String detected = sameSubnetAddress(server);
        if (detected == null) detected = preferredAddress();
        if (detected == null) detected = "127.0.0.1";
        System.out.println("✓ 已自动检测本机 IP: " + detected
                + (server == null ? "（未解析到服务器地址）" : "（服务器 " + serverIp + ":" + serverPort + "）"));
        return detected;
    }

    /** 选择与服务器同一子网的 IPv4 地址；服务器不可解析时返回 null。 */
    static String sameSubnetAddress(InetAddress server) {
        if (!(server instanceof Inet4Address)) return null;
        int target = toInt(server.getAddress());
        for (NetworkInterface nif : interfaces()) {
            if (isVirtual(nif)) continue;
            for (java.net.InterfaceAddress ia : nif.getInterfaceAddresses()) {
                if (!(ia.getAddress() instanceof Inet4Address)) continue;
                int prefix = ia.getNetworkPrefixLength();
                if (prefix < 0 || prefix > 32) continue;
                int mask = prefix == 0 ? 0 : 0xFFFFFFFF << (32 - prefix);
                if ((toInt(ia.getAddress().getAddress()) & mask) == (target & mask)) {
                    return ia.getAddress().getHostAddress();
                }
            }
        }
        return null;
    }

    /** 没有同子网网卡时，按“无线 > 有线 > 其它”选择一个站点本地 IPv4。 */
    static String preferredAddress() {
        List<NetworkInterface> candidates = new ArrayList<>();
        for (NetworkInterface nif : interfaces()) {
            if (isVirtual(nif) || !hasSiteLocalIPv4(nif)) continue;
            candidates.add(nif);
        }
        candidates.sort(Comparator.comparingInt(n -> preference(n.getName() + " " + n.getDisplayName())));
        for (NetworkInterface nif : candidates) {
            for (java.net.InterfaceAddress ia : nif.getInterfaceAddresses()) {
                if (ia.getAddress() instanceof Inet4Address && ia.getAddress().isSiteLocalAddress()) {
                    return ia.getAddress().getHostAddress();
                }
            }
        }
        return null;
    }

    static boolean isVirtual(NetworkInterface nif) {
        if (nif == null) return true;
        try {
            if (!nif.isUp() || nif.isLoopback()) return true;
        } catch (Exception e) {
            return true;
        }
        String name = (nif.getName() + " " + nif.getDisplayName()).toLowerCase(Locale.ROOT);
        return name.contains("vmware") || name.contains("virtualbox") || name.contains("vbox")
                || name.contains("hyper-v") || name.contains("vethernet") || name.contains("docker")
                || name.contains("wsl") || name.contains("loopback") || name.contains("bluetooth")
                || name.contains("tap") || name.contains("tun") || name.contains("vpn")
                || name.contains("zerotier") || name.contains("tailscale");
    }

    private static boolean hasSiteLocalIPv4(NetworkInterface nif) {
        for (java.net.InterfaceAddress ia : nif.getInterfaceAddresses()) {
            if (ia.getAddress() instanceof Inet4Address && ia.getAddress().isSiteLocalAddress()) return true;
        }
        return false;
    }

    private static int preference(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("wi-fi") || n.contains("wifi") || n.contains("wlan") || n.contains("wireless")) return 0;
        if (n.contains("ethernet") || n.contains("eth")) return 1;
        return 2;
    }

    private static List<NetworkInterface> interfaces() {
        try {
            return Collections.list(NetworkInterface.getNetworkInterfaces());
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static InetAddress parse(String serverIp) {
        if (serverIp == null || serverIp.trim().isEmpty() || "auto".equalsIgnoreCase(serverIp.trim())) {
            return null;
        }
        try {
            return InetAddress.getByName(serverIp.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static int toInt(byte[] bytes) {
        return ((bytes[0] & 0xFF) << 24) | ((bytes[1] & 0xFF) << 16)
                | ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF);
    }
}

package com.nexuschat.util;

import java.net.NetworkInterface;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.UUID;

/**
 * 本机 deviceId 生成与加载 —— 与 ARCHITECTURE.md 附录 B 保持一致。
 * <p>
 * 首次启动：MAC + UUID → SHA256 → 前 8 字符，写入 ~/.nexuschat/device_id。
 * 后续启动：直接读取文件，终身不变。
 */
public final class DeviceIdGenerator {

    private DeviceIdGenerator() {}

    private static final java.nio.file.Path DATA_DIR =
        java.nio.file.Path.of(System.getProperty("user.home"), ".nexuschat");
    private static final java.nio.file.Path DEVICE_ID_FILE = DATA_DIR.resolve("device_id");

    /**
     * 首次启动生成；后续启动从文件加载。保证同一台机器每次都是同一个值。
     */
    public static synchronized String loadOrGenerate() {
        if (DEVICE_ID_FILE.toFile().exists()) {
            try {
                return java.nio.file.Files.readString(DEVICE_ID_FILE).trim().toUpperCase();
            } catch (Exception ignored) {
                // 文件损坏，重新生成
            }
        }

        try {
            java.nio.file.Files.createDirectories(DATA_DIR);
            String mac = getPrimaryMacAddress();
            String raw = mac + "-" + UUID.randomUUID();
            String hash = sha256Hex(raw).substring(0, 8).toUpperCase();
            java.nio.file.Files.writeString(DEVICE_ID_FILE, hash);
            return hash;
        } catch (Exception e) {
            throw new RuntimeException("deviceId 生成失败", e);
        }
    }

    private static String getPrimaryMacAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                byte[] mac = ni.getHardwareAddress();
                if (mac != null && mac.length == 6) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 6; i++) {
                        if (i > 0) sb.append(':');
                        sb.append(String.format("%02X", mac[i]));
                    }
                    return sb.toString();
                }
            }
        } catch (Exception ignored) {
        }
        return "00:00:00:00:00:00"; // 兜底
    }

    private static String sha256Hex(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] bytes = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}

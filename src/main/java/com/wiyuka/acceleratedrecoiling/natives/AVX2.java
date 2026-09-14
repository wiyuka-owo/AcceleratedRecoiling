package com.wiyuka.acceleratedrecoiling.natives;

import com.sun.management.HotSpotDiagnosticMXBean;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AVX2 {
    private static Boolean hasAVX2Cache = null;

    public static boolean hasAVX2JMX() {
        if (hasAVX2Cache != null) return hasAVX2Cache;
        try {
            HotSpotDiagnosticMXBean mxBean = ManagementFactory.newPlatformMXBeanProxy(
                    ManagementFactory.getPlatformMBeanServer(),
                    "com.sun.management:type=HotSpotDiagnostic",
                    HotSpotDiagnosticMXBean.class);

            String avxValue = mxBean.getVMOption("UseAVX").getValue();
            int avxLevel = Integer.parseInt(avxValue);
            hasAVX2Cache = (avxLevel >= 2);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return hasAVX2Cache;
    }

    public static boolean hasAVX2() {
        try {
            return hasAVX2JMX();
        } catch (Throwable e) {
            return hasAVX2Java();
        }
    }

    private static boolean hasAVX2Java() {
        Process process = null;
        try {
            String javaHome = System.getProperty("java.home");
            String javaCmd = javaHome + File.separator + "bin" + File.separator + "java";
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                javaCmd += ".exe";
            }
            ProcessBuilder pb = new ProcessBuilder(javaCmd, "-XX:+PrintFlagsFinal", "-version");
            pb.redirectErrorStream(true);
            process = pb.start();
            Pattern pattern = Pattern.compile("UseAVX\\s*(:?=)\\s*(\\d+)");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher matcher = pattern.matcher(line);
                    if (matcher.find()) {
                        int avxLevel = Integer.parseInt(matcher.group(2));
                        return avxLevel >= 2;
                    }
                }
            }
        } catch (Exception e) {
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return false;
    }
}

package com.lifeos.ops;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Reboots a residential Starlink router or dish via the unauthenticated local gRPC API.
 * <p>
 * Router default: 192.168.1.1:9000<br>
 * Dish default: 192.168.100.1:9200<br>
 * Uses {@code grpcurl} (installed in the Docker image). The process must be able to reach the LAN address.
 */
@Service
public class StarlinkService {

    @Value("${starlink.router.addr:192.168.1.1:9000}")
    private String routerAddr;

    @Value("${starlink.dish.addr:192.168.100.1:9200}")
    private String dishAddr;

    public Map<String, Object> reboot(String target) {
        String addr;
        String resolvedTarget;
        if ("dish".equalsIgnoreCase(target)) {
            addr = dishAddr;
            resolvedTarget = "dish";
        } else {
            addr = routerAddr;
            resolvedTarget = "router";
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", resolvedTarget);
        result.put("address", addr);

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "grpcurl", "-plaintext", "-d", "{\"reboot\":{}}",
                    addr, "SpaceX.API.Device.Device/Handle");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            boolean finished = p.waitFor(20, TimeUnit.SECONDS);
            int exit = finished ? p.exitValue() : -1;
            if (!finished) {
                p.destroyForcibly();
                result.put("error", "timeout");
            }
            result.put("ok", exit == 0);
            result.put("exitCode", exit);
            result.put("output", output.toString().trim());
        } catch (Exception e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
        }
        return result;
    }
}

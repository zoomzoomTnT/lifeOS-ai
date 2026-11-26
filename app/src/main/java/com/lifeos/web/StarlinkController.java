package com.lifeos.web;

import com.lifeos.ops.StarlinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Local control for a residential Starlink.
 * Reboot is unauthenticated on the LAN gRPC endpoint; protect the Life OS API as usual.
 */
@RestController
@RequestMapping("/api/starlink")
@RequiredArgsConstructor
public class StarlinkController {

    private final StarlinkService starlinkService;

    /**
     * Reboot the Starlink router (default) or dish.
     * Query or body {@code target=router|dish}.
     */
    @PostMapping("/reboot")
    public ResponseEntity<Map<String, Object>> reboot(
            @RequestParam(required = false, defaultValue = "router") String target,
            @RequestBody(required = false) Map<String, Object> body) {
        if (body != null && body.get("target") != null) {
            target = String.valueOf(body.get("target"));
        }
        Map<String, Object> result = starlinkService.reboot(target);
        boolean ok = Boolean.TRUE.equals(result.get("ok"));
        return ok ? ResponseEntity.ok(result) : ResponseEntity.status(502).body(result);
    }
}

package com.lifeos.security.approval;

import com.lifeos.security.ApiKeyStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static org.springframework.web.util.HtmlUtils.htmlEscape;

/**
 * /approve/{id} on the owner chain (passkey session). Shows what will happen from the server's own
 * data and lets the owner approve or deny. Deciding needs a passkey assertion from the last
 * 5 minutes; otherwise the page asks for one first (and comes back here).
 */
@Controller
public class ApprovalPages {

    private final ApprovalService approvals;
    private final ApiKeyStore store;
    private final Clock clock;
    private final ZoneId zone;
    private final java.time.Duration passkeyMaxAge;
    private final HttpSessionRequestCache requestCache = new HttpSessionRequestCache();

    public ApprovalPages(ApprovalService approvals, ApiKeyStore store, Clock authClock,
                         @Value("${life.owner-timezone:Asia/Tokyo}") String zone,
                         @Value("${life.auth.passkey-max-age:PT5M}") java.time.Duration passkeyMaxAge) {
        this.passkeyMaxAge = passkeyMaxAge;
        this.approvals = approvals;
        this.store = store;
        this.clock = authClock;
        this.zone = ZoneId.of(zone);
    }

    String time(Instant i) {
        return i == null ? "" : DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").format(i.atZone(zone));
    }

    static String page(String title, String body) {
        return "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>" + htmlEscape(title)
                + "</title><link href=\"/default-ui.css\" rel=\"stylesheet\"/></head><body><div class=\"content\">"
                + "<div class=\"login-form\">" + body + "</div></div></body></html>";
    }

    String details(ApprovalService.Approval a) {
        StringBuilder sb = new StringBuilder("<h2>").append(htmlEscape(a.headline())).append("</h2><table>");
        row(sb, "Requested by", a.clientId() + (a.credentialId() == null ? "" : " (key " + a.credentialId() + ")"));
        row(sb, "Request", a.method() + " " + a.path());
        row(sb, "Action", a.action() + " (tier " + a.tier() + ")");
        for (Map.Entry<String, Object> e : a.summary().entrySet()) row(sb, e.getKey(), String.valueOf(e.getValue()));
        row(sb, "Requested", time(a.createdAt()));
        row(sb, "Expires", time(a.expiresAt()));
        row(sb, "Status", a.status());
        return sb.append("</table>").toString();
    }

    static void row(StringBuilder sb, String k, String v) {
        sb.append("<tr><th style=\"text-align:left;padding-right:1em\">").append(htmlEscape(k)).append("</th><td>")
                .append(htmlEscape(v)).append("</td></tr>");
    }

    @GetMapping(value = "/approve/{id}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> show(@PathVariable String id, Authentication auth, CsrfToken csrf,
                                       HttpServletRequest req, HttpServletResponse res) {
        var found = approvals.view(id);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(page("Not found", "<h2>No such approval request</h2>"));
        }
        ApprovalService.Approval a = found.get();
        StringBuilder body = new StringBuilder(details(a));
        if (a.status().equals("pending")) {
            if (PasskeyFreshness.isFresh(auth, clock, passkeyMaxAge)) {
                String hidden = "<input type=\"hidden\" name=\"" + htmlEscape(csrf.getParameterName()) + "\" value=\""
                        + htmlEscape(csrf.getToken()) + "\"/>";
                body.append("<form method=\"post\" action=\"/approve/").append(htmlEscape(a.publicId())).append("\">")
                        .append(hidden).append("<button class=\"primary\" type=\"submit\" name=\"decision\" value=\"approve\">")
                        .append("Approve (single use)</button> <button type=\"submit\" name=\"decision\" value=\"deny\">Deny</button></form>");
            } else {
                // come back here after the passkey assertion
                requestCache.saveRequest(req, res);
                String headers = "{\"" + htmlEscape(csrf.getHeaderName()) + "\":\"" + htmlEscape(csrf.getToken()) + "\"}";
                body.append("<p>Confirm it's you with your passkey to decide.</p>")
                        .append("<button id=\"passkey-signin\" class=\"primary\" type=\"button\">Confirm with passkey</button>")
                        .append("<script src=\"/login/webauthn.js\"></script><script>document.addEventListener('DOMContentLoaded',")
                        .append("()=>setupLogin(").append(headers).append(",'',document.getElementById('passkey-signin')));</script>");
            }
        }
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(page("Approve: " + a.action(), body.toString()));
    }

    @PostMapping(value = "/approve/{id}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> decide(@PathVariable String id, @RequestParam String decision, Authentication auth) {
        if (!PasskeyFreshness.isFresh(auth, clock, passkeyMaxAge)) {
            return ResponseEntity.status(HttpStatus.SEE_OTHER).header("Location", "/approve/" + id).build();
        }
        if (!decision.equals("approve") && !decision.equals("deny")) {
            return ResponseEntity.badRequest().body(page("Bad request", "<h2>Unknown decision</h2>"));
        }
        var result = approvals.decide(id, decision.equals("approve"), store.ownerPersonId());
        if (result.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(page("Not found", "<h2>No such approval request</h2>"));
        }
        ApprovalService.Approval a = result.get();
        String msg = switch (a.status()) {
            case "approved" -> "<p class=\"alert alert-success\">Approved. " + htmlEscape(a.clientId())
                    + " can now repeat the request once, until " + htmlEscape(time(a.expiresAt())) + ".</p>";
            case "denied" -> "<p class=\"alert alert-success\">Denied.</p>";
            default -> "<p class=\"alert alert-danger\">Nothing changed: this request is " + htmlEscape(a.status()) + ".</p>";
        };
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(page("Approval", msg + details(a)));
    }
}

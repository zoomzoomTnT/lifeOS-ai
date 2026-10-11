package com.lifeos.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.util.HtmlUtils;

/**
 * Minimal owner pages. Passkey sign-in only: no password form and no "email me a token" form
 * (one-time tokens come only from {@code lifeos-admin owner-login}).
 */
@Controller
public class OwnerPages {

    static final String STYLE = "<link href=\"/default-ui.css\" rel=\"stylesheet\"/>";

    static String page(String title, String body) {
        return "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>" + HtmlUtils.htmlEscape(title) + "</title>" + STYLE + "</head><body><div class=\"content\">"
                + body + "</div></body></html>";
    }

    @GetMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String login(HttpServletRequest request, CsrfToken csrf) {
        String notice = request.getParameter("logout") != null ? "<p class=\"alert alert-success\">Signed out.</p>"
                : request.getParameter("error") != null ? "<p class=\"alert alert-danger\">Passkey sign-in failed.</p>"
                : "";
        String headers = "{\"" + HtmlUtils.htmlEscape(csrf.getHeaderName()) + "\":\""
                + HtmlUtils.htmlEscape(csrf.getToken()) + "\"}";
        return page("lifeOS sign in", notice
                + "<div class=\"login-form\"><h2>lifeOS</h2>"
                + "<button id=\"passkey-signin\" type=\"submit\" class=\"primary\">Sign in with a passkey</button>"
                + "<p>No passkey yet, or lost it? On the server run <code>lifeos-admin owner-login</code> and open "
                + "the link it prints (valid 5 minutes, single use), then register a passkey.</p></div>"
                + "<script src=\"/login/webauthn.js\"></script><script>document.addEventListener('DOMContentLoaded',"
                + "()=>setupLogin(" + headers + ",'',document.getElementById('passkey-signin')));</script>");
    }

    @GetMapping(value = "/admin", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String home(CsrfToken csrf) {
        return page("lifeOS owner", "<div class=\"login-form\"><h2>Signed in with your passkey</h2><ul>"
                + "<li><a href=\"/ops\">Ops dashboard</a></li>"
                + "<li><a href=\"/webauthn/register\">Manage passkeys</a></li></ul>"
                + "<form method=\"post\" action=\"/logout\"><input type=\"hidden\" name=\""
                + HtmlUtils.htmlEscape(csrf.getParameterName()) + "\" value=\"" + HtmlUtils.htmlEscape(csrf.getToken())
                + "\"/><button type=\"submit\">Sign out</button></form></div>");
    }
}

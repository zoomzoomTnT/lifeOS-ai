package com.lifeos.security.approval;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.UnsatisfiedServletRequestParameterException;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * Does a controller handle this request? Asked before the approval gate creates an approval request,
 * so that e.g. DELETE on a path no handler serves answers 404/405 like it would without the gate,
 * instead of "approval_required" plus a pending approval row for an action that does not exist.
 */
public class RouteCheck {

    private final ObjectProvider<RequestMappingHandlerMapping> mappings;

    public RouteCheck(ObjectProvider<RequestMappingHandlerMapping> mappings) {
        this.mappings = mappings;
    }

    /** 200 = handled, 404 = no such route, 405 = route exists for other methods. */
    public int status(HttpServletRequest req) throws ServletException {
        RequestMappingHandlerMapping m = mappings.getIfAvailable();
        if (m == null) return 200; // no MVC (not in this app): don't block
        Object previous = req.getAttribute(ServletRequestPathUtils.PATH_ATTRIBUTE);
        try {
            ServletRequestPathUtils.parseAndCache(req);
            return m.getHandler(req) != null ? 200 : 404;
        } catch (HttpRequestMethodNotSupportedException e) {
            return 405;
        } catch (HttpMediaTypeNotSupportedException | HttpMediaTypeNotAcceptableException
                 | UnsatisfiedServletRequestParameterException e) {
            return 200; // the route exists but this request doesn't fit it: let MVC answer (415/406/400)
        } catch (Exception e) {
            throw new ServletException("handler lookup failed for " + req.getMethod() + " " + req.getRequestURI(), e);
        } finally {
            if (previous != null) req.setAttribute(ServletRequestPathUtils.PATH_ATTRIBUTE, previous);
            else req.removeAttribute(ServletRequestPathUtils.PATH_ATTRIBUTE);
        }
    }
}

package org.example.patrol.security;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.example.patrol.app.AuditService;
import org.example.patrol.client.ApiResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.io.IOException;

import javax.servlet.http.*;

@Component
public class SecurityErrors {
    private final ObjectMapper json;
    private final AuditService audit;

    public SecurityErrors(ObjectMapper json, AuditService audit) {
        this.json = json;
        this.audit = audit;
    }

    public void auditFailure(HttpServletRequest req, String code) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        Actor actor =
                auth != null && auth.getPrincipal() instanceof Actor
                        ? (Actor) auth.getPrincipal()
                        : null;
        // Never copy request bodies, query strings, credentials or exception messages to audit
        // storage.
        String target = req.getMethod() + " " + req.getServletPath();
        if (target.length() > 180) target = target.substring(0, 180);
        try {
            audit.failure(
                    actor == null ? null : actor.id(),
                    actor == null ? "anonymous" : actor.username(),
                    "AUTH_FAILURE",
                    target,
                    code);
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Authentication audit unavailable");
        }
    }

    public void write(
            HttpServletRequest req,
            HttpServletResponse res,
            int status,
            String code,
            String message)
            throws IOException {
        auditFailure(req, code);
        res.setStatus(status);
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        if (status == 401) res.setHeader("WWW-Authenticate", "Bearer");
        json.writeValue(res.getOutputStream(), ApiResponse.error(code, message));
    }
}

package sg.edu.ntu.taskflowapi.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Security response headers, added because the ZAP baseline scan in
 * .github/workflows/zap-baseline.yml reported them missing:
 *
 * <ul>
 *   <li>10021 X-Content-Type-Options Header Missing
 *   <li>10035 Strict-Transport-Security Header Not Set
 *   <li>90004 Cross-Origin-Resource-Policy Header Missing or Invalid
 *   <li>10015 Re-examine Cache-control Directives
 * </ul>
 *
 * <p>A filter rather than Spring Security: this application has no authentication and
 * pulling in the full security starter to set four headers would add a dependency
 * surface that has to be patched forever.
 *
 * <p>Cross-Origin-Resource-Policy is {@code cross-origin} on purpose. The stricter
 * {@code same-origin} would be wrong here: the Netlify frontend calls this API from a
 * different origin, and the scan asks for the header to be present and valid, not for it
 * to lock the API down. Access control is CORS's job, which is configured separately.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        response.setHeader("Cross-Origin-Resource-Policy", "cross-origin");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        // Every response here is live data from an in-memory store; caching it would
        // serve stale tasks. This also answers ZAP's cache-control directive warning.
        response.setHeader("Cache-Control", "no-store");

        chain.doFilter(request, response);
    }
}

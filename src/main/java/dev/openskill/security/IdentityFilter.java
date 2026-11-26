package dev.openskill.security;

import dev.openskill.domain.Json;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class IdentityFilter extends OncePerRequestFilter {
  private static final Logger LOG = LogManager.getLogger(IdentityFilter.class);
  private final boolean local;

  public IdentityFilter(@Value("${skill.auth.mode}") String mode) {
    local = "local".equals(mode);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest r) {
    return !r.getRequestURI().startsWith("/api") && !r.getRequestURI().startsWith("/internal");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest r, HttpServletResponse s, FilterChain chain)
      throws ServletException, IOException {
    long started = System.nanoTime();
    String trace = Json.id();
    MDC.put("traceId", trace);
    s.setHeader("X-Trace-Id", trace);
    s.setHeader("X-Content-Type-Options", "nosniff");
    LOG.info("接口请求开始，方法={}，路径={}", r.getMethod(), r.getRequestURI());
    try {
      if (local) {
        if (!Set.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1").contains(r.getRemoteAddr())
            || !Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(r.getServerName())) {
          deny(s, 403, "仅允许本地开发访问");
          return;
        }
        Actor.set(new Actor("local", "local-developer"));
      } else {
        if (r.getUserPrincipal() == null) {
          deny(s, 401, "请通过企业 SSO 登录");
          return;
        }
        String role = permission(r);
        if (!r.isUserInRole("skill-admin") && !r.isUserInRole("skill-" + role)) {
          deny(s, 403, "无权执行此操作");
          return;
        }
        // Populated only by the trusted SSO integration, never from browser headers.
        Object workspace = r.getAttribute("skill.workspace");
        if (workspace == null) {
          deny(s, 403, "SSO 未提供工作空间上下文");
          return;
        }
        Actor.set(new Actor(workspace.toString(), r.getUserPrincipal().getName()));
      }
      if (!Set.of("GET", "HEAD", "OPTIONS").contains(r.getMethod())) {
        String contentType =
            r.getContentType() == null ? "" : r.getContentType().toLowerCase(java.util.Locale.ROOT);
        boolean packageUpload =
            "POST".equals(r.getMethod())
                && "/api/skills/import".equals(r.getRequestURI())
                && contentType.startsWith("multipart/form-data");
        if (r.getContentLengthLong() > 0
            && !contentType.startsWith("application/json")
            && !packageUpload) {
          deny(s, 415, "写入请求需要 application/json，完整包导入使用 multipart/form-data");
          return;
        }
        String origin = r.getHeader("Origin");
        String expected = r.getScheme() + "://" + r.getHeader("Host");
        boolean normalized =
            local
                && ("http://" + r.getServerName()).equals(origin)
                && "same-origin".equals(r.getHeader("Sec-Fetch-Site"));
        if ("cross-site".equals(r.getHeader("Sec-Fetch-Site"))
            || (origin != null && !expected.equals(origin) && !normalized)) {
          deny(s, 403, "拒绝跨站请求");
          return;
        }
        if (!local) {
          var session = r.getSession(false);
          Object token = session == null ? null : session.getAttribute("skill.csrf");
          if (token == null || !token.equals(r.getHeader("X-CSRF-Token"))) {
            deny(s, 403, "CSRF token 缺失或失效");
            return;
          }
        }
      }
      chain.doFilter(r, s);
    } finally {
      LOG.info(
          "接口请求结束，方法={}，路径={}，状态码={}，耗时={} 毫秒",
          r.getMethod(),
          r.getRequestURI(),
          s.getStatus(),
          (System.nanoTime() - started) / 1_000_000);
      Actor.clear();
      MDC.remove("traceId");
    }
  }

  private String permission(HttpServletRequest r) {
    String p = r.getRequestURI();
    if (p.startsWith("/api/auth/")) return "read";
    if (p.startsWith("/internal") || p.startsWith("/api/audit-events")) return "admin";
    if (p.startsWith("/api/credentials")) return "credentials";
    if (p.endsWith("/export")) return "export";
    if ("GET".equals(r.getMethod())) return "read";
    if (p.equals("/api/publish")) return "publish";
    if (p.startsWith("/api/schedules")) return "schedule";
    if (p.startsWith("/api/runs") || p.equals("/api/run") || p.equals("/api/node-run"))
      return "execute";
    return "edit";
  }

  private void deny(HttpServletResponse r, int status, String message) throws IOException {
    LOG.warn("接口请求被拒绝，状态码={}，原因={}", status, message);
    r.setStatus(status);
    r.setContentType("application/json;charset=UTF-8");
    r.getWriter()
        .write(
            Json.obj(
                    "error",
                    message,
                    "code",
                    status == 401 ? "UNAUTHENTICATED" : "FORBIDDEN",
                    "traceId",
                    MDC.get("traceId"))
                .toString());
  }
}

package com.devmap.web;

import com.devmap.audit.AuditLog;
import com.devmap.config.ProjectService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * NFR-10 for PMO actions outside a ticket (project config, rules, conventions, manual edges, index runs): every successful
 * POST/PUT/DELETE under /api/projects/{project}/… is written to the audit log. Ticket actions are logged by the ticket services
 * with more detail (history.md), so /tasks/… is skipped here.
 */
@Component
public class AuditFilter extends OncePerRequestFilter {

  private static final Pattern PATH = Pattern.compile("^/api/projects/([^/]+)(/[^?]*)?$");
  private static final Pattern SERVICE = Pattern.compile("/rules/services/([^/]+)");
  private static final Set<String> WRITES = Set.of("POST", "PUT", "DELETE");

  private final AuditLog audit;
  private final ProjectService projects;

  public AuditFilter(AuditLog audit, ProjectService projects) {
    this.audit = audit;
    this.projects = projects;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    chain.doFilter(req, res);
    Matcher m = PATH.matcher(req.getRequestURI());
    String rest = m.matches() && m.group(2) != null ? m.group(2) : "";
    if (!WRITES.contains(req.getMethod()) || !m.matches() || res.getStatus() >= 400 || rest.startsWith("/tasks")) {
      return;
    }
    String project = URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
    try {
      Matcher s = SERVICE.matcher(rest);
      String service = s.find() ? URLDecoder.decode(s.group(1), StandardCharsets.UTF_8) : null;
      String rule = rest.startsWith("/rules") ? (rest.contains("conventions") ? "coding convention" : ".devrules.yml") : null;
      audit.add(Path.of(projects.get(project).dataDir()), "PMO", null, null, service, rule, req.getMethod() + " " + label(rest));
    } catch (RuntimeException e) {
      // project removed by this very request: nothing left to log into
    }
  }

  private static String label(String rest) {
    return switch (rest) {
      case "", "/" -> "dự án";
      case "/config" -> "sửa cấu hình dự án";
      case "/queue" -> "xếp lại hàng đợi";
      case "/queue/run" -> "chạy/dừng hàng đợi";
      case "/graph/update" -> "chạy cập nhật chỉ mục";
      case "/graph/edges" -> "thêm edge tay";
      case "/rules/default" -> "sửa rule mặc định của dự án";
      case "/rules/conventions" -> "sửa coding convention";
      case "/rules/conventions/collect" -> "trích coding convention từ code";
      case "/rules/conventions/approve" -> "duyệt coding convention";
      default -> rest.startsWith("/graph/edges/") ? "xoá edge tay" : rest.startsWith("/rules/services/") ? "rule của service" : rest;
    };
  }
}

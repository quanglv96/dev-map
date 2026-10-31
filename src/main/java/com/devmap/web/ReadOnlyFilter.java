package com.devmap.web;

import com.devmap.sync.SyncService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * FR-53: while another machine holds a project's data lock this machine is view-only for that project. Reads and the project's
 * sync endpoints (to take over or sync) still work; every other write to the project gets 423 so nothing diverges.
 */
@Component
public class ReadOnlyFilter extends OncePerRequestFilter {

  private static final Set<String> READS = Set.of("GET", "HEAD", "OPTIONS");
  private static final int LOCKED = 423;
  private static final Pattern PROJECT = Pattern.compile("^/api/projects/([^/]+)(/sync(/.*)?)?(/.*)?$");

  private final SyncService sync;

  public ReadOnlyFilter(SyncService sync) {
    this.sync = sync;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String uri = req.getRequestURI();
    Matcher m = PROJECT.matcher(uri);
    if (!READS.contains(req.getMethod()) && m.matches() && m.group(2) == null && sync.readOnly(m.group(1))) {
      res.setStatus(LOCKED);
      res.setContentType("application/json;charset=UTF-8");
      res.getWriter().write("{\"error\":\"Dự án đang mở ở máy khác: máy này chỉ xem. Bấm Chiếm quyền ở thanh đồng bộ để sửa.\"}");
      return;
    }
    chain.doFilter(req, res);
  }
}

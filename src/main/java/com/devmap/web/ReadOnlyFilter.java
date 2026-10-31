package com.devmap.web;

import com.devmap.sync.SyncService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * FR-53: while another machine holds the data lock this one is view-only. Reads, the sync endpoints (to take over or sync) and the
 * folder dialog still work; every other write gets 423 so nothing diverges between the two machines.
 */
@Component
public class ReadOnlyFilter extends OncePerRequestFilter {

  private static final Set<String> READS = Set.of("GET", "HEAD", "OPTIONS");
  private static final int LOCKED = 423;

  private final SyncService sync;

  public ReadOnlyFilter(SyncService sync) {
    this.sync = sync;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String uri = req.getRequestURI();
    if (sync.readOnly() && !READS.contains(req.getMethod()) && !uri.startsWith("/api/sync/") && !uri.equals("/api/folders/pick")) {
      res.setStatus(LOCKED);
      res.setContentType("application/json;charset=UTF-8");
      res.getWriter().write("{\"error\":\"DevMap đang mở ở máy khác: máy này chỉ xem. Bấm Chiếm quyền ở thanh đồng bộ để sửa.\"}");
      return;
    }
    chain.doFilter(req, res);
  }
}

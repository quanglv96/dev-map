package com.devmap.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * NFR-08: only loopback clients addressing localhost (blocks DNS rebinding), and only with the run token.
 * The link printed by `devmap serve` carries ?token=; it is swapped for an HttpOnly SameSite=Strict cookie.
 */
@Component
public class TokenFilter extends OncePerRequestFilter {

  public static final String COOKIE = "devmap_token";
  public static final String HEADER = "X-DevMap-Token";
  private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

  private final ServerToken token;

  public TokenFilter(ServerToken token) {
    this.token = token;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    if (!InetAddress.getByName(req.getRemoteAddr()).isLoopbackAddress() || !LOCAL_HOSTS.contains(req.getServerName())) {
      deny(res, HttpServletResponse.SC_FORBIDDEN, "DevMap chỉ nhận kết nối từ máy này (localhost).");
      return;
    }
    if ("GET".equals(req.getMethod()) && token.matches(req.getParameter("token"))) {
      ResponseCookie cookie = ResponseCookie.from(COOKIE, token.value()).httpOnly(true).sameSite("Strict").path("/").build();
      res.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
      res.sendRedirect(req.getRequestURI());
      return;
    }
    if (token.matches(req.getHeader(HEADER)) || token.matches(cookie(req))) {
      chain.doFilter(req, res);
      return;
    }
    deny(res, HttpServletResponse.SC_UNAUTHORIZED, "DevMap: mở bằng đường dẫn có token được in ra khi chạy `devmap serve`.");
  }

  private static String cookie(HttpServletRequest req) {
    return req.getCookies() == null ? null : Arrays.stream(req.getCookies())
        .filter(c -> COOKIE.equals(c.getName())).map(Cookie::getValue).findFirst().orElse(null);
  }

  private static void deny(HttpServletResponse res, int status, String message) throws IOException {
    res.setStatus(status);
    res.setContentType("text/plain;charset=UTF-8");
    res.getWriter().write(message);
  }
}

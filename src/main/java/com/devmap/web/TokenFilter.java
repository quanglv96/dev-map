package com.devmap.web;

import com.devmap.tray.LoginLinks;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * NFR-08: only loopback clients addressing localhost (blocks DNS rebinding), and only with the run token.
 * The token is never in a URL (D-67): the browser logs in with a one-time ?once= code that DevMap opens itself, swapped for an
 * HttpOnly SameSite=Strict cookie holding the token. CLI clients send it in the header.
 */
@Component
public class TokenFilter extends OncePerRequestFilter {

  public static final String COOKIE = "devmap_token";
  public static final String HEADER = "X-DevMap-Token";
  private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

  private final ServerToken token;
  private final LoginLinks links;

  public TokenFilter(ServerToken token, LoginLinks links) {
    this.token = token;
    this.links = links;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    if (!InetAddress.getByName(req.getRemoteAddr()).isLoopbackAddress() || !LOCAL_HOSTS.contains(req.getServerName())) {
      deny(res, HttpServletResponse.SC_FORBIDDEN, "DevMap chỉ nhận kết nối từ máy này (localhost).");
      return;
    }
    if ("/mcp".equals(req.getRequestURI()) && req.getHeader(McpController.SESSION_HEADER) != null) {
      chain.doFilter(req, res); // agent MCP calls: McpController checks the session token
      return;
    }
    if ("GET".equals(req.getMethod()) && links.consume(req.getParameter("once"))) {
      // a link DevMap opened (start, second `devmap`, tray): only the ticket survives the redirect
      ResponseCookie cookie = ResponseCookie.from(COOKIE, token.value()).httpOnly(true).sameSite("Strict").path("/").build();
      res.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
      String ticket = req.getParameter("ticket");
      res.sendRedirect(req.getRequestURI() + (ticket == null ? "" : "?ticket=" + URLEncoder.encode(ticket, StandardCharsets.UTF_8)));
      return;
    }
    if (token.matches(req.getHeader(HEADER)) || token.matches(cookie(req))) {
      chain.doFilter(req, res);
      return;
    }
    deny(res, HttpServletResponse.SC_UNAUTHORIZED, "DevMap: chạy lại `devmap` (hoặc dùng menu khay) để mở trang đã đăng nhập.");
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

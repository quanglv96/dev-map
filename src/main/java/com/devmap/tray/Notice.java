package com.devmap.tray;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Something that needs the PMO. text is one of DevMap's fixed short lines, never agent output or document content (AC-23). */
public record Notice(NoticeKind kind, String project, String ticket, String text) {

  public String title() {
    return "[" + project + "] " + (ticket == null ? "Đồng bộ" : ticket);
  }

  /** FR-63: the ticket's link, or the project page when the event belongs to no ticket. */
  public String path() {
    return path(project, ticket);
  }

  public static String path(String project, String ticket) {
    return "/" + project + (ticket == null ? "" : "?ticket=" + URLEncoder.encode(ticket, StandardCharsets.UTF_8));
  }
}

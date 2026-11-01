package com.devmap.tray;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The open tickets at one moment. Comparing two snapshots gives the pop-ups (FR-64), so a ticket that merely stays in a waiting state
 * or an agent finishing a step never raises one, and nothing is raised for what was already waiting when DevMap started.
 */
public record TraySnapshot(Map<String, Item> items) {

  /** waiting: DECISION or PUSH while the ticket sits at that PMO gate, else null. Key in the map = project/ticket. */
  public record Item(String project, String ticket, NoticeKind waiting, int questions, boolean failed) {

    /** Counts as "việc chờ bạn": a gate or an open question. A failed session is the error mark instead (FR-59). */
    public boolean waitsForPmo() {
      return waiting != null || questions > 0;
    }

    public String reason() {
      return text(waiting != null ? waiting : NoticeKind.QUESTION);
    }
  }

  public List<Notice> noticesSince(TraySnapshot before) {
    List<Notice> out = new ArrayList<>();
    items.forEach((key, now) -> {
      Item old = before.items.get(key);
      if (now.waiting != null && (old == null || old.waiting != now.waiting)) {
        out.add(notice(now.waiting, now));
      }
      if (now.questions > (old == null ? 0 : old.questions)) {
        out.add(notice(NoticeKind.QUESTION, now));
      }
      if (now.failed && (old == null || !old.failed)) {
        out.add(notice(NoticeKind.FAILURE, now));
      }
    });
    return out;
  }

  private static Notice notice(NoticeKind kind, Item item) {
    return new Notice(kind, item.project, item.ticket, text(kind));
  }

  static String text(NoticeKind kind) {
    return switch (kind) {
      case DECISION -> "Chờ bạn chốt giải pháp";
      case QUESTION -> "Agent hỏi lại nghiệp vụ, chờ bạn trả lời";
      case PUSH -> "SA kiểm chứng xong, chờ bạn xác nhận push";
      case FAILURE -> "Agent lỗi, cần bạn xem";
      case SYNC -> "Đồng bộ cần bạn xử lý";
    };
  }
}

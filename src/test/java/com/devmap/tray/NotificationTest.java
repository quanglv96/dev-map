package com.devmap.tray;

import static org.assertj.core.api.Assertions.assertThat;

import com.devmap.secret.SecretMasker;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** AC-21, AC-22, AC-23: what pops up, how often and with which words. */
class NotificationTest {

  private static final LocalDateTime NOON = LocalDateTime.of(2026, 11, 2, 12, 0);
  private static final NotificationSettings ON = NotificationSettings.defaults();

  private static Notice notice(NoticeKind kind, String ticket) {
    return new Notice(kind, "shop", ticket, "Chờ bạn chốt giải pháp");
  }

  private static NotificationSettings muted(Set<NoticeKind> kinds, Set<String> projects) {
    return new NotificationSettings(kinds, projects, null, null, null, null);
  }

  private static TraySnapshot snapshot(TraySnapshot.Item... items) {
    return new TraySnapshot(java.util.Arrays.stream(items).collect(java.util.stream.Collectors.toMap(
        i -> i.project() + "/" + i.ticket(), i -> i)));
  }

  private static TraySnapshot.Item item(NoticeKind waiting, int questions, boolean failed) {
    return new TraySnapshot.Item("shop", "NYX-1", waiting, questions, failed);
  }

  /** AC-21: the events of FR-64 raise a notice, once. */
  @Test
  void importantChangesRaiseANotice() {
    TraySnapshot idle = snapshot(item(null, 0, false));
    assertThat(snapshot(item(NoticeKind.DECISION, 0, false)).noticesSince(idle)).extracting(Notice::kind)
        .containsExactly(NoticeKind.DECISION);
    assertThat(snapshot(item(NoticeKind.PUSH, 0, false)).noticesSince(idle)).extracting(Notice::kind).containsExactly(NoticeKind.PUSH);
    assertThat(snapshot(item(null, 2, false)).noticesSince(idle)).extracting(Notice::kind).containsExactly(NoticeKind.QUESTION);
    assertThat(snapshot(item(null, 0, true)).noticesSince(idle)).extracting(Notice::kind).containsExactly(NoticeKind.FAILURE);
  }

  /** AC-21: an agent step, a sync or a document edit leaves the snapshot as it was, so nothing pops up. */
  @Test
  void lightEventsAndStatesThatJustStayRaiseNothing() {
    TraySnapshot waiting = snapshot(item(NoticeKind.DECISION, 1, true));
    assertThat(snapshot(item(NoticeKind.DECISION, 1, true)).noticesSince(waiting)).isEmpty();
    assertThat(snapshot(item(NoticeKind.DECISION, 0, false)).noticesSince(waiting)).isEmpty();
    assertThat(new TraySnapshot(Map.of()).noticesSince(waiting)).isEmpty();
  }

  @Test
  void quietHoursCrossMidnightAndHoldForErrorsToo() {
    Notice failure = notice(NoticeKind.FAILURE, "NYX-1");
    assertThat(NotificationPolicy.allows(failure, ON, NOON, false)).isTrue();
    assertThat(NotificationPolicy.allows(failure, ON, NOON.withHour(23), false)).isFalse();
    assertThat(NotificationPolicy.allows(failure, ON, NOON.withHour(6), false)).isFalse();
    assertThat(NotificationPolicy.quiet(ON, LocalTime.of(7, 0))).isFalse();
    assertThat(NotificationPolicy.quiet(ON, LocalTime.of(22, 0))).isTrue();
    NotificationSettings off = new NotificationSettings(null, null, false, null, null, null);
    assertThat(NotificationPolicy.allows(failure, off, NOON.withHour(23), false)).isTrue();
    NotificationSettings daytime = new NotificationSettings(null, null, true, "12:00", "13:00", null);
    assertThat(NotificationPolicy.quiet(daytime, LocalTime.of(12, 30))).isTrue();
    assertThat(NotificationPolicy.quiet(daytime, LocalTime.of(23, 0))).isFalse();
  }

  @Test
  void snoozeEndsByItself() {
    NotificationSettings snoozed = ON.withSnooze(NOON.plusHours(1));
    Notice n = notice(NoticeKind.DECISION, "NYX-1");
    assertThat(NotificationPolicy.allows(n, snoozed, NOON, false)).isFalse();
    assertThat(NotificationPolicy.allows(n, snoozed, NOON.plusHours(1), false)).isTrue();
    assertThat(NotificationPolicy.nextSevenAm(NOON)).isEqualTo(NOON.plusDays(1).withHour(7));
    assertThat(NotificationPolicy.nextSevenAm(NOON.withHour(3))).isEqualTo(NOON.withHour(7));
  }

  @Test
  void kindsAndProjectsTurnOffOneByOne() {
    Notice n = notice(NoticeKind.DECISION, "NYX-1");
    assertThat(NotificationPolicy.allows(n, muted(Set.of(NoticeKind.DECISION), null), NOON, false)).isFalse();
    assertThat(NotificationPolicy.allows(n, muted(Set.of(NoticeKind.PUSH), null), NOON, false)).isTrue();
    assertThat(NotificationPolicy.allows(n, muted(null, Set.of("shop")), NOON, false)).isFalse();
    assertThat(NotificationPolicy.allows(n, muted(null, Set.of("other")), NOON, false)).isTrue();
  }

  /** FR-65: the ticket on screen in a visible tab stays quiet, until that tab leaves it or stops reporting. */
  @Test
  void theTicketOnScreenDoesNotPopUp() {
    Notice n = notice(NoticeKind.DECISION, "NYX-1");
    assertThat(NotificationPolicy.allows(n, ON, NOON, true)).isFalse();
    TabFocus focus = new TabFocus();
    focus.report("tab", "shop", "NYX-1", 0);
    assertThat(focus.viewing("shop", "NYX-1", 1)).isTrue();
    assertThat(focus.viewing("shop", "NYX-2", 1)).isFalse();
    assertThat(focus.viewing("other", "NYX-1", 1)).isFalse();
    assertThat(focus.viewing("shop", "NYX-1", TabFocus.EXPIRES_MILLIS)).isFalse();
    focus.report("tab", "shop", "NYX-1", 0);
    focus.report("tab", "shop", null, 1);
    assertThat(focus.viewing("shop", "NYX-1", 2)).isFalse();
  }

  /** AC-22 */
  @Test
  void threeEventsInTenSecondsBecomeOneNotification() {
    NotificationBatcher b = new NotificationBatcher();
    b.offer(notice(NoticeKind.DECISION, "NYX-1"), 0);
    b.offer(notice(NoticeKind.PUSH, "NYX-2"), 3_000);
    b.offer(notice(NoticeKind.FAILURE, "NYX-3"), 9_000);
    assertThat(b.poll(9_999)).isEmpty();
    List<Notice> batch = b.poll(NotificationBatcher.WINDOW_MILLIS);
    assertThat(batch).hasSize(3);
    assertThat(b.poll(NotificationBatcher.WINDOW_MILLIS + 1)).isEmpty();
    Popup popup = Popup.of(batch, new SecretMasker());
    assertThat(popup.text()).isEqualTo("Có 3 việc chờ bạn");
    assertThat(popup.path()).isEqualTo("/shop");
  }

  /** AC-22 */
  @Test
  void theSameEventOfTheSameTicketWaitsFiveMinutes() {
    NotificationBatcher b = new NotificationBatcher();
    Notice n = notice(NoticeKind.DECISION, "NYX-1");
    assertThat(b.offer(n, 0)).isTrue();
    assertThat(b.offer(n, NotificationBatcher.REPEAT_MILLIS - 1)).isFalse();
    assertThat(b.offer(notice(NoticeKind.PUSH, "NYX-1"), 1)).isTrue();
    assertThat(b.offer(notice(NoticeKind.DECISION, "NYX-2"), 1)).isTrue();
    assertThat(b.offer(n, NotificationBatcher.REPEAT_MILLIS)).isTrue();
  }

  /** FR-63 */
  @Test
  void oneNoticeNamesProjectAndTicketAndOpensIt() {
    Popup popup = Popup.of(List.of(notice(NoticeKind.DECISION, "NYX-231")), new SecretMasker());
    assertThat(popup).isEqualTo(new Popup("[shop] NYX-231", "Chờ bạn chốt giải pháp", "/shop?ticket=NYX-231"));
    Popup two = Popup.of(List.of(notice(NoticeKind.DECISION, "NYX-1"), notice(NoticeKind.QUESTION, "NYX-1")), new SecretMasker());
    assertThat(two.title()).isEqualTo("[shop] NYX-1"); // two events of one ticket are one thing to do
    Popup acrossProjects = Popup.of(List.of(notice(NoticeKind.DECISION, "NYX-1"), new Notice(NoticeKind.PUSH, "pay", "P-1", "x")),
        new SecretMasker());
    assertThat(acrossProjects.path()).isEqualTo("/");
  }

  /** AC-23 */
  @Test
  void aTokenInAnEventNeverReachesTheScreen() {
    String token = "ghp_abcdefghijklmnopqrstuvwxyz0123456789";
    Popup popup = Popup.of(List.of(new Notice(NoticeKind.FAILURE, "shop", token, "lỗi với " + token + "\nchi tiết\nnhiều dòng")),
        new SecretMasker());
    assertThat(popup.title() + popup.text()).doesNotContain(token).contains("MASKED").doesNotContain("\n");
    assertThat(Popup.clean("x".repeat(500), new SecretMasker()).length()).isLessThanOrEqualTo(121);
  }

  /** AC-23: pop-ups are built from fixed lines; every kind stays one short line with no room for content. */
  @Test
  void noticesCarryFixedLinesOnly() {
    for (NoticeKind kind : NoticeKind.values()) {
      assertThat(TraySnapshot.text(kind)).isNotBlank().hasSizeLessThan(60);
    }
    assertThat(snapshot(item(NoticeKind.DECISION, 0, false)).noticesSince(new TraySnapshot(Map.of())))
        .extracting(Notice::text).containsExactly("Chờ bạn chốt giải pháp");
  }
}

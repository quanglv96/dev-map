package com.devmap.room;

import static org.assertj.core.api.Assertions.assertThat;

import com.devmap.config.RoomConfig;
import com.devmap.room.RoomRouter.Assignment;
import com.devmap.room.RoomRouter.Kind;
import com.devmap.room.RoomRouter.Route;
import com.devmap.ticket.TaskStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

/** AC-31 / FR-72 / FR-74: the room's router is local code; mentions first, then keywords, then the ticket status. */
class RoomRouterTest {

  final RoomRouter router = new RoomRouter();
  final RoomConfig cfg = RoomConfig.DEFAULT;

  @Test
  void mentionsWinAndSplitOneMessageIntoTwoJobs() {
    assertThat(only(router.route("@Dev chạy lại test", TaskStatus.IMPLEMENT, cfg))).isEqualTo(new Assignment("DEV", "chạy lại test",
        Kind.ASK));
    Route both = router.route("@SA rà nghiệp vụ, @Dev dò log", TaskStatus.IMPLEMENT, cfg);
    assertThat(both.ask()).isFalse();
    assertThat(both.to()).containsExactly(new Assignment("SA", "rà nghiệp vụ", Kind.ASK), new Assignment("DEV", "dò log", Kind.ASK));
    assertThat(router.route("@all kiểm tra lại giúp", TaskStatus.ANALYSIS, cfg).to()).extracting(Assignment::role)
        .containsExactly("SA", "DEV");
  }

  @Test
  void keywordsPickTheRoleAndVagueTextAsks() {
    assertThat(only(router.route("quy tắc hoàn tiền khi có phí là gì?", TaskStatus.ANALYSIS, cfg)).role()).isEqualTo("SA");
    assertThat(only(router.route("quy tắc hoàn tiền khi có phí là gì?", TaskStatus.IMPLEMENT, cfg)))
        .isEqualTo(new Assignment("SA", "quy tắc hoàn tiền khi có phí là gì?", Kind.ASK));
    assertThat(only(router.route("chạy lại test giúp", TaskStatus.IMPLEMENT, cfg)).role()).isEqualTo("DEV");
    assertThat(only(router.route("xem stack trace này", TaskStatus.ANALYSIS, cfg)).role()).isEqualTo("DEV"); // Dev may only answer
    Route vague = router.route("xem giúp cái này với", TaskStatus.IMPLEMENT, cfg);
    assertThat(vague.ask()).isTrue();
    assertThat(vague.to()).isEmpty();
    assertThat(only(router.route("xem giúp cái này với", TaskStatus.ANALYSIS, cfg)).role()).isEqualTo("SA"); // status decides
  }

  /** §2.5: while implementing, logic changes and bug reports go to SA first. */
  @Test
  void implementationChangesAndBugsGoToSa() {
    assertThat(only(router.route("hoàn tiền tối đa 50%", TaskStatus.IMPLEMENT, cfg)).kind()).isEqualTo(Kind.CHANGE);
    assertThat(only(router.route("dùng lại hàm format tiền có sẵn", TaskStatus.IMPLEMENT, cfg)))
        .isEqualTo(new Assignment("SA", "dùng lại hàm format tiền có sẵn", Kind.CHANGE));
    assertThat(only(router.route("số tiền hoàn ở calculateRefund bị sai", TaskStatus.IMPLEMENT, cfg)).kind()).isEqualTo(Kind.BUG);
    assertThat(only(router.route("hoàn tiền tối đa 50%", TaskStatus.ANALYSIS, cfg)).kind()).isEqualTo(Kind.ASK);
    // the PMO named Dev: technical, straight to Dev
    assertThat(only(router.route("@Dev sửa code log sai format", TaskStatus.IMPLEMENT, cfg)).role()).isEqualTo("DEV");
  }

  @Test
  void keywordsAreConfigurableAndMatchWholeWords() {
    assertThat(RoomRouter.hits("latest build", List.of("test"))).isZero();
    RoomConfig custom = new RoomConfig(null, null, null, null, null, List.of("đối soát"), List.of("deploy"), null, null);
    assertThat(only(router.route("đối soát ra sao", TaskStatus.ANALYSIS, custom)).role()).isEqualTo("SA");
    assertThat(only(router.route("deploy lại staging", TaskStatus.IMPLEMENT, custom)).role()).isEqualTo("DEV");
  }

  private static Assignment only(Route r) {
    assertThat(r.ask()).isFalse();
    assertThat(r.to()).hasSize(1);
    return r.to().get(0);
  }
}

package com.devmap.orchestration;

import com.devmap.ticket.TaskStatus;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * FR-77 as a table (PROMPT_ROOM §3): event → role that reacts → statuses it may run in → context it gets → its task. Nothing else
 * in the orchestration decides who runs; an event whose condition no longer holds is skipped with a note.
 */
public final class TriggerRules {

  /** What a triggered session receives as "the change" (FR-78). */
  public enum Context {
    /** The proposal (summary, location, proposal, refs). */
    PROPOSAL,
    /** Unified diff of one document between two versions. */
    DOC_DIFF,
    /** Text DevMap wrote (verify list, PMO's words, commits). */
    TEXT
  }

  /** Every event kind with its row of the table. */
  public enum Kind {
    DEV_PROPOSAL("SA", Context.PROPOSAL, EnumSet.of(TaskStatus.ANALYSIS, TaskStatus.WAIT_APPROVAL, TaskStatus.READY,
        TaskStatus.IMPLEMENT, TaskStatus.VERIFY), "SA đang đánh giá đề xuất của Dev",
        "Dev vừa ghi đề xuất ở trên. Đánh giá: đúng thì cập nhật đúng phần liên quan của plan.md / analysis.md (AC) bằng "
            + "write_ticket_doc, sai thì giữ nguyên. Phân loại ảnh hưởng và gọi resolve_proposal (APPLIED / REJECTED kèm lý do "
            + "ngắn). Thay đổi chạm AC, business rule hay phạm vi thì affects tương ứng: DevMap sẽ dừng Dev và đưa PMO chốt lại."),
    DOC_CHANGED("DEV", Context.DOC_DIFF, EnumSet.of(TaskStatus.IMPLEMENT), "Dev đang điều chỉnh code theo tài liệu SA vừa đổi",
        "SA vừa đổi tài liệu (diff ở trên). Chỉ đọc phần bị đổi, điều chỉnh code và test đang làm cho khớp, commit, chạy gate; "
            + "xong thì set_status verify. Không đồng ý với thay đổi thì write_proposal với reply_to là đề xuất đang trao đổi."),
    PROPOSAL_RESOLVED("DEV", Context.PROPOSAL, EnumSet.of(TaskStatus.IMPLEMENT), "Dev đang đọc phản hồi của SA về đề xuất",
        "SA đã trả lời đề xuất của bạn (trạng thái và lý do ở trên). Làm tiếp theo plan.md hiện tại. Vẫn không đồng ý thì "
            + "write_proposal với reply_to (DevMap đưa PMO quyết sau 2 vòng)."),
    VERIFY_FAILED("DEV", Context.TEXT, EnumSet.of(TaskStatus.IMPLEMENT), "Dev đang sửa theo kết quả kiểm chứng",
        "SA kiểm chứng KHÔNG ĐẠT (danh sách lỗi ở trên, từ verify.md). Sửa đúng các điểm đó, không sửa thêm; commit, chạy gate, "
            + "rồi set_status verify."),
    PMO_CODE_CHANGE("SA", Context.TEXT, EnumSet.of(TaskStatus.IMPLEMENT, TaskStatus.VERIFY), "SA đang ghi nhận code PMO tự sửa",
        "PMO tự sửa code trong worktree (commit ở trên; DevMap đã ghi changes.md). Xem git_diff các commit đó, kiểm AC trong "
            + "analysis.md còn đúng không, ghi nhận vào summary.md. AC hay plan cần đổi thì cập nhật và ghi rõ vì sao."),
    BUG_AFTER_DEPLOY("SA", Context.TEXT, EnumSet.of(TaskStatus.ANALYSIS), "SA đang cập nhật logic đúng theo bug PMO báo",
        "PMO báo bug sau deploy (ở trên, mục bugs/ tương ứng). Tìm nguyên nhân, cập nhật analysis.md theo logic đúng và thêm case "
            + "mang mã mục bugs/ vào AC trước khi giao Dev; ghi plan.md phần sửa, rồi set_status wait_approval."),
    PMO_CHANGE("SA", Context.PROPOSAL, EnumSet.of(TaskStatus.IMPLEMENT, TaskStatus.VERIFY, TaskStatus.READY),
        "SA đang phân loại thay đổi của PMO",
        "PMO đổi yêu cầu khi đang triển khai (đề xuất ở trên, tác giả PMO). Phân loại: A1 làm rõ kỹ thuật (không đổi AC) thì chỉ bổ "
            + "sung plan.md; A2 đổi nghiệp vụ/AC/phạm vi thì cập nhật analysis.md. Câu hỏi thuần thì chỉ trả lời. Gọi "
            + "resolve_proposal với affects đúng mức."),
    PMO_BUG("SA", Context.PROPOSAL, EnumSet.of(TaskStatus.IMPLEMENT, TaskStatus.VERIFY), "SA đang tìm nguyên nhân bug PMO báo",
        "PMO báo bug trong phần đang làm (B1, đề xuất BUG_FOUND ở trên). Tìm nguyên nhân bằng get_snippet, ghi vào plan.md cách sửa "
            + "và thêm ca kiểm thử (TC) vào analysis.md mục \"Ca kiểm thử\". Chỉ là case bị sót của AC hiện có thì không sửa AC; AC "
            + "sai thì sửa AC (DevMap sẽ đưa PMO chốt lại). resolve_proposal."),
    PMO_BUG_DEV("DEV", Context.PROPOSAL, EnumSet.of(TaskStatus.IMPLEMENT), "Dev đang sửa bug PMO báo",
        "PMO báo bug ở code bạn vừa viết (B1, vị trí ở trên). Viết test tái hiện, sửa, commit, chạy gate. Thấy AC sai thì "
            + "write_proposal thay vì tự đổi phạm vi."),
    REANALYZE("SA", Context.TEXT, EnumSet.of(TaskStatus.ANALYSIS, TaskStatus.IMPLEMENT),
        "SA đang cập nhật plan.md theo thay đổi (chuẩn bị chốt lại)",
        "Thay đổi giữa chừng chạm AC/business rule/phạm vi (ở trên); Dev đã dừng, code đang làm đã commit WIP. (1) Cập nhật "
            + "analysis.md thành bản mới nếu chưa. (2) So code đã viết (git_diff từng repo) với yêu cầu mới, ghi vào plan.md mục "
            + "\"## Ảnh hưởng tới code đang làm\" bảng `file | giữ nguyên / phải sửa / phải bỏ | vì sao`. "
            + "(3) Thêm phần việc mới vào plan."),
    REAPPROVED("DEV", Context.DOC_DIFF, EnumSet.of(TaskStatus.IMPLEMENT), "Dev đang làm phần thay đổi sau khi PMO chốt lại",
        "PMO đã chốt lại. Bắt đầu từ commit WIP trên branch; chỉ làm phần thay đổi (diff plan.md ở trên) và danh sách phải "
            + "sửa/phải bỏ ở mục \"Ảnh hưởng tới code đang làm\"; commit, gate, set_status verify."),
    UNDONE("DEV", Context.DOC_DIFF, EnumSet.of(TaskStatus.IMPLEMENT), "Dev đang đọc lại tài liệu sau khi PMO hoàn tác",
        "PMO hoàn tác một cập nhật tự động (diff ở trên). Bỏ phần code đã làm theo bản bị hoàn tác nếu có, làm theo tài liệu hiện tại.");

    public final String role;
    public final Context context;
    public final Set<TaskStatus> when;
    public final String label;
    public final String task;

    Kind(String role, Context context, Set<TaskStatus> when, String label, String task) {
      this.role = role;
      this.context = context;
      this.when = when;
      this.label = label;
      this.task = task;
    }
  }

  private TriggerRules() {
  }

  /** The table as text, for `devmap` docs and CHANGE-ROOM.md. */
  public static String table() {
    StringBuilder sb = new StringBuilder("| Sự kiện | Vai nhận | Điều kiện (trạng thái) | Ngữ cảnh |\n| --- | --- | --- | --- |\n");
    Arrays.stream(Kind.values()).forEach(k -> sb.append("| ").append(k).append(" | ").append(k.role).append(" | ")
        .append(k.when.stream().map(TaskStatus::code).toList()).append(" | ").append(k.context).append(" |\n"));
    return sb.toString();
  }
}

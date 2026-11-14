# CHANGE-ROOM – Room SA + Dev và đồng bộ tự động giữa các agent

Tóm tắt phần bổ sung theo `docs/PROMPT_ROOM.md`, để PMO cập nhật vào `docs/SRS.md`. Quyết định kỹ thuật chi tiết: `docs/DECISIONS.md` D-74 → D-82. Hướng dẫn dùng: `docs/USER_GUIDE.md` mục 11. Kiểm thử: `docs/TEST_PLAN.md` mục "Room và đồng bộ giữa agent".

## 1. Thay đổi so với SRS hiện tại

- **Luồng một ticket**: 8 trạng thái mới thay cho 8 trạng thái cũ (mục 4). Bỏ trạng thái "Mở lại", thay bằng hành động "Báo bug / Bổ sung logic" (`done → analysis`, giữ mã, `reopened` +1).
- **FR-08 / D-68** "plan.md đã chốt không sửa": sau khi chốt, plan.md/analysis.md chỉ được SA sửa trong **phiên tự kích hoạt** (đề xuất của Dev, thay đổi của PMO); thay đổi chạm AC, business rule, phạm vi bị luật cứng đưa về PMO chốt lại.
- **Chatbox** (FR-37 → FR-40) thành **room** 3 thành viên. Nguyên tắc cũ giữ nguyên: mỗi câu một phiên, ngữ cảnh từ tài liệu, không đọc lại chat, che bí mật.
- **Agent Dev trước khi chốt**: được trả lời và ghi đề xuất (cấu hình), không được sửa code (kiểm ở server).
- **File mới trong folder ticket**: `proposals/<thời điểm>-<tác giả>.json`, `events.json`.
- **task.md**: trạng thái theo mã mới; thêm mục `flags`.
- **config.yml của dự án**: thêm mục `room` (mục 6).
- **API mới**: `POST /api/projects/{p}/tasks/{id}/room/send`, `GET …/room/proposals`, `POST …/room/proposals/{pid}`, `GET …/room/events`, `POST …/room/pause`, `POST …/room/undo`, `POST …/room/priority`. `GET /agents` thêm `doing` (dòng hoạt động); `GET /usage` thêm `sync`, `syncSessions`.

## 2. Yêu cầu chức năng FR-71 → FR-96

| Mã | Yêu cầu | Đã làm |
| --- | --- | --- |
| FR-71 | Room 3 thành viên, thanh trạng thái | Tab "Room": PMO bên phải, SA (xanh) / Dev (tím) bên trái có avatar; thẻ DevMap ở giữa. Thanh trên: avatar (vòng xanh khi đang chạy), trạng thái ticket, agent đang chạy, nút tạm dừng đồng bộ. |
| FR-72 | Định tuyến cục bộ | `RoomRouter`: `@SA`/`@Dev`/`@all` → từ khoá (cấu hình) → trạng thái. Không chắc: dòng hỏi "Gửi SA / Gửi Dev". Không gọi LLM. |
| FR-73 | Quyền theo trạng thái | `room.dev_can_propose_before_approval` (mặc định `true`). Trước `implement`: Dev chỉ trả lời + `write_proposal`; sửa file, commit, gate bị server từ chối. |
| FR-74 | `@all` / hai việc một câu | Hai phiên độc lập (SA, Dev) chạy song song trong giới hạn `devmap.agent.max-concurrent`, kết quả về cùng room. |
| FR-75 | Đề xuất có cấu trúc | Schema mục 3; công cụ `write_proposal`, `resolve_proposal`; đã che bí mật. |
| FR-76 | Thẻ đề xuất | Tác giả, loại, mức ảnh hưởng, trạng thái; mở xem vị trí, đề xuất, phản hồi, hai quan điểm; PMO Đồng ý / Không đồng ý khi `NEEDS_PMO`. |
| FR-77 | Sự kiện và phản ứng tự động | Bảng luật mục 5. |
| FR-78 | Ngữ cảnh chỉ phần thay đổi | Phiên tự kích hoạt nhận spec.json, summary.md, đề xuất hoặc diff, đoạn tài liệu theo `refs`, chữ ký theo `location` (1 tầng). Không có tài liệu đầy đủ, không có chat. System prompt giữ nguyên (prompt caching). |
| FR-79 | Phân loại mức ảnh hưởng + luật cứng | `ImpactRules`: đổi dòng AC, business rule, phạm vi trong analysis.md, hoặc thêm repo vào ticket/plan.md → luôn cần PMO, bất kể agent đánh giá. `TECHNICAL_ONLY` → Dev làm tiếp, PMO chỉ được báo. |
| FR-80 | Version + thẻ + Hoàn tác | Mỗi cập nhật tự động là version mới; thẻ "SA đã cập nhật plan.md (v4) theo đề xuất của Dev" có "Xem diff" và "Hoàn tác". |
| FR-81 | Chống vòng lặp | Tối đa `room.max_rounds` (2) vòng mỗi mạch đề xuất; quá thì `NEEDS_PMO` kèm hai quan điểm. Trần `room.max_auto_sessions_per_hour` (10) phiên tự kích hoạt/giờ/ticket, vượt thì tạm dừng và báo PMO. |
| FR-82 | Hàng đợi theo ticket, bền | `events.json`; tuần tự trong ticket, song song giữa ticket; khởi động lại chạy tiếp sự kiện đang dở. |
| FR-83 | Tạm dừng đồng bộ | Nút trong room; sự kiện vẫn ghi, chạy khi bật lại. Cờ `sync_paused`. |
| FR-84 | summary.md sau mỗi lần đồng bộ | DevMap thêm dòng vào mục `## Đồng bộ tự động` (10 dòng gần nhất). |
| FR-85 | 8 trạng thái + migration | Mục 4; migration tự chạy lúc khởi động, mã cũ vẫn đọc được. |
| FR-86 | Bảng chuyển trạng thái | Mục 4; ngoài bảng bị server từ chối. |
| FR-87 | Nhãn lần chốt | "chốt lần đầu" / "chốt lại · v<n>" (n = version plan.md) ở cột phải. |
| FR-88 | Cờ | `needs_pmo`, `open_proposals`, `sync_paused`, `reopened`, `mid_change`, `file_overlap` (mục 4). |
| FR-89 | Danh sách task 3 phần | Mã + tên + nhãn trạng thái; dòng hoạt động; biểu tượng cờ, `needs_pmo` nổi bật nhất. |
| FR-90 | A1 làm rõ kỹ thuật | Giữ `implement`, SA bổ sung plan.md, Dev nhận phần bổ sung. |
| FR-91 | A2 đổi nghiệp vụ/AC/phạm vi | Dừng Dev an toàn → `mid_change` → `analysis` → SA ghi "Ảnh hưởng tới code đang làm" (giữ / sửa / bỏ) → `wait_approval` "chốt lại" → PMO chốt → `implement`, Dev nhận phần thay đổi. |
| FR-92 | B1–B4 | Mục 7. |
| FR-93 | Tự đoán case | `BugCaseGuesser`, cục bộ; không chắc thì 4 nút. |
| FR-94 | Dừng Dev an toàn | Từ chối mọi công cụ sau thao tác hiện tại; quá 60 giây huỷ phiên; commit WIP cục bộ `WIP(<mã>): tạm dừng do <lý do>`, không push; Dev làm tiếp từ commit này. |
| FR-95 | Version + history.md | `Thay đổi giữa chừng · nguồn · case · plan.md vA → vB · mức`. |
| FR-96 | Bỏ chốt lại cho thay đổi của PMO | `room.skip_reapproval_for_pmo_changes` (mặc định `false`); thêm repo/service vẫn phải chốt lại. |

## 3. Schema đề xuất (`proposals/<id>.json`)

```json
{
  "id": "20261114-103012-123-dev",
  "origin": "20261114-103012-123-dev",
  "round": 1,
  "author": "DEV",
  "type": "BUG_FOUND",
  "summary": "PaymentClient.refund nhận cả số âm",
  "location": { "repo": "order-service", "file": "src/main/java/.../PaymentClient.java", "signature": "refund(long)" },
  "proposal": "Chặn số âm trước khi gọi",
  "affects": "TECHNICAL_ONLY",
  "agent_affects": "TECHNICAL_ONLY",
  "refs": ["plan.md#Bước 2"],
  "status": "APPLIED",
  "note": "Thêm bước chặn số âm",
  "views": [],
  "created": "2026-11-14 10:30",
  "updated": "2026-11-14 10:31"
}
```

`author`: SA | DEV | PMO. `type`: BUG_FOUND | SOLUTION_CHANGE | SCOPE_CHANGE | QUESTION. `affects`: TECHNICAL_ONLY | AC | BUSINESS_RULE | SCOPE (sau luật cứng); `agent_affects`: agent tự đánh giá. `status`: OPEN | APPLIED | REJECTED | NEEDS_PMO. `origin`/`round`: mạch trao đổi và số lần qua lại (chống vòng lặp). `views`: hai quan điểm khi dừng sau 2 vòng.

## 4. Mô hình trạng thái mới

| Lớp | Ý nghĩa | Ai đổi | Lưu ở |
| --- | --- | --- | --- |
| `status` | Giai đoạn | Luồng cố định và PMO | `task.md` |
| `activity` | Ai đang làm gì | Agent, tự động | Bộ nhớ + SSE |
| `flags` | Điều cần chú ý | Hệ thống | `task.md` |

Trạng thái: `new` (Mới), `analysis` (Phân tích), `wait_approval` (Chờ PMO chốt), `ready` (Sẵn sàng), `implement` (Triển khai), `verify` (Kiểm chứng), `wait_confirm` (Chờ PMO xác nhận push), `done`. Ngoài luồng: `cancelled` (D-66).

| Từ → Đến | Kích hoạt |
| --- | --- |
| `new` → `analysis` | Tự động khi SA nhận ticket |
| `analysis` → `wait_approval` | SA gửi giải pháp (hoặc DevMap sau phiên chuẩn bị chốt lại) |
| `wait_approval` → `ready` | **PMO chốt** lần đầu |
| `wait_approval` → `implement` | **PMO chốt lại** khi đã từng triển khai |
| `wait_approval` → `analysis` | **PMO yêu cầu phân tích lại** |
| `ready` → `implement` | Đến lượt / PMO cho chạy; pull nhánh nền trước |
| `implement` → `verify` | Dev xong, gate đạt |
| `verify` → `implement` | SA thấy lệch phạm vi / hồi quy |
| `implement`/`verify` → `analysis` | DevMap (luật cứng) hoặc PMO; dừng Dev an toàn trước |
| `verify` → `wait_confirm` | SA xác nhận đạt |
| `wait_confirm` → `implement` | **PMO yêu cầu sửa** |
| `wait_confirm` → `done` | **PMO xác nhận push** |
| `done` → `analysis` | **PMO báo bug / bổ sung logic** |

Migration: `sa_analysis`→`analysis`, `awaiting_approval`→`wait_approval`, `in_dev`→`implement`, `sa_verify`→`verify`, `awaiting_push`→`wait_confirm`, "Mở lại" (`reopened`)→`analysis` + cờ `reopened` ≥ 1.

Cờ: `needs_pmo` (lý do), `open_proposals`, `sync_paused`, `reopened` (số lần), `mid_change`, `file_overlap` (mã task kia), cùng `approved_plan` (version plan.md lúc chốt, dùng để đưa Dev đúng phần diff khi chốt lại).

## 5. Bảng luật kích hoạt (`orchestration.TriggerRules`)

| Sự kiện | Phát sinh khi | Vai nhận | Trạng thái được chạy | Ngữ cảnh |
| --- | --- | --- | --- | --- |
| `DEV_PROPOSAL` | Dev ghi BUG_FOUND / SOLUTION_CHANGE / SCOPE_CHANGE | SA | analysis, wait_approval, ready, implement, verify | Đề xuất + refs + location |
| `DOC_CHANGED` | SA đổi plan.md/analysis.md, mức kỹ thuật | Dev | implement | Diff tài liệu |
| `PROPOSAL_RESOLVED` | SA trả lời đề xuất của Dev mà không đổi tài liệu | Dev | implement | Đề xuất + phản hồi |
| `VERIFY_FAILED` | SA kiểm chứng KHÔNG ĐẠT (`verify → implement`) | Dev | implement | verify.md |
| `PMO_CODE_CHANGE` | Có commit trong worktree không do Dev | SA | implement, verify | Danh sách commit, file |
| `BUG_AFTER_DEPLOY` | PMO "Báo bug / Bổ sung logic" trên ticket Done | SA | analysis | Mục bugs/ |
| `PMO_CHANGE` | PMO đổi logic khi đang triển khai | SA | ready, implement, verify | Đề xuất (tác giả PMO) |
| `PMO_BUG` | PMO báo bug B1 | SA | implement, verify | Đề xuất BUG_FOUND |
| `PMO_BUG_DEV` | B1, nguyên nhân rõ | Dev | implement | Đề xuất BUG_FOUND |
| `REANALYZE` | Thay đổi A2/B2 (luật cứng hoặc PMO chọn) | SA | analysis, implement (bỏ chốt lại) | Lý do + diff + commit WIP |
| `REAPPROVED` | PMO chốt lại, hoặc bỏ chốt lại xong | Dev | implement | Diff plan.md từ lần chốt trước + mục ảnh hưởng |
| `UNDONE` | PMO hoàn tác cập nhật tự động | Dev | implement | Diff hoàn tác |

## 6. Cấu hình mới (`config.yml` → `room`)

```yaml
room:
  dev_can_propose_before_approval: true    # FR-73
  skip_reapproval_for_pmo_changes: false   # FR-96
  max_rounds: 2                            # FR-81
  max_auto_sessions_per_hour: 10           # FR-81
  safe_stop_seconds: 60                    # FR-94
  sa_keywords: [nghiệp vụ, quy tắc, nguyên nhân, …]   # FR-72
  dev_keywords: [sửa code, chạy test, log, …]
  bug_keywords: [bug, lỗi, sai, …]
  change_keywords: [đổi, thêm, bỏ, tối đa, dùng lại, …]
```

## 7. Thay đổi giữa chừng khi đang `implement`

| Case | Nhận biết | Trạng thái | Xử lý |
| --- | --- | --- | --- |
| A1 làm rõ kỹ thuật | SA đánh giá + luật cứng không thấy đổi AC/rule/phạm vi | Giữ `implement` | SA bổ sung plan.md (version mới), Dev nhận diff; thẻ "SA đã bổ sung plan.md theo yêu cầu của PMO, Dev tiếp tục". |
| A2 đổi nghiệp vụ/AC/phạm vi | Luật cứng hoặc SA | `implement → analysis → wait_approval → implement` | Dừng Dev an toàn, commit WIP, `mid_change`; SA ghi "Ảnh hưởng tới code đang làm"; "chốt lại · v<n>"; chốt → Dev chỉ nhận phần thay đổi. |
| B1 bug phần đang viết | Tên file/method khớp diff worktree | Giữ `implement` | Đề xuất BUG_FOUND tác giả PMO; rõ nguyên nhân → Dev sửa; SA thêm ca kiểm thử (AC sai → A2). |
| B2 code cũ, sửa luôn | "sửa luôn / trong task này" hoặc PMO chọn | Như A2 | SA thêm phần sửa bug vào plan. |
| B3 thuộc task Done | Chỉ khớp changes.md của task Done | Không đổi | Mở lại task đó ("Báo bug"); chạm cùng file → `file_overlap` cả hai, PMO chọn task làm trước, task kia dừng an toàn. |
| B4 không thuộc task nào | Không khớp gì | Không đổi | Tạo task BUG mới. |

Không chắc thì room hỏi một dòng: "Bug trong phần đang làm" / "Sửa luôn trong task này" / "Thuộc task khác" / "Tạo task mới". Việc đoán chạy cục bộ, không gọi LLM.

## 8. Nghiệm thu AC-31 → AC-50

| AC | Kiểm thử |
| --- | --- |
| AC-31 | `RoomRouterTest`, `RoomFlowTest.vagueMessageAsksWithoutAnyLlmCall` |
| AC-32 | `AgentServiceTest.toolsNeverServeSecretsOrWholeFiles` (phần FR-73) |
| AC-33 | `RoomFlowTest.devTechnicalFindingUpdatesPlanAndDevContinues` |
| AC-34 | `RoomFlowTest.acChangeFromDevProposalNeedsThePmoWhateverTheAgentSays`, `ImpactRulesTest` |
| AC-35 | `RoomFlowTest.devTechnicalFindingUpdatesPlanAndDevContinues`, `caseA1KeepsImplementing` (đầu vào Dev chỉ có diff) |
| AC-36 | `RoomFlowTest.loopStopsAfterTwoRoundsAndHourlyCapPauses` |
| AC-37 | `RoomFlowTest.eventsRunInOrderAndSurviveARestart` |
| AC-38 | `RoomFlowTest.undoRestoresThePreviousVersionAndTellsDev` |
| AC-39 | `RoomFlowTest.twoJobsInOneMessageRunInParallel` |
| AC-40 | `TaskServiceTest.everyPairOutsideTheTableIsRefused`, `RoomFlowTest.loopStopsAfterTwoRoundsAndHourlyCapPauses` |
| AC-41 | `TaskServiceTest.oldStatusesMigrate` |
| AC-42 | `RoomFlowTest.caseA1KeepsImplementing` |
| AC-43 | `RoomFlowTest.caseA2StopsDevCommitsWipAndReapproves` |
| AC-44 | `RoomFlowTest.caseB1BugInTheWorkInProgress` |
| AC-45 | `RoomFlowTest.caseB2FixOldBugHereGoesLikeA2` |
| AC-46 | `RoomFlowTest.caseB3ReopensTheOtherTicketAndFlagsOverlap` |
| AC-47 | `RoomFlowTest.caseB4CreatesANewBugTicket` |
| AC-48 | `RoomFlowTest.bugCasesAreGuessedLocally` |
| AC-49 | `RoomFlowTest.devThatDoesNotStopIsCancelledAndItsWorkCommitted` |
| AC-50 | `RoomFlowTest.skipReapprovalUnlessANewRepoIsAdded` |

Một lượt chạy với agent thật (Claude Code) trên `samples/shop`: kịch bản K-12 trong TEST_PLAN, chưa chạy.

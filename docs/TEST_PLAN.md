# TEST_PLAN – DevMap

Chạy toàn bộ: `./mvnw verify` (JUnit 5 + Checkstyle + ESLint). Dự án mẫu: `samples/shop` (xem README trong đó).

## Bản đồ AC → phase → kiểm thử

| AC | Phase | Trạng thái | Kiểm thử |
| --- | --- | --- | --- |
| AC-01 Khởi tạo | 1, 3 | Đạt (phần tự động); pull do Dev ở P4 gọi cùng cơ chế | `ProjectServiceTest.createsDataFolderWithReposAndServices`, `GraphServiceTest.noScanUntilPmoAsks`, `GraphServiceTest.incrementalUpdateParsesOnlyTheGitDiff`, `WebTest`, `CliTest`, K-01, K-03 |
| AC-02 FEAT trọn luồng | 2, 4, 5 | Một phần: phần Dev bằng tay xong (pull nhánh nền, commit 2 repo cùng mã, gate, xác nhận push); agent SA/Dev ở P5 | `TicketGitServiceTest.ticketFromWorktreeToConfirmedPush`, K-04 |
| AC-03 Mở lại ticket | 2, 5 | Một phần: mở lại bằng tay xong; SA cập nhật analysis (FR-11) ở P5 | `TaskServiceTest.reopenKeepsCode`, K-02 |
| AC-04 MAINT | 2, 4, 5 | Một phần: chốt + thứ tự hàng đợi xong; chạy theo thứ tự ở P5 | `TaskServiceTest.flowGatesAndQueue` |
| AC-05 Push | 4 | Đạt (phần tự động): chỉ PMO push, chỉ commit đã qua gate, không force-push, không push branch bảo vệ (nhánh nền luôn bảo vệ) | `TicketGitServiceTest` (3 test), `RuleServiceTest.protectedBranchesAlwaysIncludeBaseBranches`, `TaskServiceTest.flowGatesAndQueue`, K-04 |
| AC-06 Bí mật | 2, 5 | Một phần: tài liệu không chứa bí mật; chặn agent đọc `.env` ở P5 | `SecretMaskerTest`, `TaskServiceTest.createsTicketWithMaskedSpec` |
| AC-07 Token | 5 | Chưa | |
| AC-08 Hai dự án song song | 5, 6 | Chưa | |
| AC-09 Quét toàn bộ | 3, 5 | Một phần: mặc định tắt, PMO bấm mới quét, có cảnh báo trước; bật theo câu hỏi ở P5 | `GraphServiceTest.noScanUntilPmoAsks`, K-03 |
| AC-10 Ngữ cảnh gọn và chất lượng | 3, 4, 5 | Một phần: chỉ mục chỉ có chữ ký, lân cận ≤ 3 tầng; Controller→Repository làm gate không đạt và chặn chuyển SA kiểm chứng; chỉ sang SA khi phân tích tĩnh + unit test đạt; ngữ cảnh agent và prompt caching ở P5 | `GraphServiceTest.*`, `RuleServiceTest.layerCheckFindsControllerCallingRepository`, `RuleServiceTest.qualityGateRunsConfiguredCommands`, `TicketGitServiceTest.ticketFromWorktreeToConfirmedPush` |
| AC-11 Kiểm chứng phạm vi | 5 | Chưa | |
| AC-12 Tối ưu token | 2, 5 | Một phần: spec.json gọn, đã che, không gọi LLM; nhật ký LLM và xoá ngữ cảnh ở P5 | `TaskServiceTest.createsTicketWithMaskedSpec`, `RequestCleanerTest`, K-02 |

## Test tự động Phase 1

| Test | Kiểm |
| --- | --- |
| `ProjectServiceTest.createsDataFolderWithReposAndServices` | folder dữ liệu đủ cấu trúc SRS; 3 repo với nhánh nền `test`/`main`; mỗi repo phát hiện đúng service; `project.md` có danh sách; `graph/` rỗng (không quét) |
| `ProjectServiceTest.rejectsMissingBaseBranchAndBadNames` | nhánh nền không tồn tại, tên sai, tên dành riêng, thư mục không phải git → lỗi, không tạo folder |
| `ProjectServiceTest.removeKeepsDataAndCopiedFolderCanBeRegisteredAgain` | bỏ đăng ký giữ dữ liệu; đăng ký lại folder đã có |
| `ProjectServiceTest.updateValidatesAndKeepsPmoTextInProjectMd` | sửa cấu hình (FR-04), kiểm hợp lệ, giữ phần chữ của PMO |
| `ProjectServiceTest.gitOptionMakesDataFolderARepo` | `--git` |
| `ServiceDetectorTest` | FR-03: repo một service, monorepo/parent pom, bỏ `node_modules`, `src`, thư mục ẩn |
| `WebTest.rejectsRequestsWithoutToken` | NFR-08: thiếu/sai token → 401, kể cả trang HTML |
| `WebTest.tokenLinkSetsStrictHttpOnlyCookie` | link có token → cookie HttpOnly SameSite=Strict; `/shop` trả trang dự án |
| `WebTest.rejectsNonLocalClientsEvenWithToken` | NFR-08: IP ngoài máy, Host lạ → 403 |
| `WebTest.projectLifecycleOverApi` | API tạo/xem/sửa/bỏ đăng ký dự án, phát hiện service, lỗi 400/404 |
| `CliTest.initListDoctorRemove` | FR-42: `init`, `workspace add/list/remove`, `doctor` |

## Kịch bản thủ công Phase 1

**K-01 (AC-01, phần khởi tạo)**

1. `sh samples/shop/init-repos.sh`
2. `java -jar target/devmap.jar init shop --data <folder> --repo samples/shop/repos/order-service@test --repo samples/shop/repos/payment-service@main --repo samples/shop/repos/notification-service@main`
3. `java -jar target/devmap.jar serve`, mở link có token.
4. Trang chủ có dự án `shop`, 3 repo, 3 service. Bấm vào → `localhost:7070/shop`: tab trình duyệt có tên và màu dự án, cột giữa liệt kê 3 service theo repo, cột phải có folder dữ liệu và nhánh nền.
5. "Cấu hình dự án": đổi nhánh nền payment-service thành `nope` → báo lỗi, không lưu; đổi lại `main`, thêm môi trường `staging` → "Đã lưu"; `config.yml` cập nhật.
6. Trên trang chủ thêm dự án thứ hai qua form → mở được ở URL riêng.
7. Kiểm `<folder>/shop/graph/` vẫn trống: không có lần quét nào tự chạy.
8. Mở `http://localhost:7070/` trong cửa sổ ẩn danh (không cookie) → 401.

## Test tự động Phase 2

| Test | Kiểm |
| --- | --- |
| `SecretMaskerTest.masksEverySecretInSampleRepos` | NFR-02 (phía tài liệu): không giá trị giả nào trong `samples/shop` (kể cả `.env`) còn sót sau khi che |
| `SecretMaskerTest.masksCommonFormats` / `leavesCodeAndAllowedValuesAlone` / `projectRulesAddPatternsAndAllowValues` | FR-35/36: các dạng bí mật; không che code, `${placeholder}`, 127.0.0.1, SHA commit; mẫu và allowlist theo dự án |
| `RequestCleanerTest` | FR-44: bỏ câu xã giao, tách ràng buộc, tìm module đích, slug tiếng Việt |
| `TaskServiceTest.createsTicketWithMaskedSpec` | AC-12 (phần cục bộ), FR-05/06/36/44: mã PMO nhập, branch chung, spec.json gọn và đã che, không file nào chứa token/IP |
| `TaskServiceTest.ticketCodes` | FR-05: mã tự sinh theo loại, trùng mã, regex của dự án, chế độ `auto` |
| `TaskServiceTest.flowGatesAndQueue` | FR-07/08, NFR-03: chuyển trạng thái hợp lệ, 2 cổng chỉ PMO, cần plan.md mới chốt, hàng đợi đổi thứ tự, tổng quan |
| `TaskServiceTest.versionsAndDiff` | FR-09: version + diff, che khi ghi, chặn tên tài liệu lạ (path traversal) |
| `TaskServiceTest.reopenKeepsCode` | FR-10: mở lại giữ mã, ghi `bugs/`, spec.json REOPEN_BUG/ADD_LOGIC |
| `WebTest.projectLifecycleOverApi` (phần ticket) | API ticket, diff, overview, SSE |
| `CliTest` (phần task) | FR-42: `task new/list/show` |

## Kịch bản thủ công Phase 2

**K-02 (FEAT bằng tay, AC-12 phần cục bộ)**

1. Ở `localhost:7070/shop` bấm "+ Ticket", loại FEAT, mã `NYX-231`, yêu cầu:
   `Chào em, nhờ em thêm hoàn tiền một phần cho order-service nhé. Token ghp_abcdefghijklmnopqrstuvwxyz0123456789. Không được đổi API cũ. Cảm ơn!`
2. Thấy cảnh báo "Đã che 1 giá trị". Tài liệu `spec.json`: không có "Chào/nhé/Cảm ơn", token là `[MASKED_TOKEN]`, `target_modules` = order-service, ràng buộc tách riêng.
3. Bấm "Bắt đầu phân tích", "Gửi PMO chốt". Sửa analysis.md hai lần → chọn v1 ↔ v2, "So sánh" thấy diff.
4. "Chốt giải pháp" khi chưa có plan.md → báo lỗi. Viết plan.md rồi chốt → ticket vào Hàng đợi.
5. Mở thêm một tab `localhost:7070/` → "Việc chờ PMO"/"Ticket đang chạy" tự cập nhật khi đổi trạng thái ở tab kia.
6. Đi tiếp tới Done; "Báo bug" → ticket về "SA phân tích", cùng mã, có `bugs/BUG-01.md`, số lần mở lại = 1.
7. CLI (tiếng Việt qua stdin): `echo "..." | java -jar target/devmap.jar task new shop --type BUG`; `task list shop`; `task show shop BUG-001`.

## Test tự động Phase 3

| Test | Kiểm |
| --- | --- |
| `ExtractorTest.javaSpringServiceAndClients` | FR-19/20: Spring endpoint, lời gọi qua field inject, publish/consume Kafka, entity → bảng, repository, RestClient |
| `ExtractorTest.javaFeignClientIsAnOutgoingCall` | Feign client là lời gọi ra, không phải endpoint |
| `ExtractorTest.configComposeAndOpenApi` | FR-20: URL trong application.yml, docker-compose `depends_on`/environment, OpenAPI (swagger-parser), `.proto` |
| `ExtractorTest.typescriptExpressAndAxios` / `pythonFastApiAndGoNetHttp` | tree-sitter: chữ ký, route, lời gọi HTTP, lời gọi theo tên |
| `ExtractorTest.neverSelectsSecretsTestsOrBuildOutput` | FR-34 (phía indexer): `.env`, khoá, test, build output không bao giờ được chọn đọc |
| `GraphServiceTest.noScanUntilPmoAsks` | AC-01/AIR-05: không có chỉ mục thì cập nhật tăng dần không làm gì; `graph/` trống |
| `GraphServiceTest.fullScanBuildsCrossRepoGraph` | FR-19/20/23: graph 3 repo, độ tin cậy edge, mức Service và mức API |
| `GraphServiceTest.indexHoldsNoSecretsAndNeverReadsEnvFiles` | FR-36/NFR-02: chỉ mục không chứa IP/token giả, không có thân hàm |
| `GraphServiceTest.incrementalUpdateParsesOnlyTheGitDiff` | FR-21/AIR-06/NFR-11: chỉ đọc lại file đổi trên nhánh nền, commit ở nhánh khác bị bỏ qua, file xoá bị gỡ |
| `GraphServiceTest.blastRadiusCrossesServices` | AIR-09/FR-23: blast radius xuyên service qua REST, giới hạn 3 tầng; tìm kiếm |
| `GraphServiceTest.manualEdgesSurviveReindex` | FR-22: edge tay còn sau khi quét lại; từ chối node không có |
| `GraphServiceTest.ticketHighlightsItsServices` | FR-23: service của ticket từ spec.json |
| `GraphPerfTest` (bật bằng `-Dperf=true`) | NFR-11: 232.000 dòng/2.000 file: quét toàn bộ + dựng graph 8,2 s (giới hạn 180 s); cập nhật tăng dần 0,7 s (giới hạn 20 s), đo ngày 29/10/2026 |

## Kịch bản thủ công Phase 3

**K-03 (graph, AC-01 phần graph, AC-09 phần mặc định tắt)**

1. Dự án `shop` vừa tạo (K-01). Mở tab Graph: chỉ có 3 service, khung vàng "chưa có chỉ mục", `graph/` trống.
2. Bấm "Quét toàn bộ dự án" → hộp xác nhận báo số file sẽ đọc, không tốn token → OK → khoảng 1 giây sau graph có 51 node.
3. Mức Service: order → payment (http/config/compose), order → topic `order.created` → notification, bảng `orders`, `payment`.
4. Nhấp đúp `order-service` → các API của order và `POST /api/payments` của payment. Tìm `PaymentService.pay` → chọn tầng 3 → "Blast radius" → thấy `PaymentClient.charge` của order-service.
5. Chọn một ticket ở "Tô sáng ticket…" → service của ticket có viền vàng.
6. "Nối edge tay từ node này" → chọn node khác → Lưu. "Quét toàn bộ" lại → edge tay vẫn còn.
7. Commit một file mới vào nhánh `test` của `samples/shop/repos/order-service`, bấm "Cập nhật theo git diff" → báo "đọc 1" ở order-service, "đã mới nhất" ở repo khác.
8. CLI: `java -jar target/devmap.jar index shop` và `index shop --full`.

## Test tự động Phase 4

| Test | Kiểm |
| --- | --- |
| `RuleServiceTest.serviceRulesInheritProjectDefault` | FR-29: preset theo loại service ← mặc định dự án ← rule service; file chỉ lưu phần khác; khôi phục kế thừa; mẫu commit |
| `RuleServiceTest.protectedBranchesAlwaysIncludeBaseBranches` | FR-30: nhánh nền luôn được bảo vệ; mẫu `release/*`; mẫu commit thiếu `{code}` bị từ chối |
| `RuleServiceTest.scopeSortsChangedFiles` | FR-32: phân loại file cấm sửa, DB schema, dependency theo service |
| `RuleServiceTest.globs` | glob của rule (`**`, `*`, tên file, thư mục) |
| `RuleServiceTest.layerCheckFindsControllerCallingRepository` | AIR-12/AC-10: `OrderReportController → OrderRepository` của repo mẫu; payment-service sạch |
| `RuleServiceTest.qualityGateRunsConfiguredCommands` | FR-31: chạy lệnh cấu hình trong thư mục service; lệnh lỗi, thiếu lệnh, vi phạm tách lớp → không đạt |
| `RuleServiceTest.conventionsCollectedFromCodeThenApproved` | FR-33: bản nháp từ code (không đọc `.env`, đã che IP), giữ phần PMO viết, duyệt, thu thập lại về bản nháp |
| `TicketGitServiceTest.ticketFromWorktreeToConfirmedPush` | FR-24/26/27, NFR-07, AC-05, AC-10: worktree từ nhánh nền mới nhất (có commit vừa push lên origin), checkout của PMO không đổi; `pom.xml` cần PMO duyệt (Dev không tự duyệt được); `.env` không commit được; commit mang mã; gate không đạt vì Controller→Repository, sửa xong thì đạt; chưa đạt thì không sang SA; Done chỉ qua xác nhận push của PMO; origin nhận đúng commit, repo không đổi thì không push; changes.md, tests.md, history.md |
| `TicketGitServiceTest.protectedBranchesAreRefused` | FR-30: branch ticket khớp mẫu bảo vệ không bắt đầu được; refspec có `+` bị từ chối |
| `TicketGitServiceTest.pushOnlyTheVerifiedCommit` | commit thêm sau khi gate đạt thì không push được |
| `TaskServiceTest.reopenKeepsCode` (cập nhật) | ticket không có repo vẫn đi tay được tới Done |

## Kịch bản thủ công Phase 4

**K-04 (AC-05, AC-10 phần chặn, AC-02 phần Dev bằng tay)**

Chuẩn bị: dự án `shop` (K-01). Mỗi repo trong `samples/shop/repos` cần một remote `origin` để push thử:
`git clone --bare samples/shop/repos/order-service <tmp>/order-service.git` rồi `git -C samples/shop/repos/order-service remote add origin <tmp>/order-service.git` (tương tự payment-service).

1. Tab "Rule & convention": mặc định dự án có preset trống, 3 ô khoá (xác nhận push, không force-push, không push branch bảo vệ) và dòng "Nhánh nền luôn được bảo vệ: test, main". Chọn `order-service` → preset `maven`, lệnh `mvn -q test`. Máy không có Maven thì đổi lệnh thành lệnh có sẵn (ví dụ `git status`) → Lưu → nút "có rule riêng"; `rules/order-service.devrules.yml` chỉ chứa phần đổi.
2. "Thu thập từ code" → coding convention có mục order-service/payment-service, nhãn "Bản nháp"; "Sửa" thêm một dòng chuẩn riêng → Lưu; "Duyệt" → nhãn "PMO đã duyệt". `conventions.md` không có giá trị trong `.env`.
3. Tạo ticket FEAT `NYX-7` nhắc order-service và payment-service, đi tới "Sẵn sàng" (K-02). Cột phải: tick/bỏ tick repo → "Lưu repo".
4. "Bắt đầu triển khai (pull nhánh nền)" → mục Code có 2 repo, branch `feature/NYX-7-…`, worktree ở `~/.devmap/worktrees/shop/NYX-7/`. Repo gốc vẫn ở nhánh cũ.
5. Trong worktree order-service: thêm một file Java và sửa `pom.xml`. Ở ticket nhập nội dung commit → "Commit" → thẻ vàng "Cần PMO duyệt: pom.xml – đổi dependency" → "PMO duyệt và commit" → commit `NYX-7: …`. Tạo `.env` trong worktree → Commit → báo không commit file bí mật (xoá file đi).
6. "Chạy quality gate" → order-service "gate chưa đạt", tests.md có `OrderReportController → OrderRepository`. Bấm "Chuyển SA kiểm chứng" → báo lỗi gate. Xoá `OrderReportController.java` trong worktree, commit, chạy lại gate → "gate đạt" → chuyển SA kiểm chứng → "SA đạt, chờ PMO xác nhận".
7. "Xác nhận push" → khung xem lại: repo → origin/branch, số commit, tests.md, diff. Bấm "Push" → ticket Done; `git -C <tmp>/order-service.git branch` có branch ticket; payment-service (không đổi) không bị push.
8. CLI: `java -jar target/devmap.jar rules show shop order-service`, `rules check shop --repo order-service` (exit 1, báo vi phạm tách lớp), `rules check shop --path ~/.devmap/worktrees/shop/NYX-7/order-service --repo order-service`.
9. Cấu hình mặc định dự án thêm `feature/*` vào branch bảo vệ → ticket mới không bắt đầu triển khai được ("branch được bảo vệ"). Xoá đi; xoá `test` khỏi danh sách → `test` vẫn hiện trong nhánh nền được bảo vệ.

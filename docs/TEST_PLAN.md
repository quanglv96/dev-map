# TEST_PLAN – DevMap

Chạy toàn bộ: `./mvnw verify` (JUnit 5 + Checkstyle + ESLint). Dự án mẫu: `samples/shop` (xem README trong đó).

## Bản đồ AC → phase → kiểm thử

| AC | Phase | Trạng thái | Kiểm thử |
| --- | --- | --- | --- |
| AC-01 Khởi tạo | 1, 3 | Đạt (phần tự động); pull do Dev ở P4 gọi cùng cơ chế | `ProjectServiceTest.createsDataFolderWithReposAndServices`, `GraphServiceTest.noScanUntilPmoAsks`, `GraphServiceTest.incrementalUpdateParsesOnlyTheGitDiff`, `WebTest`, `CliTest`, K-01, K-03 |
| AC-02 FEAT trọn luồng | 2, 4, 5, 6 | Đạt (Claude Code thật, K-06): SA phân tích → PMO chốt → Dev pull nhánh nền, sửa, gate, commit `NYX-21: …` → SA kiểm chứng tự chạy → PMO push → Done | `AgentServiceTest.saVerificationCatchesScopeCreepAndChangedSignature`, `TicketGitServiceTest.ticketFromWorktreeToConfirmedPush`, K-04, K-06 |
| AC-03 Mở lại ticket | 2, 5 | Đạt (phần tự động): mở lại cùng mã, ngữ cảnh dựng lại từ tài liệu, SA không đạt được khi analysis.md chưa có case mới (FR-11) | `TaskServiceTest.reopenKeepsCode`, `AgentServiceTest.doneDropsSessionContextAndReopenRebuildsFromDocs`, K-02 |
| AC-04 MAINT | 2, 4, 5, 6 | Đạt (phần tự động): PMO chốt và xếp thứ tự 2 ticket MAINT; "Chạy hàng đợi" cho Dev làm đúng thứ tự, ticket sau chỉ bắt đầu khi ticket trước qua SA kiểm chứng; gate (có unit test) đạt ở cả hai | `AgentServiceTest.queueRunsMaintTicketsInPmoOrder`, `TaskServiceTest.flowGatesAndQueue`, K-07 |
| AC-05 Push | 4 | Đạt (phần tự động): chỉ PMO push, chỉ commit đã qua gate, không force-push, không push branch bảo vệ (nhánh nền luôn bảo vệ) | `TicketGitServiceTest` (3 test), `RuleServiceTest.protectedBranchesAlwaysIncludeBaseBranches`, `TaskServiceTest.flowGatesAndQueue`, K-04 |
| AC-06 Bí mật | 2, 5 | Đạt (phần tự động): agent không có công cụ đọc file/shell; công cụ DevMap từ chối `.env`, đường dẫn ra ngoài, ghi file bí mật; nhật ký đầu vào không có giá trị nào của `.env` hay IP giả | `SecretMaskerTest`, `TaskServiceTest.createsTicketWithMaskedSpec`, `AgentServiceTest.toolsNeverServeSecretsOrWholeFiles`, `ClaudeCodeProviderTest.runsWithoutBuiltInToolsOrUserSettings`, K-05 |
| AC-07 Token | 5 | Đạt (phần tự động): 3 câu = 3 phiên mới, không gửi lại chat, câu sau dùng summary.md, token mỗi câu và cộng dồn | `AgentServiceTest.threeQuestionsAreThreeFreshSessions`, K-05 |
| AC-08 Hai dự án song song | 5, 6 | Một phần: hai dự án chạy agent cùng lúc (K-06 bước 9, Claude Code thật), mỗi dự án chỉ thấy ticket/repo của mình, nhật ký tách riêng; tiêu đề tab, biểu tượng, thông báo đã làm nhưng chưa kiểm trên trình duyệt (K-07) | `AgentServiceTest.unknownSessionTokenIsRejected`, `WebTest.mcpNeedsALiveSession`, `WebTest.projectLifecycleOverApi`, K-06, K-07 |
| AC-09 Quét toàn bộ | 3, 5 | Đạt (phần tự động): bật cho một câu hỏi thì quét đúng một lần trước câu đó, câu sau không quét | `GraphServiceTest.noScanUntilPmoAsks`, `AgentServiceTest.toolsNeverServeSecretsOrWholeFiles`, K-03, K-05 |
| AC-10 Ngữ cảnh gọn và chất lượng | 3, 4, 5, 6 | Đạt (phần tự động): nhật ký đầu vào chỉ có chữ ký và đoạn ≤ 80 dòng, không có nguyên file; system prompt giống hệt giữa các phiên (cache); thay đổi thêm Controller→Repository bị chặn ở gate (vi phạm có sẵn ở nhánh nền chỉ liệt kê, D-44); Dev không sang SA khi gate chưa đạt | `AgentServiceTest.*`, `RuleServiceTest.layerCheckFindsControllerCallingRepository`, `TicketGitServiceTest.ticketFromWorktreeToConfirmedPush` |
| AC-11 Kiểm chứng phạm vi | 5, 6 | Đạt (agent giả và Claude Code thật, K-06 bước 6): file ngoài yêu cầu và chữ ký bị đổi có nơi gọi đều nêu trong verify.md, trả về Dev, không có `awaiting_push`; SA không sửa được plan.md đã chốt | `AgentServiceTest.saVerificationCatchesScopeCreepAndChangedSignature`, K-06 |
| AC-12 Tối ưu token | 2, 5 | Đạt (phần tự động): prompt không có câu xã giao, nguyên văn hay token; nhật ký đầu vào bị xoá khi Done; mở lại trả lời từ tài liệu | `TaskServiceTest.createsTicketWithMaskedSpec`, `RequestCleanerTest`, `AgentServiceTest.threeQuestionsAreThreeFreshSessions`, `AgentServiceTest.doneDropsSessionContextAndReopenRebuildsFromDocs`, K-02, K-05 |

## Test tự động Phase 1

| Test | Kiểm |
| --- | --- |
| `ProjectServiceTest.createsDataFolderWithReposAndServices` | folder dữ liệu đủ cấu trúc SRS; 3 repo với nhánh nền `test`/`main`; mỗi repo phát hiện đúng service; `project.md` có danh sách; `graph/` rỗng (không quét) |
| `ProjectServiceTest.rejectsMissingBaseBranchAndBadNames` | nhánh nền không tồn tại, tên sai, tên dành riêng, thư mục không phải git → lỗi, không tạo folder |
| `ProjectServiceTest.removeKeepsDataAndCopiedFolderCanBeRegisteredAgain` | bỏ đăng ký giữ dữ liệu; đăng ký lại folder đã có |
| `ProjectServiceTest.updateValidatesAndKeepsPmoTextInProjectMd` | sửa cấu hình (FR-04), kiểm hợp lệ, giữ phần chữ của PMO |
| `ProjectServiceTest.scanMarksUsedFolders` | quét folder dữ liệu, đánh dấu folder dự án nào đang dùng; không xoá gì |
| `ProjectServiceTest.scanReposFindsRepoChildrenOrTheFolderItself` | folder dự án: tìm repo con (bỏ folder không phải git) hoặc chính folder; nhánh nền = nhánh đang checkout; không ghi gì |
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
   Ô **Folder dự án** → **Chọn…** chọn `samples/shop/repos` → danh sách có 3 repo, nhánh nền và service điền sẵn; **Bỏ khỏi DevMap** một repo → chỉ mất khỏi danh sách, folder repo vẫn còn trên máy.
   Nút **Chọn…** mở hộp thoại chọn folder của hệ điều hành (Windows: kiểu File Explorer, nằm trên trình duyệt). Huỷ → ô và danh sách không đổi; gõ tay đường dẫn → không quét; chọn folder → ô điền đường dẫn và danh sách folder bên trong hiện ra.
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

## Test tự động Phase 5

Agent giả (`AgentServiceTest.FakeAgent`) gọi công cụ qua đúng `McpServer.handle` (JSON-RPC như Claude Code), chạy đồng bộ.

| Test | Kiểm |
| --- | --- |
| `AgentServiceTest.threeQuestionsAreThreeFreshSessions` | AC-07, AC-12, FR-37/38/40, AIR-11: `initialize`/`tools/list` (SA không có công cụ sửa file), 3 phiên 3 token khác nhau, system prompt giống hệt, prompt không có câu xã giao/nguyên văn/token, câu 2 thấy summary.md của câu 1 nhưng không thấy câu hỏi 1, summary.md tự ghi khi agent quên, token theo ticket và dự án, SA không chốt được, task.md không đọc được |
| `AgentServiceTest.toolsNeverServeSecretsOrWholeFiles` | AC-06, AC-09, AC-10, FR-34/35: `.env` và `../` bị từ chối, đoạn > 80 dòng và snippet cả class bị từ chối, IP trong code bị che, graph 2 tầng trong prompt, quét toàn bộ đúng một lần, nhật ký không có giá trị `.env` hay nguyên file, Dev không chạy trước khi chốt |
| `AgentServiceTest.saVerificationCatchesScopeCreepAndChangedSignature` | AC-11, FR-43, AIR-03/13/14, FR-25/28: SA phân tích → gửi chốt; Dev pull, không ghi được `.env`/ngoài worktree, chưa gate thì không sang SA, commit mang mã; SA kiểm chứng tự chạy, `verify_facts` nêu `Extra.java` và `OrderService.create` ← `OrderController.create`; verify.md thiếu thì không đạt; trả về Dev; PMO tự commit trong worktree → phiên Dev sau được báo, changes.md cập nhật |
| `AgentServiceTest.doneDropsSessionContextAndReopenRebuildsFromDocs` | FR-45, AC-12, FR-11, AC-03: Done xoá nhật ký/thư mục phiên, tài liệu còn; mở lại thì prompt có REOPEN_BUG và summary.md cũ; SA không đạt khi analysis.md chưa có BUG-01 |
| `AgentServiceTest.unknownSessionTokenIsRejected` | NFR-06: token phiên lạ hoặc thiếu bị từ chối |
| `ClaudeCodeProviderTest.parsesStreamJson` | đọc stream-json của Claude Code 2.1.x: chữ, lời gọi công cụ, kết quả, usage, lỗi |
| `ClaudeCodeProviderTest.runsWithoutBuiltInToolsOrUserSettings` | `--tools ""`, `--strict-mcp-config`, `--setting-sources project`, `--allowed-tools mcp__devmap`, `--permission-mode dontAsk` |
| `WebTest.mcpNeedsALiveSession` | `/mcp` cần token phiên còn sống; run token không mở được; IP ngoài máy → 403 |
| `SecretMaskerTest.keepsBranchNamesAndPathsButNotRandomTokens` | D-40: branch/đường dẫn không bị che, token ngẫu nhiên vẫn bị che |

## Kịch bản thủ công Phase 5

**K-05 (Claude Code thật; AC-06, AC-07, AC-09, AC-12, một phần AC-02)**

Bước 1–3 đã chạy qua API ngày 29/10/2026 trên `samples/shop` với Claude Code 2.1.312: phiên SA dùng `get_ticket_docs`, `search_graph`, `get_signatures`, `write_ticket_doc`;
nhật ký đầu vào không có "Chào em", "Cảm ơn", IP hay mật khẩu giả; 53.919 token vào (40.159 đọc từ cache), 2.647 ra, $0,17. Các bước còn lại chưa chạy với agent thật.

1. Dự án `shop` đã quét toàn bộ (K-03). Tạo ticket `NYX-9`: "Chào em, nhờ em thêm hoàn tiền một phần cho order-service nhé. Không được đổi API cũ. Cảm ơn!"
2. Tab Chat (mặc định) → hỏi "Hoàn tiền một phần sẽ chạm những service, API và method nào?" → Gửi. Tin SA bên trái khung nét đứt, ghi "phiên mới", "Ngữ cảnh: spec.json, graph", các bước hiện dần (fetch theo git diff, từng công cụ). Ticket sang "SA phân tích".
3. "Đầu vào agent" → không có nguyên văn yêu cầu, không có giá trị trong `.env`, không có nguyên file. Cột phải "Token đã dùng" có số của ticket và dự án.
4. Hỏi tiếp 2 câu → mỗi câu một phiên mới, câu sau nhắc lại đúng điều đã chốt (nhờ summary.md).
5. Bảo SA "phân tích và gửi chốt" → analysis.md (AC Given/When/Then), plan.md (việc theo repo, tên file) → "Chờ PMO chốt". Bấm "Yêu cầu phân tích lại" một lần, hỏi lại, rồi "Chốt giải pháp".
6. Ticket "Sẵn sàng" → hỏi "Triển khai" (tự chọn Dev) → Dev pull nhánh nền, sửa, chạy gate, commit `NYX-9: …`, chuyển SA; SA kiểm chứng tự chạy, ghi verify.md.
7. Cố ý bảo Dev sửa thêm một file ngoài plan và đổi chữ ký `OrderService.create` → SA nêu cả hai trong verify.md, trả về Dev (AC-11).
8. Tick "Quét toàn bộ dự án cho câu này" → hộp xác nhận báo số file → bước "PMO bật quét toàn bộ…" chỉ có ở câu đó (AC-09).
9. Xác nhận push → Done → `~/.devmap/sessions/shop/NYX-9/` không còn; "Đầu vào agent" báo đã đóng. "Báo bug" → hỏi SA → trả lời từ tài liệu (AC-12, AC-03).
10. CLI: `java -jar target/devmap.jar prompt shop NYX-9 --role sa --question "..."` in system prompt, prompt, công cụ; không gọi LLM.

## Test tự động Phase 6

| Test | Kiểm |
| --- | --- |
| `AgentServiceTest.queueRunsMaintTicketsInPmoOrder` | AC-04, FR-07, NFR-10: PMO đổi thứ tự 2 ticket MAINT; "Chạy hàng đợi" chạy Dev ticket thứ nhất, ticket thứ hai chỉ bắt đầu khi ticket thứ nhất đã "Chờ PMO xác nhận"; gate đạt; hàng đợi tự tắt khi hết; hàng đợi trống thì báo lỗi; audit.jsonl có thao tác PMO, công cụ của DEV/SA kèm service và rule, không có nội dung file |
| `AgentServiceTest.saVerificationCatchesScopeCreepAndChangedSignature` (thêm) | D-45: SA không ghi được plan.md đã chốt; Dev xoá file bằng `delete_file`, không xoá được `.env`; ghi chú "Commit PMO tự làm" |
| `TicketGitServiceTest.ticketFromWorktreeToConfirmedPush` (sửa) | AC-10, D-44: controller mới gọi thẳng repository làm gate không đạt; vi phạm có sẵn ở nhánh nền chỉ liệt kê, gate đạt |
| `TaskServiceTest.createsTicketWithMaskedSpec` (thêm) | NFR-10: tạo ticket ghi vào audit.jsonl, đã che |
| `WebTest.projectLifecycleOverApi` (thêm) | NFR-10: quét chỉ mục của PMO và thao tác ticket có trong `/audit`, request lỗi và lệnh đọc không ghi, lọc theo ticket; `/agents`; "Chạy hàng đợi" khi trống → 400 |
| `GraphPerfTest` (`-Dperf=true`) | NFR-11, đo lại ngày 29/10/2026 sau Phase 6: 232.000 dòng/2.000 file quét toàn bộ + dựng graph 17,1 s (giới hạn 180 s), tăng dần 0,42 s (giới hạn 20 s) |

NFR-12 (tối đa 2 agent, phiên sau chờ lượt) không đổi từ Phase 5: pool cố định `devmap.agent.max-concurrent`, tin agent hiện "Đang chờ lượt" cho tới khi có chỗ.

## Kịch bản Phase 6

**K-06 (Claude Code thật, đã chạy ngày 29/10/2026; AC-02, AC-03, AC-05, AC-06, AC-07, AC-08, AC-11, AC-12, NFR-10)**

Môi trường: bản sao `samples/shop` (`init-repos.sh <thư mục>`, mỗi repo có remote `origin` là bare repo), `devmap.home` riêng, Claude Code 2.1.312, máy không có `mvn` trong PATH nên rule dự án dùng `mvnw.cmd -q -DskipTests compile` và `mvnw.cmd -q test`. Tổng chi phí 3,39 USD (cộng từ chat.json).

1. Quét toàn bộ một lần. Tạo `NYX-21` bằng câu có "Chào em… nhé… Cảm ơn!" và một token `ghp_…`: spec.json gọn, token thành `[MASKED_TOKEN]` (AC-12).
2. Hỏi SA "phân tích và gửi chốt": analysis.md có 6 AC Given/When/Then, plan.md có 2 phương án, việc theo file, ghi chú đổi DB schema; → "Chờ PMO chốt". 96.727 token vào (79.660 đọc từ cache), 0,28 USD.
3. PMO chốt. Hỏi Dev, kèm yêu cầu cố ý lệch phạm vi: Dev pull nhánh nền vào worktree, sửa, viết test, nhưng **từ chối** phần lệch vì trái plan đã chốt; commit bị chặn vì `pom.xml` thêm dependency cần PMO duyệt (FR-32). Gate lần đầu không đạt vì vi phạm có sẵn ở nhánh nền → sửa D-44.
4. PMO duyệt `pom.xml`; Dev thấy `target/` chưa có `.gitignore` nên dừng hỏi PMO thay vì commit file build → thêm `.gitignore` vào mẫu (D-45).
5. Dev commit `NYX-21: …`, gate đạt, chuyển SA; SA kiểm chứng tự chạy, nêu đúng 2 file ngoài plan nhưng tự sửa plan.md để chấp nhận → khoá plan.md sau khi chốt (D-45).
6. AC-11: PMO trả về Dev và tự commit trong worktree: đổi chữ ký `PaymentClient.charge` (nơi gọi `OrderService.create`) và thêm `util/NoteFormatter.java`. Dev chạy gate, chuyển SA. SA kiểm chứng: verify.md nêu đích danh cả hai mục, nơi gọi, "Kết luận: KHÔNG ĐẠT", trả về Dev; không có `awaiting_push`.
7. Dev làm theo phương án SA đề xuất (khôi phục chữ ký, xoá file bằng `delete_file`), SA kiểm chứng lại: ĐẠT → "Chờ PMO xác nhận".
8. PMO xác nhận push: `origin` có nhánh `feature/NYX-21-…` với 4 commit đều mang `NYX-21:`; ticket Done; `sessions/shop/NYX-21/` bị xoá, "Đầu vào agent" báo đã đóng (FR-45). Mở lại "Báo bug" → hỏi SA: ngữ cảnh chỉ spec.json, summary.md, analysis.md, plan.md, graph; trả lời đúng quy tắc BR-4 đã chốt (AC-03, AC-12).
9. AC-08: thêm dự án `lendbiz` (bản sao repo khác), hỏi SA ở `lendbiz/LB-1` và `shop/NYX-22` cùng lúc: `/agents` của mỗi dự án báo đúng ticket đang chạy; nhật ký mỗi dự án không có dòng nào của dự án kia.
10. Nhật ký đầu vào còn lại không chứa giá trị nào của `.env`, IP `10.12.0.*` hay token `ghp_…` (AC-06). `logs/audit.jsonl` của `shop`: 228 dòng, có PMO/SA/DEV, ticket, repo, service, rule; không có bí mật (NFR-10).

**K-07 (giao diện, kiểm bằng tay; AC-04, AC-08)** — chưa chạy trên trình duyệt trong Phase 6

1. Mở `/shop` và `/lendbiz` ở hai tab. Bấm "Bật thông báo" → cho phép.
2. Hỏi agent ở `shop`: tiêu đề tab `shop · agent đang chạy (NYX-…)…`, biểu tượng có chấm xanh. Chuyển sang tab khác: khi xong có thông báo "Agent đã chạy xong"; bấm vào → về đúng tab, mở đúng ticket.
3. Agent gửi PMO chốt: tiêu đề `(1) shop · chờ PMO xác nhận`, chấm vàng, thông báo "Chờ PMO chốt giải pháp…". Trang chủ: `(1) DevMap`.
4. Phiên agent lỗi (ví dụ đặt `devmap.agent.command` sai): tiêu đề `shop · lỗi (…)`, chấm đỏ, thông báo lỗi.
5. Hai ticket MAINT Sẵn sàng: đổi thứ tự bằng ↑↓, bấm "Chạy hàng đợi" → nút thành "Dừng hàng đợi", "đang làm <mã>"; ticket thứ hai chỉ bắt đầu khi ticket thứ nhất sang "Chờ PMO xác nhận" (AC-04).
6. Tab "Nhật ký": lọc theo ticket, thấy thao tác của PMO, SA, DEV.
7. Đang có agent chạy, Ctrl+C ở cửa sổ `devmap serve` → in cảnh báo, server vẫn chạy; Ctrl+C lần nữa trong 10 giây → tắt. Không có agent → Ctrl+C tắt ngay.
8. Đóng tab khi agent đang chạy → mở lại thấy agent vẫn chạy tiếp.

**K-08 (giao diện, kiểm bằng tay; D-50)**

1. Ticket Sẵn sàng, chưa có worktree: tích "Code và push thẳng vào nhánh nền" → hộp xác nhận → dòng Branch thành "Nhánh nền của từng repo (push thẳng)". Bấm Bắt đầu triển khai: thẻ repo hiện `main ← main`, ô tích biến mất.
2. Đi hết gate → SA kiểm chứng → Xác nhận push: hộp xác nhận ghi `→ origin/main`; origin/main lên commit đó, không có branch `feature/*` (tự động: `TicketGitServiceTest.directModePushesToTheBaseBranch`).
3. Ticket mơ hồ: SA trả lời câu hỏi đánh số, summary.md có `## Câu hỏi chờ PMO` → tab "Câu hỏi chờ PMO (n)" hiện đầu tiên. Trả lời 1 câu, bấm Gửi → chuyển sang Chat, phiên SA mới nhận đúng câu hỏi → trả lời; xong phiên, câu đã trả lời mất khỏi tab.

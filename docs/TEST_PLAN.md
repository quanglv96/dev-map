# TEST_PLAN – DevMap

Chạy toàn bộ: `./mvnw verify` (JUnit 5 + Checkstyle + ESLint). Dự án mẫu: `samples/shop` (xem README trong đó).

## Bản đồ AC → phase → kiểm thử

| AC | Phase | Trạng thái | Kiểm thử |
| --- | --- | --- | --- |
| AC-01 Khởi tạo | 1 (graph theo git diff: 3) | Một phần: phần khởi tạo xong | `ProjectServiceTest.createsDataFolderWithReposAndServices`, `WebTest.projectLifecycleOverApi`, `CliTest`, kịch bản K-01 |
| AC-02 FEAT trọn luồng | 2, 4, 5 | Chưa | |
| AC-03 Mở lại ticket | 2, 5 | Một phần: mở lại bằng tay xong; SA cập nhật analysis (FR-11) ở P5 | `TaskServiceTest.reopenKeepsCode`, K-02 |
| AC-04 MAINT | 2, 4, 5 | Một phần: chốt + thứ tự hàng đợi xong; chạy theo thứ tự ở P5 | `TaskServiceTest.flowGatesAndQueue` |
| AC-05 Push | 4 | Một phần: cổng "Xác nhận push" chỉ PMO, kiểm ở server; push thật ở P4 | `TaskServiceTest.flowGatesAndQueue` |
| AC-06 Bí mật | 2, 5 | Một phần: tài liệu không chứa bí mật; chặn agent đọc `.env` ở P5 | `SecretMaskerTest`, `TaskServiceTest.createsTicketWithMaskedSpec` |
| AC-07 Token | 5 | Chưa | |
| AC-08 Hai dự án song song | 5, 6 | Chưa | |
| AC-09 Quét toàn bộ | 3, 5 | Chưa | |
| AC-10 Ngữ cảnh gọn và chất lượng | 3, 4, 5 | Chưa (đã có vi phạm mẫu `OrderReportController`) | |
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

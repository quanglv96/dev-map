# TEST_PLAN – DevMap

Chạy toàn bộ: `./mvnw verify` (JUnit 5 + Checkstyle + ESLint). Dự án mẫu: `samples/shop` (xem README trong đó).

## Bản đồ AC → phase → kiểm thử

| AC | Phase | Trạng thái | Kiểm thử |
| --- | --- | --- | --- |
| AC-01 Khởi tạo | 1 (graph theo git diff: 3) | Một phần: phần khởi tạo xong | `ProjectServiceTest.createsDataFolderWithReposAndServices`, `WebTest.projectLifecycleOverApi`, `CliTest`, kịch bản K-01 |
| AC-02 FEAT trọn luồng | 2, 4, 5 | Chưa | |
| AC-03 Mở lại ticket | 2, 5 | Chưa | |
| AC-04 MAINT | 2, 4, 5 | Chưa | |
| AC-05 Push | 4 | Chưa | |
| AC-06 Bí mật | 2, 5 | Chưa (đã có dữ liệu giả trong `samples/shop`) | |
| AC-07 Token | 5 | Chưa | |
| AC-08 Hai dự án song song | 5, 6 | Chưa | |
| AC-09 Quét toàn bộ | 3, 5 | Chưa | |
| AC-10 Ngữ cảnh gọn và chất lượng | 3, 4, 5 | Chưa (đã có vi phạm mẫu `OrderReportController`) | |
| AC-11 Kiểm chứng phạm vi | 5 | Chưa | |
| AC-12 Tối ưu token | 2, 5 | Chưa | |

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

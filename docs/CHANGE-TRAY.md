# CHANGE-TRAY – Biểu tượng khay hệ thống và thông báo Windows

Tóm tắt phần bổ sung theo `docs/PROMPT_TRAY.md`, để PMO cập nhật vào `docs/SRS.md`. Quyết định kỹ thuật: `docs/DECISIONS.md` D-61. Hướng dẫn dùng: `docs/USER_GUIDE.md` mục 10. Kiểm thử: `docs/TEST_PLAN.md` mục "Biểu tượng khay và thông báo".

## 1. Thay đổi so với SRS hiện tại

- **Đóng hết tab**: khi có biểu tượng khay, đóng tab không còn tắt server (thay đổi D-56). Tắt bằng menu Thoát DevMap hoặc Ctrl+C.
- **Cách chạy**: thêm `DevMap.cmd` (chạy nền bằng `javaw`) và `DevMap-console.cmd` ở thư mục gốc. `bin/devmap.cmd` vẫn là launcher dòng lệnh.
- **Đăng nhập giao diện**: thêm link một lần dùng `?once=` cho các link DevMap tự mở. Link `?token=` vẫn dùng được.
- **Trang chủ**: thêm thẻ "Thông báo Windows".
- **Khi có khay**: trang web không bật thông báo trình duyệt nữa, để không báo đôi.

## 2. Cấu hình mới

| Ở đâu | Nội dung |
| --- | --- |
| `~/.devmap/notifications.yml` (theo máy, không đồng bộ) | `muted_kinds` (`DECISION`, `QUESTION`, `PUSH`, `FAILURE`, `SYNC`), `muted_projects`, `quiet_enabled` (mặc định `true`), `quiet_from` (`22:00`), `quiet_to` (`07:00`), `snooze_until` |
| `devmap serve --no-tray` | Không tạo biểu tượng; đóng hết tab lại tự tắt như D-56 |
| `devmap serve --background` | Tuỳ chọn ẩn, dành cho `DevMap.cmd` và shortcut khởi động: ghi log ra file |

File mới trên máy: `~/.devmap/serve.lock` (khoá một phiên), `~/.devmap/logs/devmap.log` và `devmap.log.1` (log khi chạy nền), `DevMap.lnk` trong thư mục Startup của người dùng (khi bật Khởi động cùng Windows).

API mới: `GET`/`PUT /api/tray/settings`, `POST /api/tray/focus`, `POST /api/tray/open`.

## 3. Yêu cầu chức năng FR-58 → FR-70

| Mã | Yêu cầu | Đã làm |
| --- | --- | --- |
| FR-58 | Biểu tượng khi chạy `devmap serve`, lệnh khác không tạo | Có. Thêm `--no-tray`. |
| FR-59 | 4 trạng thái, vẽ động, rõ ở 100–200% DPI | Có. Icon gốc 16/24/32 px trong `src/main/resources/tray/`. Nhiều trạng thái: số > lỗi > agent chạy. |
| FR-60 | Tooltip số việc, số agent | Có. |
| FR-61 | Double-click mở trang khởi động | Có, bằng link một lần dùng. |
| FR-62 | Menu chuột phải | Có. "Việc chờ bạn" liệt kê tối đa 15 ticket, số trên nhãn vẫn là tổng. "Đồng bộ ngay" chỉ hiện khi có dự án bật đồng bộ. |
| FR-63 | Popup `[dự án] mã ticket`, bấm mở đúng ticket | Có. Chỉ toast mới nhất mở được đúng ticket (D-61). |
| FR-64 | Sự kiện bật popup, sự kiện nhẹ chỉ cập nhật icon | Có, so sánh trạng thái ticket giữa hai lần đọc, thêm sự kiện `GateFailed`. |
| FR-65 | Gộp 10 giây, không lặp trong 5 phút, không báo ticket đang xem | Có. |
| FR-66 | Cấu hình theo máy, giờ yên lặng, tạm tắt | Có. |
| FR-67 | Thoát: hỏi nếu còn agent, dừng sạch, nhả khoá | Có. Nhả khoá tối đa 15 giây. |
| FR-68 | Một phiên duy nhất | Có, khoá file theo `devmap.home`. |
| FR-69 | `DevMap.cmd` dùng `javaw`, log ra file, có bản console | Có. Vẫn loé console rất ngắn lúc khởi động do là file `.cmd`. |
| FR-70 | Khởi động cùng Windows bằng shortcut Startup | Có, mặc định tắt. |

## 4. Tiêu chí nghiệm thu AC-21 → AC-30

| AC | Nội dung | Kiểm thử |
| --- | --- | --- |
| AC-21 | Chính sách thông báo | Tự động: `NotificationTest`, `TrayServiceTest` |
| AC-22 | Gộp và chống spam | Tự động: `NotificationTest` |
| AC-23 | Không lộ nội dung | Tự động: `NotificationTest.aTokenInAnEventNeverReachesTheScreen` |
| AC-24 | Không có khay | Tự động: `TrayTest`, `WebTest.serverRunsWithoutATray` |
| AC-25 → AC-30 | Kiểm trên máy thật Windows 10 và 11 | Thủ công: K-11 trong `docs/TEST_PLAN.md` |

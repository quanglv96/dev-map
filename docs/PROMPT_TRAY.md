# Bổ sung tính năng: Biểu tượng khay hệ thống và thông báo Windows

> Dán file này cho agent trong repo `devmap/`. Agent đã có `CLAUDE.md`, `docs/SRS.md`; prompt này mô tả phần bổ sung.

## 0. Trước khi code
1. Đọc lại `CLAUDE.md`, `docs/SRS.md`, `docs/DECISIONS.md`.
2. **Khảo sát source hiện tại**: lệnh `devmap serve` khởi động thế nào, file `DevMap.cmd` (nếu có), cơ chế token đăng nhập giao diện, luồng sự kiện SSE (agent cần duyệt, SA kiểm chứng xong, lỗi, đồng bộ…), phần Cấu hình. Không giả định cấu trúc; bám theo code đang có.
3. Viết kế hoạch ngắn và ghi quyết định vào `docs/DECISIONS.md`, rồi làm luôn.

## 1. Mục tiêu
Khi DevMap chạy, có **biểu tượng ở khay hệ thống Windows** (góc phải thanh taskbar) giống Zalo/Telegram:
- Hiện trạng thái và số việc đang chờ PMO.
- Bật **thông báo popup của Windows** khi có việc cần PMO, kể cả khi đã đóng trình duyệt.
- Bấm vào thông báo hoặc biểu tượng thì mở đúng dự án, đúng ticket trên trình duyệt.

## 2. Yêu cầu chức năng

| Mã | Yêu cầu |
|---|---|
| FR-58 | Khi chạy `devmap serve`, tạo biểu tượng khay hệ thống bằng `java.awt.SystemTray` / `TrayIcon`. Các lệnh CLI khác (`sync`, `index`, `task`…) **không** tạo biểu tượng. |
| FR-59 | Biểu tượng có 4 trạng thái, vẽ động bằng `Graphics2D` trên icon gốc: bình thường; **chấm đỏ kèm số** khi có việc chờ PMO; chấm vàng khi agent đang chạy; dấu lỗi khi agent lỗi hoặc đồng bộ lỗi. Rõ nét ở 100–200% DPI (`setImageAutoSize(true)`, chuẩn bị icon 16/24/32 px). |
| FR-60 | Tooltip khi rê chuột: `DevMap · <n> việc chờ bạn · <m> agent đang chạy`. |
| FR-61 | Double-click biểu tượng: mở trình duyệt mặc định vào `localhost:<port>` (trang khởi động). |
| FR-62 | Menu chuột phải (tiếng Việt): **Mở DevMap** · **Việc chờ bạn (n)** → danh sách ticket chờ, bấm để mở đúng ticket · **Dự án** → nyxhub, lendbiz… · **Tạm tắt thông báo** → 1 giờ / Đến 7 giờ sáng / Bật lại · **Đồng bộ ngay** (nếu đã có tính năng đồng bộ) · **Khởi động cùng Windows** (bật/tắt) · **Thoát DevMap**. |
| FR-63 | Thông báo popup bằng `TrayIcon.displayMessage` (trên Windows 10/11 hiện thành toast ở góc màn hình). Tiêu đề `[<dự án>] <mã ticket>`, nội dung một dòng ngắn, ví dụ `Chờ bạn chốt giải pháp`. Bấm vào thông báo mở `localhost:<port>/<dự án>?ticket=<mã>`. |
| FR-64 | Sự kiện bật thông báo (lấy từ luồng sự kiện SSE đang có): agent SA cần PMO chốt giải pháp hoặc hỏi lại nghiệp vụ; SA kiểm chứng xong, chờ xác nhận push; agent lỗi, test hoặc quality gate fail; xung đột đồng bộ hoặc máy khác chiếm quyền. Sự kiện nhẹ (agent xong một bước, đồng bộ xong) chỉ cập nhật biểu tượng, không bật popup. |
| FR-65 | Gộp và chống spam: nhiều sự kiện trong 10 giây gộp thành một thông báo `Có 3 việc chờ bạn`; cùng một sự kiện của cùng ticket không báo lại trong 5 phút. Khi PMO đang mở đúng ticket đó trên trình duyệt (tab đang hiển thị), không bật popup. |
| FR-66 | Cấu hình thông báo (lưu ở cấu hình máy, không đồng bộ): bật/tắt theo từng loại sự kiện và từng dự án; giờ yên lặng (mặc định 22:00–07:00, sự kiện lỗi vẫn chỉ cập nhật biểu tượng); trạng thái "Tạm tắt" từ menu. |
| FR-67 | Thoát DevMap từ menu: nếu còn agent đang chạy thì hỏi xác nhận (dùng hộp thoại hệ thống); dừng server sạch sẽ, gỡ biểu tượng, giải phóng khóa mềm đồng bộ nếu có. |
| FR-68 | Chạy một phiên duy nhất: chạy `DevMap.cmd` lần hai khi đã có DevMap chạy thì không mở server thứ hai, chỉ báo phiên đang chạy mở trình duyệt rồi thoát. |
| FR-69 | `DevMap.cmd` dùng `javaw` để **không hiện cửa sổ terminal**; log ghi ra file trong `~/.devmap/logs/`. Thêm `DevMap-console.cmd` dùng `java` để xem log khi cần gỡ lỗi. |
| FR-70 | Khởi động cùng Windows (tuỳ chọn, mặc định tắt): tạo/xoá shortcut trong thư mục Startup của người dùng hiện tại. Không sửa registry, không cần quyền admin. |

## 3. Lưu ý kỹ thuật bắt buộc
- Spring Boot **mặc định chạy headless**, khi đó `SystemTray` không dùng được. Phải tắt headless khi chạy `serve` (ví dụ `SpringApplication.setHeadless(false)` hoặc `-Djava.awt.headless=false`) và kiểm tra lại sau khi đóng gói jar/jpackage.
- Kiểm tra `SystemTray.isSupported()`; nếu không hỗ trợ (Linux không có khay, môi trường không màn hình) thì bỏ qua lặng lẽ, ghi log, DevMap vẫn chạy bình thường.
- Mọi thao tác AWT chạy trên event dispatch thread; không chặn luồng của server.
- Mở trình duyệt bằng `Desktop.browse`. Link mở từ khay phải vào được giao diện **không cần nhập lại token**, dùng lại cơ chế xác thực hiện có (ví dụ link một lần dùng). Không đưa token dài hạn vào URL.
- Trên Windows, bấm vào thông báo kích hoạt `ActionListener` của `TrayIcon`; lưu URL của thông báo gần nhất để mở đúng chỗ.
- Thiết kế qua interface `TrayNotifier` (cài đặt mặc định bằng AWT), để sau này thay bằng toast WinRT/PowerShell hoặc thêm kênh Telegram mà không sửa phần phát sự kiện.
- Nội dung thông báo **không bao giờ chứa code, nội dung phân tích hay chuỗi bí mật**; chỉ tên dự án, mã ticket, trạng thái ngắn. Chạy qua lớp lọc bí mật trước khi hiển thị.
- Tách theo Controller → Service → Repository như phần còn lại; logic quyết định có báo hay không (`NotificationPolicy`) tách riêng để test được.

## 4. Kiểm thử
**Tự động** (JUnit, không cần màn hình):
- [ ] **AC-21 Chính sách thông báo**: đúng loại sự kiện thì báo, sự kiện nhẹ không báo; giờ yên lặng; tạm tắt; bật/tắt theo dự án.
- [ ] **AC-22 Gộp và chống spam**: 3 sự kiện trong 10 giây ra 1 thông báo; lặp lại cùng ticket trong 5 phút không báo.
- [ ] **AC-23 Không lộ nội dung**: sự kiện có chứa chuỗi giống token thì nội dung thông báo đã được che, không có code hay phân tích.
- [ ] **AC-24 Không có khay**: `SystemTray` không hỗ trợ (giả lập) thì server vẫn khởi động, chỉ ghi log.

**Thủ công trên Windows 10 và 11** (ghi checklist vào `docs/TEST_PLAN.md`, có ảnh chụp nếu được):
- [ ] **AC-25** Chạy `DevMap.cmd`: không có cửa sổ terminal, biểu tượng hiện ở khay, rõ nét ở 100% và 150% DPI.
- [ ] **AC-26** Agent cần chốt giải pháp khi trình duyệt đã đóng → toast hiện ở góc màn hình; bấm vào mở đúng dự án, đúng ticket, không phải nhập lại token.
- [ ] **AC-27** Biểu tượng hiện chấm đỏ kèm số việc chờ; hết việc chờ thì chấm mất.
- [ ] **AC-28** Menu chuột phải hiển thị đúng tiếng Việt; Việc chờ bạn mở đúng ticket; Thoát hỏi xác nhận khi còn agent chạy và không để lại tiến trình `javaw`.
- [ ] **AC-29** Chạy `DevMap.cmd` lần hai: không có server thứ hai, trình duyệt mở ra.
- [ ] **AC-30** Bật "Khởi động cùng Windows", đăng xuất rồi đăng nhập lại: DevMap tự chạy, biểu tượng hiện.

## 5. Bàn giao
- Code, test, icon (đặt trong `src/main/resources/tray/`), cập nhật `DevMap.cmd`, thêm `DevMap-console.cmd`.
- Cập nhật `docs/USER_GUIDE.md` (mục "Biểu tượng khay và thông báo"), `docs/TEST_PLAN.md`.
- Viết `docs/CHANGE-TRAY.md` tóm tắt FR-58 → FR-70, AC-21 → AC-30 và các cấu hình mới, để PMO cập nhật vào tài liệu SRS gốc.
- Commit nhỏ theo bước, prefix `TRAY:`. Không push.
- Báo cáo cuối: việc đã làm, kết quả test tự động, checklist thủ công cần PMO chạy trên máy thật.

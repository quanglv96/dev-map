# Bổ sung tính năng: Đồng bộ dữ liệu DevMap giữa nhiều máy

> Dán file này cho agent trong repo `devmap/`. Agent đã có `CLAUDE.md` và `docs/SRS.md`; prompt này mô tả phần bổ sung.

## 0. Trước khi code
1. Đọc lại `CLAUDE.md`, `docs/SRS.md`, `docs/DECISIONS.md`.
2. **Khảo sát source hiện tại**: cấu trúc package, cách đang đọc ghi folder dữ liệu, `config.yml`, `~/.devmap/workspaces.yml`, module Git (JGit), lớp lọc bí mật, giao diện 3 cột, các lệnh CLI. Không giả định cấu trúc; bám theo code đang có.
3. Viết kế hoạch ngắn: file/class sẽ thêm hoặc sửa, cách migrate cấu hình cũ, danh sách test. Ghi quyết định vào `docs/DECISIONS.md`. Sau đó làm luôn, không chờ duyệt kế hoạch.

## 1. Mục tiêu
Cùng một PMO dùng DevMap trên máy A và máy B với cùng tài khoản Git. Mở máy nào cũng thấy đủ ticket, tài liệu, lịch sử, cấu hình mới nhất; có thể chuyển ticket đang làm dở từ máy này sang máy kia.

Cơ chế: **folder dữ liệu DevMap là một repo git private riêng** (ví dụ `devmap-data`), chứa các folder dự án `nyxhub/`, `lendbiz/`… DevMap tự commit, pull, push repo này.

## 2. Yêu cầu chức năng

| Mã | Yêu cầu |
|---|---|
| FR-46 | Khởi tạo đồng bộ: biến folder dữ liệu hiện có thành git repo và gắn remote (`devmap data init-sync <remote-url>`), hoặc trên máy mới clone repo dữ liệu về (`devmap data clone <remote-url> [path]`). Có màn hình tương ứng trong Cấu hình. |
| FR-47 | Tự commit khi ticket, tài liệu, cấu hình, rule, convention thay đổi. Gom thay đổi trong vài giây thành một commit; message dạng `data(<dự án>/<mã ticket>): <hành động>`, ví dụ `data(nyxhub/NYX-231): cập nhật analysis.md`. |
| FR-48 | Tự pull khi `devmap serve` khởi động và trước khi mở một dự án; tự push sau mỗi commit. Có nút "Đồng bộ ngay" và lệnh `devmap sync`. |
| FR-49 | Push repo dữ liệu được điều khiển bằng `sync.auto_push` trong cấu hình máy, **mặc định `true`** vì repo chỉ chứa tài liệu đã che bí mật. Khi `false`, mỗi lần push hiện thẻ xác nhận giống push code. Push **code** của các service vẫn luôn cần PMO xác nhận, không đổi. |
| FR-50 | Chỉ đồng bộ dữ liệu cần thiết. Sinh `.gitignore` cho repo dữ liệu loại trừ: graph/chỉ mục chữ ký, cache, phiên agent, file khóa tạm, log. Graph mỗi máy tự cập nhật theo git diff. |
| FR-51 | Tách đường dẫn theo máy. `config.yml` (được đồng bộ) chỉ ghi `name` và `remote` của mỗi repo/service, không ghi đường dẫn tuyệt đối. `~/.devmap/workspaces.yml` (riêng từng máy) ghi `data_dir` và map `remote → đường dẫn local`. Lần đầu mở dự án trên máy mới, với repo chưa có đường dẫn: cho chọn folder đã có hoặc clone giúp vào folder PMO chọn. |
| FR-52 | **Migrate tự động** cấu hình cũ: nếu `config.yml` đang có đường dẫn tuyệt đối, đọc remote của repo đó, ghi `remote` vào `config.yml`, chuyển đường dẫn sang `workspaces.yml`, sao lưu file cũ thành `config.yml.bak`. Migrate chạy một lần, có log. |
| FR-53 | Khóa mềm: khi `devmap serve` chạy, ghi `.devmap-lock.json` (tên máy, thời điểm, heartbeat mỗi 60 giây) vào repo dữ liệu và push. Máy khác mở lên thấy khóa còn hiệu lực thì cảnh báo "Đang mở ở máy A" và cho chọn: Chỉ xem / Chiếm quyền. Khóa quá 10 phút không heartbeat coi như hết hạn. Chiếm quyền thì máy A nhận cảnh báo ở lần đồng bộ sau và chuyển sang chỉ xem. |
| FR-54 | Xử lý xung đột: pull dùng rebase; nếu xung đột ở file Markdown/JSON của ticket, giữ cả hai bản (`<file>.conflict-<máy>.md`), đánh dấu ticket "Có xung đột", giao diện hiện so sánh hai bản cho PMO chọn hoặc ghép. Không bao giờ tự ghi đè mất dữ liệu. |
| FR-55 | Chuyển ticket sang máy khác: nút "Chuyển sang máy khác" trên ticket đang Dev triển khai. DevMap commit code dở trong worktree thành commit WIP mang mã ticket, **xin PMO xác nhận** rồi push branch của ticket ở mọi repo bị chạm, ghi `handoff` (máy, branch, commit) vào `task.md`. Trên máy B, mở ticket có `handoff` thì đề nghị fetch branch, tạo worktree và làm tiếp. Lệnh: `devmap ticket handoff <mã>`. |
| FR-56 | Trạng thái đồng bộ hiển thị cố định trên thanh trên cùng: Đã đồng bộ / Đang đồng bộ / Có thay đổi chưa đẩy / Lỗi / Có xung đột / Đang mở ở máy khác. Bấm vào xem chi tiết và lần đồng bộ gần nhất. |
| FR-57 | Xuất/nhập không dùng git: "Xuất dữ liệu dự án" ra file zip (đã loại trừ như FR-50), "Nhập" trên máy khác. Lệnh `devmap data export <dự án>` / `devmap data import <file.zip>`. |

## 3. Bảo mật
- Trước mỗi commit của repo dữ liệu, chạy lại lớp lọc bí mật trên các file thay đổi. Nếu phát hiện chuỗi giống bí mật chưa được che: chặn commit, báo PMO file và dòng, không ghi giá trị ra log.
- Token Git dùng cho repo dữ liệu lấy từ kho khóa của hệ điều hành; không ghi vào file, không đưa vào URL remote.
- Cảnh báo nếu remote của repo dữ liệu là public (khi kiểm tra được qua API của GitHub/GitLab); không chặn cứng.

## 4. Ràng buộc kỹ thuật
- Dùng JGit, tách thành module riêng (ví dụ package `sync`) theo đúng Controller → Service → Repository.
- Thao tác đồng bộ chạy nền, không chặn giao diện; tiến trình đẩy qua SSE như các tác vụ khác.
- Mất mạng: vẫn làm việc bình thường, commit cục bộ, đánh dấu "Có thay đổi chưa đẩy", tự thử lại.
- Không thay đổi hành vi của repo code các service ngoài phạm vi FR-55.
- Giao diện: HTML/CSS/JS thuần như hiện tại, tiếng Việt mặc định.

## 5. Kiểm thử bắt buộc
Dùng hai thư mục giả lập hai máy (`machineA/`, `machineB/` với `~/.devmap` riêng) và một bare repo cục bộ làm remote. Viết test tự động cho:

- [ ] **AC-13 Đồng bộ cơ bản**: tạo ticket và sửa analysis.md trên A → mở B thấy đủ; sửa trên B → A thấy sau khi đồng bộ.
- [ ] **AC-14 Đường dẫn khác máy**: A và B có đường dẫn repo code khác nhau; `config.yml` không chứa đường dẫn tuyệt đối; B được hỏi đường dẫn hoặc clone giúp.
- [ ] **AC-15 Migrate**: cấu hình cũ có đường dẫn tuyệt đối được chuyển đúng, có file `.bak`.
- [ ] **AC-16 Khóa mềm**: A đang chạy → B mở thấy cảnh báo; B chiếm quyền → A chuyển chỉ xem; khóa hết hạn sau 10 phút không heartbeat.
- [ ] **AC-17 Xung đột**: A và B cùng sửa một file khi offline → không mất nội dung nào, ticket hiện "Có xung đột".
- [ ] **AC-18 Chuyển ticket**: ticket đang làm dở trên A, chuyển sang B cần PMO xác nhận push; B tiếp tục trên đúng branch, đúng commit.
- [ ] **AC-19 Không đồng bộ thứ không cần**: graph, cache, phiên agent, khóa tạm không có trong repo dữ liệu.
- [ ] **AC-20 Bí mật**: cố ý ghi một token thật dạng giả vào history.md → commit bị chặn, log không chứa giá trị.

## 6. Bàn giao
- Code, test, cập nhật `docs/USER_GUIDE.md` (mục "Dùng trên nhiều máy") và `docs/TEST_PLAN.md`.
- Viết `docs/CHANGE-SYNC.md` tóm tắt FR-46 → FR-57, AC-13 → AC-20, thay đổi cấu hình và cách migrate, để PMO cập nhật vào tài liệu SRS gốc.
- Commit nhỏ theo bước, prefix `SYNC:`. Không push repo DevMap.
- Cuối cùng báo cáo: việc đã làm, kết quả test, AC đã đạt, điểm còn rủi ro.

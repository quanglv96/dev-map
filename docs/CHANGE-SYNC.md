# CHANGE-SYNC – Đồng bộ dữ liệu DevMap giữa nhiều máy

Tóm tắt phần bổ sung theo `docs/PROMPT_SYNC.md`, để PMO cập nhật vào `docs/SRS.md`. Quyết định kỹ thuật chi tiết: `docs/DECISIONS.md` D-52. Hướng dẫn dùng: `docs/USER_GUIDE.md` mục 9. Kiểm thử: `docs/TEST_PLAN.md` mục "Đồng bộ nhiều máy".

## 1. Thay đổi so với SRS hiện tại

- **Mục "Lưu trữ dữ liệu"**: folder dữ liệu gốc (cha của các folder dự án) có thể là một repo git private riêng do DevMap tự commit/pull/push. Thay cho D-47 "không khởi tạo git cho folder dữ liệu" khi PMO bật đồng bộ; không bật thì như cũ.
- **`config.yml` của dự án**: repo không còn `path`. Ví dụ mới:

  ```yaml
  project: nyxhub
  repos:
    - name: order-service
      remote: git@github.com:acme/order-service.git
      base_branch: test
  ```

- **"Dữ liệu trên máy, ngoài folder dữ liệu"**: `~/.devmap/workspaces.yml` thêm:

  ```yaml
  data_dir: D:/devmap-data          # folder dữ liệu gốc đang đồng bộ
  machine: may-van-phong            # tuỳ chọn, mặc định tên máy tính
  sync:
    auto_push: true                 # FR-49
  repos:                            # remote (chuẩn hoá) hoặc <dự án>/<repo> → đường dẫn trên máy này
    github.com/acme/order-service: D:/code/order-service
  projects:
    - name: nyxhub
      data_dir: D:/devmap-data/nyxhub
  ```

- **File mới trong folder dữ liệu gốc**: `.gitignore` (DevMap sinh), `.devmap-lock.json` (khoá mềm), `<tên>.conflict-<máy>.<đuôi>` (bản thứ hai khi xung đột, xoá khi PMO giải quyết).
- **task.md**: thêm `handoff` (máy, lúc, danh sách repo/branch/commit) khi ticket đang chờ máy khác làm tiếp.

## 2. Yêu cầu chức năng FR-46 → FR-57

| Mã | Yêu cầu | Đã làm |
| --- | --- | --- |
| FR-46 | Khởi tạo đồng bộ / clone trên máy mới | `devmap data init-sync <remote> [--dir]`, `devmap data clone <remote> [path]`; giao diện ở trang chủ và Cấu hình dự án ("Dùng trên nhiều máy"). Clone xong tự đăng ký mọi dự án. |
| FR-47 | Tự commit, gom vài giây, message `data(<dự án>/<mã>): <hành động>` | Luồng nền quét mỗi 3 giây, commit khi thay đổi đứng yên một nhịp. Message suy từ đường dẫn file. |
| FR-48 | Tự pull khi `serve` chạy và trước khi mở dự án; tự push sau commit; "Đồng bộ ngay", `devmap sync` | Có. Pull = fetch + rebase. Mở trang dự án gọi pull (bỏ qua nếu vừa pull trong 15 giây). |
| FR-49 | `sync.auto_push`, mặc định `true`; `false` thì thẻ xác nhận | Có. Khi `false` không push gì tự động (kể cả heartbeat khoá). Push code service không đổi. |
| FR-50 | Chỉ đồng bộ thứ cần thiết | `.gitignore`: `graph/`, `logs/`, `cache/`, `sessions/`, `*.tmp`, `*.lock`, `*.log`, `*.bak`. Phiên agent nằm ở `~/.devmap/sessions` (ngoài folder dữ liệu). |
| FR-51 | `config.yml` chỉ `name` + `remote`; đường dẫn theo máy; máy mới chọn folder hoặc clone | Có. Repo chưa có đường dẫn: thẻ trên trang tổng quan dự án (Chọn folder đã có… kiểm remote khớp / Clone vào folder…), API `POST /api/projects/{p}/repos/{repo}/locate|clone`. |
| FR-52 | Migrate tự động, `.bak`, chạy một lần, có log | Có, khi đọc config.yml còn `path`. |
| FR-53 | Khoá mềm, heartbeat 60 giây, hết hạn 10 phút, Chỉ xem / Chiếm quyền | Có. Chỉ xem: mọi request ghi (trừ `/api/sync/**`) trả 423, không tự commit. |
| FR-54 | Pull rebase; xung đột giữ cả hai bản, ticket "Có xung đột", giao diện so sánh | Có, cho mọi file (không chỉ Markdown/JSON của ticket); khoá và `.gitignore` lấy bản remote. Không bao giờ ghi đè; rebase kẹt thì huỷ. |
| FR-55 | Chuyển ticket sang máy khác | Có: nút "Chuyển sang máy khác" (ticket Dev triển khai), commit WIP, PMO xác nhận push, `handoff` trong task.md; máy kia "Tiếp tục trên máy này". `devmap ticket handoff <mã>`, `devmap ticket resume <mã>`. |
| FR-56 | Trạng thái đồng bộ cố định trên thanh trên cùng | Có, ở trang chủ và trang dự án; cập nhật qua SSE `sync`. |
| FR-57 | Xuất/nhập zip | `devmap data export <dự án>`, `devmap data import <file.zip>`; giao diện: Cấu hình dự án → Xuất, trang chủ → Nhập. |

Bảo mật: lọc bí mật chạy lại từng dòng của file đổi trước mỗi commit (chặn, báo file:dòng, không log giá trị); token Git do git credential manager của hệ điều hành giữ (fetch/push/clone qua git CLI), URL remote có mật khẩu/token bị từ chối; cảnh báo khi GitHub/GitLab cho biết remote là public.

## 3. Tiêu chí nghiệm thu AC-13 → AC-20

| AC | Nội dung | Kiểm thử tự động |
| --- | --- | --- |
| AC-13 | Tạo ticket, sửa analysis.md trên A → B thấy; sửa trên B → A thấy | `SyncServiceTest.ticketAndDocumentsTravelBothWays` |
| AC-14 | Đường dẫn repo khác nhau giữa hai máy; config.yml không có đường dẫn tuyệt đối; B chọn folder hoặc clone | `ProjectServiceTest.otherMachinePicksOrClonesMissingRepos` |
| AC-15 | Migrate cấu hình cũ, có `.bak` | `ProjectServiceTest.legacyConfigWithAbsolutePathsIsMigratedOnce` |
| AC-16 | Khoá: cảnh báo, chiếm quyền, hết hạn 10 phút | `SyncServiceTest.softLockWarnsTakesOverAndExpires`, `WebTest.viewOnlyRefusesWrites` |
| AC-17 | Cùng sửa khi offline → không mất nội dung, ticket "Có xung đột" | `SyncServiceTest.offlineEditsOfTheSameFileKeepBothVersions` |
| AC-18 | Chuyển ticket cần PMO xác nhận push; B đúng branch, đúng commit | `TicketGitServiceTest.handoffPushesWipAndOtherMachineContinuesOnTheSameCommit` |
| AC-19 | Graph, cache, phiên agent, khoá tạm không có trong repo dữ liệu | `SyncServiceTest.rebuildableAndMachineFilesAreNotSynced` |
| AC-20 | Token thật dạng giả trong history.md → chặn commit, log không chứa giá trị | `SyncServiceTest.secretInAChangedFileBlocksTheCommit` |

## 4. Cách migrate

Không cần làm gì: lần đầu DevMap đọc một `config.yml` còn `path` (khi mở dự án, `devmap workspace list`, `serve`…):

1. Đọc `origin` của từng repo, ghi vào `remote`.
2. Chuyển `path` sang `repos:` của `~/.devmap/workspaces.yml` trên máy đó.
3. Sao lưu bản cũ thành `config.yml.bak` (cạnh `config.yml`, không đồng bộ), ghi lại `config.yml` không còn `path`, log một dòng `Đã migrate …`.

Sau đó bật đồng bộ (FR-46) như bình thường. Máy khác clone về sẽ được hỏi đường dẫn cho từng repo.

## 5. Điểm PMO nên chốt khi cập nhật SRS

- `.devmap-lock.json` có trong repo dữ liệu (FR-53) nên mỗi phút một commit heartbeat khi `serve` chạy (~60 commit/giờ). Chấp nhận, hay đổi sang heartbeat thưa hơn / khoá ngoài git?
- Tắt `serve` không nhả khoá: máy kia phải chờ hết hạn 10 phút hoặc bấm Chiếm quyền.
- `chat.json` của ticket được đồng bộ (đã che bí mật, là lịch sử hỏi đáp). Nếu muốn coi là "phiên agent" thì thêm vào `.gitignore`.
- `devmap ticket handoff <mã>` dùng `--project` khi hai dự án trùng mã ticket.

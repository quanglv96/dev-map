# DECISIONS – các quyết định khi xây DevMap

Mỗi mục: quyết định, lý do, cách đổi lại nếu PMO muốn.

## D-01 Chia 6 phase (SRS thiếu nội dung lộ trình)

Mục "Lộ trình triển khai" của SRS v0.3 chỉ có ảnh nhúng (`[embedded content: lộ trình · 6 phase…]`), không có chữ.
Từ câu "luồng ticket, graph và rule phải chạy được bằng tay trước, rồi mới giao cho agent ở phase 5", tạm chia như sau.
**Cần PMO xác nhận hoặc gửi lại nội dung ảnh.**

| Phase | Nội dung | FR/NFR chính |
| --- | --- | --- |
| 1 Nền tảng & dự án | `devmap.jar` (CLI + server + UI), init/workspace/serve/doctor, folder dữ liệu, repo + nhánh nền, phát hiện service, 127.0.0.1 + token, khung UI 3 cột, vi/en, Checkstyle + ESLint | FR-01→04, FR-42 (một phần), NFR-08, NFR-13, NFR-15 |
| 2 Ticket & tài liệu | tạo ticket, mã, trạng thái, version + diff tài liệu, mở lại, lớp lọc bí mật cho tài liệu, spec.json, SSE, trang tổng quan đầy đủ | FR-05→11, FR-35/36, FR-41, FR-44 |
| 3 Graph | chỉ mục chữ ký (JavaParser, tree-sitter, OpenAPI, proto), cập nhật tăng dần theo git diff, edge tay, màn hình Graph | FR-17, FR-19→23 |
| 4 Rule, convention, git | `.devrules.yml`, quality gate, kiểm tách lớp, pull nhánh nền, worktree, commit, xác nhận push | FR-24, 26, 27, 29→33, NFR-03, NFR-07 |
| 5 Agent | AgentProvider/ClaudeCodeProvider, MCP server nội bộ, SA/Dev, chatbox stream, verify.md, token | FR-12→16, 18, 25, 28, 34, 37→40, 43, 45 |
| 6 Hoàn thiện & nghiệm thu | thông báo, tiêu đề tab, nhật ký, giới hạn agent, chạy đủ AC-01→12 trên shop và dự án thật | NFR-10→12, toàn bộ AC |

## D-02 Môi trường build

Dùng JDK 17 có sẵn trên máy (xem D-12) và Maven Wrapper (`./mvnw`, Maven 3.9.11).
`mvn verify` tự tải Node vào `target/node` để chạy ESLint, không cần Node cài sẵn.

## D-03 Token kết nối (NFR-08)

`devmap serve` in ra `http://localhost:7070/?token=…`. Lần đầu mở, token đổi thành cookie `HttpOnly; SameSite=Strict` rồi redirect bỏ token khỏi URL;
các tab khác (`/nyxhub`, `/lendbiz`) dùng cookie đó. Client gọi API có thể dùng header `X-DevMap-Token`.
Ngoài token, server từ chối địa chỉ không phải loopback và Host khác `localhost/127.0.0.1` (chống DNS rebinding).

## D-04 Tên dự án và URL

Tên dự án là slug `[a-z0-9-]` (tối đa 40) để dùng thẳng làm URL `/<tên>`; cấm `api`, `assets`, `vendor`, `error`.
Folder dữ liệu của dự án là `<folder PMO chọn>/<tên>` như sơ đồ trong SRS.

## D-05 Danh sách service lưu trong config.yml

Service phát hiện theo file build (pom.xml, build.gradle, package.json, go.mod, pyproject.toml, requirements.txt), sâu tối đa 3 tầng,
bỏ qua `src`, `node_modules`, `target`…; thư mục chứa service khác (parent pom, monorepo) không tính là service.
Kết quả ghi vào `repos[].services` trong config.yml; PMO sửa trong "Cấu hình dự án". Bộ phát hiện chỉ xem tên file, không đọc nội dung.
`project.md`: DevMap chỉ ghi lại đoạn giữa `<!-- devmap:repos -->`, phần chữ khác thuộc PMO.

## D-06 `init` và `workspace add`

Cả hai cùng tham số. Folder đã có `config.yml` (ví dụ chép từ máy khác) thì chỉ đăng ký lại, không ghi đè.
`workspace remove` chỉ bỏ đăng ký, không xoá folder dữ liệu. `--git` khởi tạo folder dữ liệu thành git repo (mặc định không, CLAUDE.md §5.4).

## D-07 `ticket_code`

`manual` (mặc định, như SRS): PMO nhập, để trống thì tự sinh. Thêm `auto`: luôn tự sinh. `ticket_pattern` là regex tuỳ chọn (CLAUDE.md §5.2), dùng ở Phase 2.

## D-08 Nhánh nền phải tồn tại

Khi lưu cấu hình, mỗi repo phải là git repo và nhánh nền phải có ở local hoặc `origin/` (kiểm bằng JGit). Sai thì báo lỗi, không lưu.

## D-09 Ngôn ngữ CLI

Help của CLI bằng tiếng Anh; thông báo kết quả/lỗi bằng tiếng Việt (giống giao diện). stdout ép UTF-8 để không lỗi dấu trên Windows.

## D-10 Repo mẫu

Mã nguồn `samples/shop/<service>` được repo DevMap theo dõi như file thường (repo git lồng nhau sẽ thành gitlink, mất nội dung).
3 repo git riêng (nhánh `main`, `test`) được sinh bằng `samples/shop/init-repos.sh` vào `samples/shop/repos/` (gitignore);
test JUnit dùng `SampleRepos` (JGit) làm việc tương tự trong thư mục tạm.

## D-11 Tách lớp trong code DevMap

Checkstyle `ImportControl` cấm package `web` và `cli` import `*Repository`: lớp giao diện/CLI chỉ gọi Service.

## D-12 Java 17 thay cho Java 21 (khác SRS)

SRS (Kiến trúc, Giả định) và CLAUDE.md ghi Java 21. PMO yêu cầu dùng JDK 17 (29/10/2026), nên `java.version` là 17 cho DevMap và repo mẫu;
`devmap doctor` yêu cầu Java 17+. Spring Boot 3 và JGit 7 đều chạy trên 17. Không dùng API chỉ có từ Java 18+.
Muốn quay lại 21: đổi `java.version` trong `pom.xml` và ngưỡng trong `DoctorCommand`.

## D-13 Tám trạng thái ticket (SRS chỉ có ảnh)

Sơ đồ luồng trong SRS là ảnh nhúng. Suy ra từ chữ: `new` → `sa_analysis` → `awaiting_approval` → `ready` → `in_dev` → `sa_verify` → `awaiting_push` → `done`.
Quay lại: `awaiting_approval → sa_analysis` (PMO yêu cầu phân tích lại), `sa_verify → in_dev` (SA trả về Dev), `awaiting_push → in_dev`.
Hai cổng chỉ PMO: `awaiting_approval → ready` (Chốt giải pháp, bắt buộc có plan.md) và `awaiting_push → done` (Xác nhận push). Kiểm ở `TaskService` (NFR-03), không ở giao diện.
Phase 2 chưa có agent nên các bước của SA/Dev được bấm tay; nút "Xác nhận push" mới chỉ chuyển trạng thái, việc push thật gắn vào ở Phase 4.
**Cần PMO xác nhận** hoặc gửi lại nội dung ảnh.

## D-14 Lớp che bí mật

Module `secret`: luật dựng sẵn (khoá riêng PEM, connection string, URL có user:pass, `key=value` với tên chứa password/secret/token/api_key…, Bearer, token có tiền tố
ghp_/sk_/AKIA/xox…/JWT, chuỗi ngẫu nhiên ≥32 ký tự lẫn hoa-thường-số, IPv4). Giá trị bị thay bằng `[MASKED_IP]`, `[MASKED_SECRET]`, `[MASKED_TOKEN]`, `[MASKED_CONN]`, `[MASKED_KEY]`.
Mặc định giữ nguyên `127.0.0.1`, `0.0.0.0`, `${placeholder}`. Dự án thêm mẫu/giá trị giữ nguyên trong `config.yml` (`secrets.patterns`, `secrets.allow`) hoặc màn hình Cấu hình.
Ưu tiên che thừa hơn lọt: số phiên bản dạng `1.2.3.4` cũng bị che như IP. Khoá chỉ gồm hex không có tên biến gợi ý thì có thể lọt (đã ghi `ponytail:` trong code).

## D-15 task.md cũng được che

SRS vừa nói "nguyên văn lưu ở task.md" vừa nói "luôn che trước khi ghi vào bất kỳ file nào". Chọn che: task.md giữ nguyên câu chữ của PMO nhưng bí mật đã bị thay.
Giao diện/CLI báo số giá trị đã che khi tạo ticket.

## D-16 Làm sạch cục bộ → spec.json

Luật thuần (không NLP nặng): bỏ cụm xã giao tiếng Việt/Anh (chào, nhờ em, giúp, nhé, ạ, cảm ơn, please…), tách câu có từ ràng buộc (không được, phải, giữ nguyên, must…) vào `constraints`,
phần còn lại là `summary`. `target_modules` = tên service/repo xuất hiện trong yêu cầu (không phân biệt hoa thường, `-`/`_`/khoảng trắng). Không tìm được thì để rỗng (CLAUDE.md §5.10).
Tiêu đề trống thì lấy câu đầu của summary. Mở lại ticket ghi đè spec.json với `action_type` `REOPEN_BUG`/`ADD_LOGIC` và `source` là file trong `bugs/`.

## D-17 Version tài liệu, hàng đợi, bug

- Mỗi lần lưu analysis.md/plan.md có nội dung khác: bản lưu ở `versions/<doc>.v<N>.md`, `version` trong task.md tăng 1. Diff dạng unified (java-diff-utils).
- PMO sửa tay được analysis.md, plan.md, summary.md; các tài liệu khác do agent ghi (Phase 4–5).
- Hàng đợi (FR-07): `tasks/queue.yml`, gồm các ticket `ready` theo thứ tự PMO xếp; vào `ready` thì thêm cuối, rời `ready` thì bỏ ra.
- Mở lại: `bugs/BUG-NN.md` (Báo bug) hoặc `bugs/LOGIC-NN.md` (Bổ sung logic), cùng mã ticket, `reopened` tăng 1.
- `history.md` ghi mọi thao tác có giờ và người làm (PMO/SA/DEV) – phần đầu của NFR-10.

## D-18 Tiếng Việt trên dòng lệnh Windows

Windows đưa tham số dòng lệnh cho Java theo code page ANSI, nên tiếng Việt trong `--request` có thể thành `?`.
`devmap task new` nhận thêm `--request-file <file UTF-8>` hoặc đọc stdin khi không có `--request`. Giao diện web không bị ảnh hưởng.

## D-19 SSE

`GET /api/events[?project=]` phát sự kiện `task` mỗi khi ticket đổi; trang dự án và trang chủ tự làm mới, không làm mới khi PMO đang mở trình soạn thảo.

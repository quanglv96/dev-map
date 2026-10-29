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

Máy dev chỉ có JDK 17 nên đã cài Temurin JDK 21 vào `~/.jdks/jdk-21.0.12.1+1`; dùng Maven Wrapper (`./mvnw`, Maven 3.9.11).
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

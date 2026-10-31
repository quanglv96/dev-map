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
| 6 Hoàn thiện & nghiệm thu | thông báo, tiêu đề tab, nhật ký, giới hạn agent, chạy hàng đợi, chạy đủ AC-01→12 trên shop (dự án thật: PMO chạy) | NFR-10→12, toàn bộ AC |

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
`workspace remove` chỉ bỏ đăng ký, không xoá folder dữ liệu. Bỏ tuỳ chọn `--git` (xem D-47).

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

## D-20 Chỉ mục đọc từ commit của nhánh nền, không đọc working tree

Indexer đọc file qua JGit từ cây commit của `origin/<nhánh nền>` (sau `git fetch`), nếu không có remote thì `<nhánh nền>` local.
Vì vậy việc PMO đang checkout nhánh nào hay có thay đổi chưa commit không ảnh hưởng chỉ mục. `git fetch` chạy bằng git CLI để dùng credential manager
sẵn có của máy (JGit không đọc được kho khoá Windows); fetch lỗi (mất mạng) thì vẫn index trên dữ liệu đã có và ghi chú lại.
Phase 3 chỉ **fetch**, không đổi working tree của PMO; pull vào worktree của ticket thuộc Phase 4.

## D-21 Khi nào chỉ mục được cập nhật (FR-17, FR-21, AIR-05/06)

- `devmap serve` không quét gì.
- Dự án mới: graph chỉ có node service; màn hình Graph hiện nút "Quét toàn bộ dự án" (CLAUDE.md §5.8). Trước khi chạy, DevMap đếm và báo số file sẽ đọc (NFR-05).
- "Cập nhật theo git diff" / `devmap index <dự án>`: fetch, diff commit đã index với commit mới nhất của nhánh nền, chỉ đọc file thêm/sửa, bỏ file xoá.
  Phase 4–5 sẽ gọi cùng hàm này sau mỗi lần pull.
- Lịch sử nhánh bị viết lại (force-push) thì không diff được: báo PMO quét lại toàn bộ.
- `scan_full_project` trong config.yml vẫn luôn là `false`; quét theo câu hỏi (AC-09) thuộc Phase 5.

## D-22 Nội dung chỉ mục và graph

- `graph/<repo>.json`: theo từng file, chỉ có chữ ký class/method/function (kèm dòng bắt đầu/kết thúc), endpoint, lời gọi HTTP, topic, bảng, tham chiếu theo tên.
  Không có thân hàm. Mọi chuỗi đi qua lớp che bí mật trước khi ghi (FR-36).
- Không bao giờ chọn đọc: file bí mật (`SecretFiles`, dùng lại cho FR-34 ở Phase 5), test, `node_modules`/`target`/`build`…, file > 1 MB.
- Java: JavaParser, không dùng symbol solver; lời gọi được nối qua kiểu của field/tham số (đủ cho Spring inject qua constructor).
  Nhận diện Spring MVC, Feign, RestClient/RestTemplate, Kafka/Rabbit/JMS, JPA entity/repository.
- TS/JS/Python/Go: tree-sitter (binding `io.github.bonede`, có sẵn bản native Windows/macOS/Linux). Nhận diện route kiểu Express, FastAPI/Flask,
  net/http/gin và lời gọi axios/fetch/requests/http.Get. NestJS decorator và URL ghép động chưa nhận diện (edge tay hoặc agent SA bù).
- OpenAPI: swagger-parser; `.proto`: đọc `service`/`rpc` bằng regex. Spring config (`application*.yml/properties`), docker-compose.
- Graph không lưu ra file; dựng lại trong bộ nhớ từ chỉ mục + `manual-edges.json` (vài trăm ms), tự dựng lại khi cấu hình dự án đổi.
- Độ tin cậy edge: `high` khi khớp chính xác (method đã biết, host trùng tên service và path khớp API); `medium` khi chỉ khớp một phía
  (chỉ host, hoặc chỉ path và duy nhất một API khớp); `low` khi đoán (docker-compose `depends_on`, path khớp nhiều API).
- Truy vấn lân cận luôn giới hạn tối đa 3 tầng (AIR-09) và 300 node.

## D-23 Edge tay nằm ngoài graph/

`manual-edges.json` ở gốc folder dữ liệu dự án, không nằm trong `graph/`: `graph/` là cache tạo lại được, xoá đi không mất edge của PMO.
Edge tay chỉ nối được giữa hai node đang có; nếu sau này node biến mất, edge vẫn giữ trong file nhưng không hiển thị.

## D-24 Kích thước devmap.jar

Thêm tree-sitter (thư viện native cho 3 hệ điều hành × 4 ngôn ngữ) và swagger-parser làm `devmap.jar` lên khoảng 40 MB. Chấp nhận vì vẫn là một file chạy offline.

## D-25 File rule và kế thừa (FR-29)

- Rule nằm trong folder dữ liệu, không ghi vào repo của PMO: `rules/_default.devrules.yml` (mặc định dự án) và `rules/<service>.devrules.yml`.
- Thứ tự: rule dựng sẵn (`src/main/resources/rules/presets.yml`) ← preset ← mặc định dự án ← rule service. Preset lấy từ file rule, không có thì theo loại service do bộ phát hiện tìm ra (maven, gradle, node, python, go; không rõ thì `none`).
- Giao diện luôn gửi rule đầy đủ; server chỉ lưu phần khác cấp trên, nên file rule ngắn và đổi mặc định dự án vẫn áp dụng cho service chưa ghi đè.
- Nhóm `git` chỉ có ở cấp dự án (một repo có thể chứa nhiều service nhưng chỉ có một lịch sử). `quality` và `scope` theo service, đường dẫn tính từ thư mục service.
- Đường dẫn trong rule là glob tự viết (`rule/Glob`), dùng `/` trên mọi hệ điều hành; mẫu không có `/` khớp tên file ở mọi thư mục.

## D-26 Rule git không tắt được (FR-30, AC-05)

- Push chỉ chạy qua `TicketGitService.confirmPush` với Actor PMO, ticket đang "Chờ PMO xác nhận"; kiểm ở server.
- Lệnh push luôn là `git push origin <sha>:refs/heads/<branch-ticket>`: không có `--force`, refspec không có `+`, nên không thể force-push.
- Branch được bảo vệ = danh sách cấu hình ∪ nhánh nền của mọi repo (không xoá được). Branch ticket khớp mẫu bảo vệ thì không bắt đầu triển khai và không push.

## D-27 Worktree và pull nhánh nền (FR-24, NFR-07)

- Worktree ở `~/.devmap/worktrees/<dự án>/<ticket>/<repo>`: ngoài folder dữ liệu (folder dữ liệu chép sang máy khác được) và ngoài repo của PMO; checkout của PMO không bị đổi.
- Worktree, merge, commit, push chạy bằng git CLI (JGit không hỗ trợ worktree; CLI dùng credential và hook sẵn có của máy).
- "Bắt đầu triển khai" và "Pull nhánh nền": `git fetch`, tạo worktree trên branch ticket từ `origin/<nhánh nền>` (hoặc nhánh local nếu không có remote), rồi merge nhánh nền mới nhất vào branch ticket. Xung đột thì huỷ merge và báo; worktree còn thay đổi chưa commit thì không pull.
- Sau mỗi lần pull, DevMap phát sự kiện để Graph cập nhật chỉ mục theo git diff ở nền (chỉ khi dự án đã có chỉ mục, D-21).
- Worktree không tự xoá khi ticket Done (mở lại dùng tiếp). `ponytail:` thêm dọn worktree khi ổ đĩa thành vấn đề.

## D-28 dev.json và hai cổng cuối

- `dev.json` trong folder ticket: mỗi repo có worktree, branch, commit nền, commit đã qua quality gate (`gate_head`), commit đã push (`pushed_head`), danh sách file PMO đã duyệt.
- `in_dev → sa_verify` chỉ được khi mọi repo của ticket có gate đạt **trên commit hiện tại** và không còn thay đổi chưa commit (AIR-13). Ticket không có repo nào thì không kiểm (luồng tay của Phase 2).
- `awaiting_push → done` chỉ được khi mọi repo có commit mới đã push đúng commit đã qua gate. Commit thêm sau khi gate đạt thì push bị từ chối, phải trả về Dev.
- PMO chọn repo của ticket ở cột phải (trước đó lấy từ module đích trong spec.json).

## D-29 Phạm vi commit (FR-32, FR-34)

- File khớp `forbidden_paths`, `schema_paths`, `dependency_files` cần PMO duyệt từng file trước khi commit (thẻ xác nhận ở giao diện); quyền duyệt chỉ của PMO, hết hiệu lực sau commit đó.
- File bí mật (`SecretFiles`: `.env*`, khoá, credentials…) không bao giờ được commit, kể cả PMO.
- Mọi commit qua DevMap mang mã ticket theo `commit_template` (mặc định `{code}: {message}`); mẫu bắt buộc có `{code}`.
- `changes.md` sinh lại từ git sau mỗi bước (branch, commit, file sửa, file chưa commit, trạng thái push), nên commit PMO tự làm trong worktree cũng hiện ra (một phần FR-28; agent Dev đọc lại ở Phase 5).

## D-30 Quality gate (FR-31, AIR-12/13, CLAUDE.md §5.9)

- Theo từng service: kiểm tách lớp có sẵn, rồi lệnh phân tích tĩnh, unit test, integration test đã cấu hình (chạy qua `cmd /c` hoặc `sh -c` trong thư mục service của worktree). Chạy đủ mọi bước để báo cáo đầy đủ; bước bật mà chưa có lệnh thì tính là không đạt.
- Kiểm tách lớp (JavaParser, chỉ Java): controller (`@Controller`, `@RestController`, tên `*Controller`) nhắc tới kiểu repository (`@Repository`, `*Repository`, `*Dao`, kế thừa Spring Data) là vi phạm. Ngôn ngữ khác dựa vào lệnh lint của dự án.
- Preset chỉ là gợi ý lệnh, cần công cụ có trên máy (ví dụ `mvn` trên PATH; dự án có Maven Wrapper thì đổi thành `mvnw.cmd`/`./mvnw`). SonarQube server: thêm lệnh `sonar-scanner` vào danh sách phân tích tĩnh nếu PMO muốn.
- Kết quả ghi `tests.md` (60 dòng cuối của mỗi bước lỗi, đã che bí mật). `devmap rules check` chạy cùng gate trên repo hoặc `--path` worktree.

## D-31 Coding convention (FR-33)

- Thu thập từ cây commit của nhánh nền (như D-20), không đọc file bí mật, không gọi LLM: số file theo ngôn ngữ, thụt lề, độ dài dòng (99%), cách inject, tách package, nháy/chấm phẩy JS/TS, thư viện test, và trích file cấu hình lint/formatter.
- Chỉ phần giữa `<!-- devmap:collected -->` được thay khi thu thập lại; phần PMO viết (chuẩn riêng, CLAUDE.md §5.6) giữ nguyên. Dòng `> Trạng thái:` ghi bản nháp/đã duyệt; mọi lần sửa hay thu thập đưa về bản nháp, PMO bấm "Duyệt".

## D-32 Actor DEVMAP

Tài liệu do chính công cụ sinh (`tests.md`, `changes.md`) ghi với Actor `DEVMAP` trong history.md, để phân biệt với thao tác của PMO, SA, Dev.

## D-33 Agent không có công cụ có sẵn nào, kể cả Dev (khác CLAUDE.md §4)

CLAUDE.md §4 gợi ý cho agent Dev dùng công cụ sửa file của Claude Code và chặn file bí mật bằng hook. Chọn cách chặt hơn: cả SA và Dev chạy với `--tools ""`
(không Read/Edit/Bash), mọi thao tác đi qua MCP server của DevMap. Dev đọc code bằng `get_snippet` (tối đa 80 dòng, từ worktree) và sửa bằng `edit_file`/`write_file`.
Lý do: chặn file bí mật ở một chỗ trong tiến trình DevMap (FR-34, NFR-01), không có shell nên AC-06 "kể cả qua lệnh shell" không có đường vòng,
không phải chạy một JVM cho mỗi lần hook, và Dev cũng không bao giờ nhận nguyên file (AIR-10). Test chạy bằng lệnh PMO cấu hình trong rule (`run_quality_gate`), không phải lệnh agent tự gõ.
Đoạn code chứa giá trị đã che (`[MASKED_…]`) thì agent không sửa được (PMO tự sửa). Muốn quay lại cách của §4: thêm `Read,Edit,Write` vào `--tools` trong `ClaudeCodeProvider` và một hook PreToolUse.

## D-34 MCP server nội bộ

- JSON-RPC 2.0 qua HTTP POST `/mcp` ngay trong `devmap serve` (MCP "streamable HTTP", trả JSON, không stream từ server; GET trả 405). Các method: `initialize`, `ping`, `tools/list`, `tools/call`.
- Mỗi phiên agent có một token ngẫu nhiên (header `X-DevMap-Session`) gắn với đúng dự án, ticket, vai. Token này không mở được API của PMO, run token cũng không mở được `/mcp`; vẫn chỉ nhận 127.0.0.1 (NFR-06, NFR-08).
- Công cụ SA: `get_spec`, `get_ticket_docs`, `search_graph`, `get_signatures`, `get_snippet`, `verify_facts`, `git_diff`, `write_ticket_doc`, `set_status`. Dev thêm `write_file`, `edit_file`, `delete_file` (D-45), `run_quality_gate`, `commit`, bỏ `verify_facts`.
- Không công cụ nào trả `task.md` hay `bugs/` (nguyên văn của PMO): agent chỉ thấy spec.json (FR-44, AC-12). `get_snippet` theo node chỉ nhận method/function; class thì chỉ có chữ ký.
- Agent chỉ chạy khi có `devmap serve`. CLI có `devmap prompt <dự án> <ticket> [--role] [--question]` để xem trước đúng nội dung một phiên sẽ nhận, không gọi LLM (FR-42).

## D-35 Gọi Claude Code

Đã kiểm bằng `claude --help` (2.1.312) và chạy thử: `claude -p --output-format stream-json --verbose --no-session-persistence --setting-sources project --strict-mcp-config
--mcp-config <mcp.json> --system-prompt-file <system.md> --tools "" --allowed-tools mcp__devmap --permission-mode dontAsk [--model …]`, prompt qua stdin.
Thư mục làm việc là một folder rỗng `~/.devmap/sessions/<dự án>/<ticket>/<n>/`, xoá ngay sau phiên; `--setting-sources project` trong folder rỗng nghĩa là không nạp hook, plugin, MCP của người dùng.
Cấu hình (`-D` hoặc biến môi trường Spring): `devmap.agent.command` (mặc định `claude.cmd` trên Windows, `claude` nơi khác), `devmap.agent.model`, `devmap.agent.timeout-minutes` (30),
`devmap.agent.max-concurrent` (2, NFR-12: phiên sau xếp hàng). Thay agent khác: viết một `AgentProvider` mới (NFR-14).

## D-36 Ngữ cảnh mỗi phiên (FR-37, AIR-09 → AIR-11, 5 lớp tối ưu token)

- System prompt tĩnh theo dự án và vai: hai khối nguyên văn trong SRS (`prompts/execution.md`, `prompts/workflow.md`), `prompts/sa.md` hoặc `prompts/dev.md`, quy tắc tách lớp, mẫu commit, branch bảo vệ, conventions.md.
  Giống hệt nhau giữa các phiên nên được cache (giao diện hiện "cache đọc"). Chạy thử thật: 40.159/53.919 token đầu vào đọc từ cache ngay ở phiên đầu.
- Prompt: spec.json, summary.md, analysis.md, plan.md (Dev thêm verify.md, changes.md; SA kiểm chứng thêm changes.md), graph mức service quanh service của ticket (2 tầng) và chữ ký API của chúng, việc DevMap đã làm trước phiên (pull, quét, thay đổi PMO tự làm), câu hỏi của PMO sau khi che bí mật và làm sạch bằng `RequestCleaner`.
- Không bao giờ có: lịch sử chat, task.md, bugs/, nguyên file code.
- FR-38: nếu agent không ghi summary.md, DevMap tự thêm câu hỏi + câu trả lời của phiên vào summary.md để phiên sau vẫn có ngữ cảnh.

## D-37 Vai, trạng thái và tự chạy kiểm chứng

- Không chọn vai thì: Sẵn sàng / Đang triển khai → Dev, còn lại → SA. Dev chỉ chạy ở hai trạng thái đó. SA ở "SA kiểm chứng" luôn là phiên kiểm chứng.
- Trước phiên: SA ở "Mới" → "SA phân tích"; SA luôn fetch và cập nhật chỉ mục theo git diff (AIR-04/06); Dev luôn gọi `TicketGitService.start` (tạo worktree hoặc merge nhánh nền mới nhất, FR-24).
- Agent chỉ chuyển được: SA `awaiting_approval` (cần analysis.md + plan.md), `in_dev`, `awaiting_push`; Dev `sa_verify` (vẫn qua kiểm gate của D-28). Chốt giải pháp và push vẫn chỉ PMO (NFR-03).
- AIR-03: phiên Dev kết thúc ở "SA kiểm chứng" thì DevMap tự mở một phiên SA kiểm chứng. SA trả về Dev thì không tự chạy lại Dev (tránh vòng lặp tốn token); PMO hỏi Dev.

## D-38 Kiểm chứng (FR-43, AIR-14, FR-18, FR-11, AC-11)

- `verify_facts` tính không cần LLM: file đổi (đã và chưa commit); file không phải test mà tên (bỏ đuôi) không xuất hiện trong analysis.md/plan.md → "có thể lệch phạm vi"; chữ ký class/method trước/sau (trích lại bằng indexer từ commit nền và worktree) bị đổi/xoá → nơi gọi 1 tầng trong graph, và nếu nơi gọi là API thì cả service gọi API đó qua HTTP; endpoint bị xoá/đổi → service gọi (FR-18).
- SA chỉ quyết được sau khi ghi verify.md trong chính phiên kiểm chứng. Muốn "đạt" (`awaiting_push`) thì verify.md phải nêu đích danh mọi mục bị gắn cờ; ticket mở lại thì analysis.md phải có mã mục bugs/ mới nhất (ví dụ BUG-01) (FR-11). Không đạt thì DevMap từ chối, PMO không nhận "chờ xác nhận".
- Heuristic tên file là gần đúng: plan.md phải nêu tên file/class sẽ sửa (prompt SA yêu cầu). File test luôn được coi là trong phạm vi (FR-25).

## D-39 Chat, token và xoá ngữ cảnh (FR-39, FR-40, FR-45)

- `chat.json` trong folder ticket: tin PMO, tin agent (vai, ngữ cảnh đã dùng, các bước, câu trả lời, token). Chỉ để PMO đọc lại; giữ lại khi Done như tài liệu. Mỗi bước được phát qua SSE `agent`.
- Token mỗi câu = vào (input + cache đọc + cache ghi) + ra, lấy từ dòng `result` của Claude Code; cộng dồn theo ticket và dự án từ các chat.json.
- Nhật ký đầu vào của mỗi phiên (system prompt, prompt, mọi kết quả công cụ, đã che) ở `~/.devmap/sessions/<dự án>/<ticket>/<n>.log`, xem được bằng nút "Đầu vào agent" (AC-10, AC-12). Ticket sang Done thì xoá cả thư mục đó (FR-45); mở lại thì ngữ cảnh dựng lại từ tài liệu.
- FR-28: cuối mỗi phiên Dev lưu commit hiện tại của từng worktree; phiên Dev sau thấy commit mới hoặc file chưa commit không do Dev → báo trong ngữ cảnh, ghi history.md, sinh lại changes.md.

## D-40 Che bí mật: chuỗi ghép từ không phải token

Luật "chuỗi ngẫu nhiên ≥ 32 ký tự" (D-14) che cả tên branch (`feature/NYX-9-them-hoan-tien-…`) và đường dẫn (`db/migration/V2__add_refund_column.sql`), phát hiện khi chạy thử thật.
Riêng luật đó bỏ qua giá trị gồm ≥ 3 đoạn nối bằng `/ - _ +` mà mọi đoạn là từ thường, Từ Hoa đầu, chữ HOA ≤ 10 ký tự, số, hoặc ≤ 3 ký tự. Token ngẫu nhiên có đoạn trộn hoa-thường-số nên vẫn bị che; các luật khác (IP, key=value, tiền tố…) không đổi.

## D-41 Nhật ký hành động (NFR-10)

- Mỗi dự án một file `<folder dữ liệu>/logs/audit.jsonl`, mỗi dòng một JSON: `at`, `actor` (PMO/SA/DEV/DEVMAP), `ticket`, `repo`, `service`, `rule`, `action`. Nội dung đã qua bộ che bí mật. Xem ở tab "Nhật ký" (lọc theo ticket) hoặc `GET /api/projects/<dự án>/audit`.
- Ba nguồn: (1) mọi dòng history.md của ticket (tạo, chuyển trạng thái, pull, commit, gate, push, duyệt file…) kèm repo và rule áp dụng; (2) mọi lời gọi công cụ MCP của agent, service suy ra từ đường dẫn file (`RuleService.serviceOf`), không ghi nội dung file/đoạn sửa; (3) mọi POST/PUT/DELETE thành công của PMO ngoài ticket (cấu hình, rule, convention, edge tay, quét chỉ mục, hàng đợi) qua `AuditFilter`. Request lỗi (≥ 400) và lệnh đọc không ghi.
- Ghi bằng sự kiện Spring (`AuditLog.Event`) từ các service đã có publisher, nên không đổi constructor. Đọc lại cả file khi xem; file lớn thì tách theo tháng (đánh dấu `ponytail:` trong code).

## D-42 Tiêu đề tab, biểu tượng, thông báo trình duyệt

- Tiêu đề: `(<số ticket chờ PMO>) <dự án> · <trạng thái>`; trạng thái ưu tiên: agent đang chạy (kèm mã ticket) → chờ PMO xác nhận → lỗi (phiên agent gần nhất của ticket lỗi) → DevMap. Trang chủ: `(<tổng chờ PMO>) DevMap`.
- Biểu tượng: chấm màu dự án, thêm chấm nhỏ xanh (chạy) / vàng (chờ PMO) / đỏ (lỗi). Dữ liệu từ `GET /api/projects/<dự án>/agents`, làm mới theo SSE `task`/`agent`.
- Thông báo trình duyệt khi ticket sang Chờ PMO chốt / Chờ PMO xác nhận, khi phiên agent xong hoặc lỗi; chỉ khi tab đang ẩn; `tag` theo dự án/ticket nên nhiều tab cùng dự án không chồng thông báo. Bấm vào: focus đúng tab, mở đúng ticket. Quyền thông báo xin bằng nút "Bật thông báo" (trình duyệt yêu cầu thao tác người dùng).
- Đóng tab không dừng agent (agent chạy trong `devmap serve`). Ctrl+C khi còn phiên chạy hoặc chờ lượt: in cảnh báo, Ctrl+C lần nữa trong 10 giây mới tắt.

## D-43 Chạy hàng đợi (AC-04, FR-07)

Nút "Chạy hàng đợi": Dev làm lần lượt các ticket Sẵn sàng theo thứ tự PMO xếp. Ticket sau chỉ bắt đầu khi ticket trước đã rời triển khai (SA kiểm chứng đạt → chờ push, hoặc Done). Ticket trước lỗi, bị SA trả về Dev, hoặc Dev dừng giữa chừng thì hàng đợi tự dừng để PMO xử lý. Trạng thái chạy chỉ ở bộ nhớ: khởi động lại `serve` thì bấm lại. Các ticket không nằm trong hàng đợi vẫn chạy song song như cũ (worktree riêng).

## D-44 Gate của ticket chỉ chặn vi phạm tách lớp trong file ticket sửa

Phát hiện khi chạy trọn luồng với Claude Code thật: vi phạm có sẵn ở nhánh nền (`OrderReportController`, cài cho AC-10) làm gate của mọi ticket ở order-service không bao giờ đạt. Dự án thật có nợ kỹ thuật cũ cũng sẽ bị như vậy.
Gate của ticket giờ chỉ chặn vi phạm trong file ticket đã đổi so với nhánh nền (commit + chưa commit); vi phạm có sẵn vẫn liệt kê trong tests.md ("không chặn, nên làm ticket riêng"). `devmap rules check` vẫn kiểm cả repo. AC-10 ("một thay đổi gọi thẳng Controller → Repository bị chặn") giữ nguyên.

## D-45 Sửa sau khi chạy thật (Phase 6)

- SA không ghi được plan.md khi ticket đã qua cổng chốt (Sẵn sàng trở đi): lần chạy thật, SA kiểm chứng đã tự thêm file lệch vào plan.md để hợp thức hoá. Lệch plan thì ghi verify.md và trả về Dev; đổi plan thì PMO yêu cầu phân tích lại.
- Thêm công cụ `delete_file` cho Dev (cùng kiểm tra đường dẫn và file bí mật): lần chạy thật Dev chỉ làm rỗng được file cần xoá.
- Ghi chú trước phiên Dev tách "Commit PMO tự làm" (chắc chắn của PMO) và "File chưa commit (phiên Dev trước để lại hoặc PMO sửa tay)": trước đây file chưa commit của chính Dev bị ghi là của PMO.
- `samples/shop/*/.gitignore` có `target/`: không có thì output build của gate thành "file chưa commit".

## D-46 Gõ `devmap` là mở giao diện

- `devmap` không tham số chạy `serve` và mở trình duyệt đúng link có token (`--no-open` để không mở). SRS ghi `devmap serve`; lệnh đó vẫn giữ nguyên.
- Launcher `bin/devmap.cmd` (Windows) và `bin/devmap` (macOS/Linux): tìm `devmap.jar` cạnh script, không có thì `../target/devmap.jar`. Thêm `bin/` vào PATH là gõ được `devmap` ở mọi nơi. Tuỳ chọn JVM qua biến `DEVMAP_OPTS`.
- Khi chạy, link có token được ghi vào `~/.devmap/serve.url` (xoá khi tắt). Gõ `devmap` lần nữa trong lúc server đang chạy thì chỉ mở lại link đó, không báo lỗi trùng cổng. Token vẫn đổi mỗi lần chạy (NFR-08); file nằm trong thư mục người dùng giống các dữ liệu khác của `~/.devmap`.
- NFR-09: DevMap không giữ khoá nào; khoá API do Claude Code giữ, quyền Git do git credential manager của máy giữ (fetch/push đi qua git CLI).

## D-47 Chọn folder dữ liệu, dọn folder thừa, bỏ `--git`
PMO yêu cầu bỏ tuỳ chọn khởi tạo folder dữ liệu thành git repo (khác CLAUDE.md §5.4): mỗi service đã có git riêng.
Trình duyệt không trả đường dẫn tuyệt đối nên nút **Chọn…** gọi `POST /api/folders/pick`: server (cùng máy, chỉ nghe 127.0.0.1) mở hộp thoại chọn folder của hệ điều hành —
Windows: hộp thoại kiểu Explorer (`IFileOpenDialog`, qua PowerShell `-EncodedCommand`, `src/main/resources/pick-folder.ps1`); macOS: `osascript choose folder`; Linux: `zenity`.
Chỉ khi PMO chọn xong (không phải khi gõ tay hay bấm Huỷ) form liệt kê (chỉ xem) folder con của folder dữ liệu và dự án nào đang dùng. PMO yêu cầu bỏ nút xoá folder: DevMap không xoá gì trên đĩa ở đây.

## D-48 Folder dự án và quét service
Form "Thêm dự án" có ô **Folder dự án** = folder cha chứa các repo service. Chỉ sau khi PMO chọn folder trong hộp thoại hệ điều hành, DevMap gọi `GET /api/repos/scan`:
folder là repo git thì lấy chính nó, không thì lấy các folder con trực tiếp là repo git; nhánh nền mặc định là nhánh đang checkout (detached thì `main`), service tự phát hiện. Quét không ghi gì.
Nút **Bỏ khỏi DevMap** (ở form và ở Cấu hình dự án) chỉ bỏ repo khỏi config.yml/danh sách, không bao giờ xoá file trên máy.

## D-49 Graph như canvas, tạo ticket là gửi SA, ô token & quota
- **Graph**: thanh công cụ zoom +/−, Vừa khung, chọn bố cục (tự chọn/lực đẩy/cây theo tầng/đồng tâm/vòng tròn/lưới), Sắp xếp lại; zoom tới 4×; Shift+kéo chọn nhiều; nhấp node làm mờ phần không nối trực tiếp; nhãn tự ẩn khi thu nhỏ. Vị trí node PMO kéo được nhớ theo từng góc nhìn trong `localStorage` của trình duyệt (chỉ là tiện ích, mất cũng không sao). Hộp "Bước này làm gì?" giải thích quét → nối → xem → dùng.
- **Tạo ticket**: form có ô "Gửi agent SA phân tích…" tích sẵn; tạo xong DevMap mở ngay một phiên SA (như PMO hỏi, chuyển Mới → SA phân tích). API `POST /tasks` chỉ làm vậy khi gửi `analyze: true`, để script/CLI không vô tình tốn token.
- **Token & quota** (FR-40 mở rộng): ô trên thanh đầu trang dự án hiện token hôm nay, cộng dồn dự án, chi phí ước tính và quota tài khoản. Quota lấy từ sự kiện `rate_limit_event` mà Claude Code stream (`status`, `rateLimitType`, `resetsAt`, `utilization`); DevMap giữ bản mới nhất theo từng loại trong bộ nhớ, khởi động lại thì hiện lại sau phiên agent kế tiếp. Dùng API key thì Claude Code không gửi quota.

## D-50 Tuỳ chọn push thẳng vào nhánh nền, tab Câu hỏi chờ PMO
- **Push thẳng** (PMO yêu cầu, khác FR-30 "không cấu hình bỏ được"): ở cột phải, dòng Branch có ô "Code và push thẳng vào nhánh nền (không tạo branch)", lưu `direct` trong `dev.json`. Chỉ PMO bật được (`PUT /dev/direct`) và chỉ trước khi có worktree; có worktree rồi thì khoá. Khi bật: worktree vẫn tách riêng nhưng `--detach` tại nhánh nền (không checkout branch nào, repo của PMO giữ nguyên), push `commit:refs/heads/<nhánh nền>` sau khi PMO xác nhận, bỏ qua kiểm tra branch bảo vệ chỉ cho ticket này. Quality gate, SA kiểm chứng, xác nhận push và cấm force-push vẫn giữ; nhánh nền đi trước thì push bị từ chối (không fast-forward), PMO bấm pull nhánh nền rồi làm lại.
- **Tab Câu hỏi chờ PMO**: SA ghi câu hỏi làm rõ dưới heading `## Câu hỏi chờ PMO` của summary.md (sa.md đã quy định rõ). Khi mục này có câu, ticket hiện tab đầu tiên "Câu hỏi chờ PMO (n)" màu cam, mở mặc định; mỗi câu một ô trả lời, "Gửi câu trả lời" mở một phiên agent mới với các cặp câu hỏi → trả lời (FR-13, FR-37). SA xoá câu đã trả lời khỏi summary.md nên tab tự mất khi hết câu.

## D-51 Sửa code thẳng trong repo service, Dev tự triển khai, trạng thái và thông báo
PMO yêu cầu (đợt góp ý 2026-10-31):
- **Code sửa thẳng trong repo của service** (khác NFR-07 "mỗi ticket một git worktree"): "Triển khai"/"Pull nhánh nền" checkout branch của ticket ngay trong repo (`git checkout [-b]`), rồi merge nhánh nền mới nhất. Từ chối khi repo còn file chưa commit, hoặc khi ticket khác đang triển khai/SA kiểm chứng trên repo đó (mỗi repo một ticket đang làm). Ticket chờ push không giữ checkout: commit của nó đọc từ ref của branch (`refs/heads/<branch>`), không từ HEAD; commit và quality gate từ chối nếu repo đang ở nhánh khác. Chế độ push thẳng: checkout chính nhánh nền. Ticket cũ còn worktree ở `~/.devmap/worktrees` thì worktree sạch được gỡ (`git worktree remove`), branch giữ nguyên. `ponytail:` song song nhiều ticket trên cùng repo cần quay lại worktree.
- **Dev tự triển khai**: ticket Sẵn sàng có nút chính "Triển khai" (và ▶ ở từng ticket trong hàng đợi, nút "Dev triển khai theo tài liệu" khi đang triển khai): `POST /tasks/{id}/implement` mở phiên Dev với yêu cầu "Triển khai theo plan.md đã chốt"; Dev xong (`sa_verify`) thì phiên SA kiểm chứng tự chạy như trước.
- **Rà soát nhánh nền trước khi code** (AIR-04 mở rộng): trước phiên Dev, DevMap so commit nền mới với commit lần pull trước của ticket (lần đầu: commit chỉ mục mà SA đã phân tích), đưa danh sách commit và file mới vào ngữ cảnh; dev.md yêu cầu rà soát với plan.md và ghi `## Rà soát nhánh nền` trong summary.md, ảnh hưởng làm sai plan thì nêu để SA/PMO cập nhật (Dev không tự đổi phạm vi).
- **Bớt quét lại mỗi câu hỏi**: phiên SA chỉ fetch và cập nhật chỉ mục cho repo của ticket (chưa có repo thì mọi repo), ghi một dòng khi đã mới nhất. Bảng "Service ảnh hưởng" chỉ dựng ở lần phân tích đầu; các câu sau dùng lại bảng trong tài liệu, chỉ tra graph cho điểm mới (sa.md, nhiệm vụ trong prompt).
- **Chọn repo**: cột phải chỉ hiện các service SA xác định (`set_repos`); service khác của dự án nằm trong mục thu gọn để PMO thêm khi cần.
- **Ponytail luôn bật**: plugin Claude Code của máy không chạy trong phiên agent (DevMap dùng `--setting-sources project` và system prompt riêng), nên quy tắc ponytail rút gọn nằm ở `prompts/ponytail.md`, ghép vào system prompt tĩnh của SA và Dev (được cache, gần như không tốn thêm token).
- **Trạng thái trên tab và danh sách**: `GET /agents` trả thêm `working` (ticket → SA/DEV/VERIFY) và `questions` (ticket → số câu chờ PMO). Tiêu đề tab: "(số việc chờ PMO) dự án · SA đang soạn tài liệu (FEAT-001) +1"; chấm trên icon và nhãn màu ở danh sách/ticket: SA xanh dương, Dev tím, SA kiểm chứng xanh ngọc, cần PMO vàng, lỗi đỏ. Ticket đang chạy có thanh tiến trình trên đầu (vai trò, số bước, bước mới nhất).
- **Thông báo hệ điều hành**: hiện khi trang không được focus (trước đây chỉ khi tab bị ẩn), gồm cả khi agent ghi câu hỏi mới cho PMO; thông báo cần PMO quyết định/trả lời ở lại tới khi bấm, bấm vào mở ticket (tab Câu hỏi mở mặc định).
- **Ghi file trên Windows**: lỗi "Không ghi được …chat.json.tmp -> chat.json" do Windows không cho thay một file đang có người mở (UI đọc chat.json trong lúc agent ghi từng bước). Mỗi lần ghi dùng file tạm riêng, thử thay vài lần, rồi ghi đè tại chỗ; các hàm đọc JSON/YAML dùng `Files.readAllBytes` (giữ file rất ngắn).
- **Gate chạy được trên máy thiếu công cụ** (log Dev FEAT-001): lệnh `mvn …`/`gradle …` tự đổi sang `mvnw`/`gradlew` khi service có wrapper; lệnh không tìm thấy (mã 9009/127) kèm gợi ý cài công cụ hoặc sửa lệnh ở trang Quy tắc. Gate chạy với `CI=true` để test runner không ở chế độ watch.
- **Preset `node`** (khác CLAUDE.md §5.9 "ESLint cho JS/TS"): `npm run lint --if-present`, `npm run build --if-present`, `npm run test --if-present -- --watch=false` thay cho `npx eslint .`/`npm test`. Repo chưa có cấu hình ESLint vẫn được kiểm biên dịch, không phải thêm file vào repo; ESLint chạy khi repo có script `lint`. Repo không có test nào thì PMO tắt `unit_test` cho service đó ở trang Quy tắc.
- **`git_diff` có tham số `commit`**: Dev/SA xem đúng một commit (commit PMO, commit mới của nhánh nền) qua `git show`, mã commit chỉ nhận 7–40 ký tự hex, file bí mật bị bỏ như diff thường. Phiên code thẳng trên nhánh nền ghi rõ ở đầu ngữ cảnh thay vì tên branch ticket; công cụ `commit` trả cả tên nhánh.

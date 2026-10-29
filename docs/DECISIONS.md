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

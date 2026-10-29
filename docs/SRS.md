# DevMap – Tài liệu yêu cầu (SRS) v0.3

Oct 23, 2026 · @Lưu Quang

## Thông tin tài liệu

Tài liệu này tổng hợp yêu cầu cho công cụ DevMap, do SA/BA soạn từ các buổi trao đổi với PMO, gửi PMO rà soát và chốt trước khi chuyển sang thiết kế chi tiết và triển khai.

| Mục | Nội dung |
| --- | --- |
| Tên sản phẩm | DevMap (tên tạm) |
| Người yêu cầu | PMO |
| Người soạn | SA/BA |
| Loại tài liệu | Đặc tả yêu cầu phần mềm (SRS) |
| Phiên bản | 0.3 – bản nháp chờ PMO duyệt |
| Dự án áp dụng đầu tiên | nyxhub, lendbiz |

**Lịch sử phiên bản**

| Phiên bản | Ngày | Người sửa | Nội dung |
| --- | --- | --- | --- |
| 0.3 | 29/10/2026 | SA/BA | Thêm quy tắc thực thi cho agent AI (AIR-01 đến AIR-13): git pull trước mọi task, không quét toàn bộ khi khởi động, graph JSON tăng dần theo git diff, ngữ cảnh 2–3 tầng chỉ gồm chữ ký và đoạn code, prompt caching, tách lớp Controller → Service → Repository, phân tích tĩnh trước khi báo hoàn thành; SA đối chiếu yêu cầu gốc với PR/commit trước khi báo PMO (AIR-14, FR-43); thêm cơ chế tối ưu token 5 lớp, làm sạch cục bộ thành spec.json, xoá ngữ cảnh khi đóng task (FR-44, FR-45); chỉnh FR, NFR, kiến trúc và nghiệm thu cho khớp |
| 0.2 | 24/10/2026 | SA/BA | Cập nhật theo 18 góp ý của PMO: công cụ cá nhân, SA và Dev là agent AI, bỏ mức ưu tiên, folder dữ liệu riêng, mã ticket do PMO cấp, bug sau deploy ghi vào ticket gốc, che bí mật, xác nhận push, coding convention, mỗi câu hỏi một phiên, giao diện kiểu Messenger, dự án gồm nhiều repo |
| 0.1 | 23/10/2026 | SA/BA | Bản đầu, tổng hợp từ trao đổi với PMO ngày 22–23/10/2026 |

## Bối cảnh và mục tiêu

DevMap giúp đi từ một yêu cầu bằng lời của PMO đến code chạy đúng trên dự án microservice backend, với mọi API/function bị ảnh hưởng được đánh dấu và truy vết đầy đủ.

**Vấn đề hiện tại**

- Một yêu cầu nghiệp vụ thường cắt ngang nhiều service, nhưng code bị chia theo service. Không ai nhìn thấy đủ "lát cắt" của một yêu cầu.
- Yêu cầu của PMO viết bằng ngôn ngữ nghiệp vụ. Thiếu một bước biên dịch có kiểm soát sang ngôn ngữ IT, nên Dev hoặc AI phải tự đoán.
- Bug phát hiện sau deploy khó truy ngược lại yêu cầu gốc, phương án đã chốt và code đã sửa.
- Mỗi dự án có thói quen khác nhau (có hoặc không có mã ticket, quy tắc git khác nhau), khó quản lý chung.

**Mục tiêu**

1. Biên dịch yêu cầu của PMO thành phân tích nghiệp vụ và giải pháp kỹ thuật bằng agent SA; PMO chốt rồi agent Dev mới triển khai.
2. Đánh dấu các API/function ảnh hưởng lẫn nhau xuyên nhiều repo/service của một dự án.
3. Lưu nghiệp vụ và lịch sử của mỗi ticket thành file, luôn cập nhật logic đúng sau mỗi lần sửa bug hoặc bổ sung, để lần sau xử lý lại nhanh và không sót case.
4. Một người (PMO) điều khiển hai agent AI là SA và Dev; PMO luôn là người chốt và có thể tự sửa code.
5. Áp dụng rule và coding convention riêng theo dự án và từng service; không push khi PMO chưa xác nhận.
6. Không để lộ IP, token, key cho agent AI hay vào tài liệu.
7. Tiết kiệm token: mỗi câu hỏi một phiên agent, ngữ cảnh lấy từ tài liệu đã lưu thay vì đọc lại chat.
8. Quản lý song song nhiều dự án (nyxhub, lendbiz), mỗi dự án gồm nhiều repo/service.

## Phạm vi

Phiên bản đầu là công cụ cá nhân cho một PMO (dev kiêm quản lý dự án), chạy trên máy của PMO qua lệnh `devmap serve` và giao diện web localhost.

**Trong phạm vi**

- Một người dùng duy nhất là PMO; SA và Dev là agent AI.
- Ticket FEAT, MAINT, BUG; mã do PMO cấp theo dự án hoặc tự sinh.
- Làm ticket lần lượt hoặc song song nhiều ticket; không dùng mức ưu tiên.
- Dữ liệu DevMap lưu ở folder PMO chọn, tách khỏi source code.
- Dependency graph xuyên nhiều repo/service của một dự án.
- Chatbox theo ticket, mỗi câu hỏi một phiên agent.
- Rule theo từng service (git, chất lượng, phạm vi) và coding convention theo dự án.
- Che IP, token, key trước khi agent đọc code và trước khi ghi tài liệu; không đọc file env.
- Nhiều dự án, mỗi dự án một URL; CLI `devmap`.

**Ngoài phạm vi**

- Nhiều người dùng, đăng nhập, phân quyền, server chung.
- Mức ưu tiên, sprint, velocity, burndown.
- Frontend/mobile app của dự án được quản lý (chỉ phân tích backend).
- Tự động deploy; công cụ chỉ ghi nhận đã deploy lên môi trường nào, version nào.
- Bản cài đặt .exe (Electron); để lại phase sau nếu cần.

**Giả định**

- Máy PMO có git, JDK 21 và agent AI (mặc định Claude Code).
- Mỗi dự án (nyxhub, lendbiz) gồm nhiều repo/service; PMO khai báo danh sách repo khi thêm dự án.
- Mỗi repo có một nhánh nền được cấu hình (ví dụ test hoặc main) để pull code mới trước khi làm.

## Vai trò và quyền hạn

PMO là người thật duy nhất, đưa mọi yêu cầu và chốt mọi quyết định; SA và Dev là hai agent AI làm theo chỉ đạo của PMO.

| Vai trò | Là ai | Trách nhiệm | Được làm | Không được làm |
| --- | --- | --- | --- | --- |
| PMO | Người thật (dev kiêm quản lý dự án) | Đưa yêu cầu, chốt giải pháp và thứ tự làm, xác nhận push, nghiệm thu | Mọi thao tác, kể cả tự sửa code; bật quét toàn bộ dự án | Không giới hạn |
| SA | Agent AI | Bóc tách yêu cầu, phân tích nghiệp vụ và hệ thống, tìm nguyên nhân bug, gợi ý giải pháp, kiểm chứng kết quả của Dev với yêu cầu gốc | Đọc code đã che bí mật, ghi analysis.md, plan.md, summary.md | Sửa code, tự chốt giải pháp |
| Dev | Agent AI | Triển khai giải pháp đã chốt, viết và chạy test, commit | Pull nhánh nền, sửa code trong phạm vi ticket, chạy test, commit theo rule | Push khi PMO chưa xác nhận, sửa ngoài phạm vi, đọc file env |

Mọi đề xuất của SA chỉ là gợi ý; chỉ khi PMO chốt thì Dev mới được bắt đầu. Vì chỉ có một người dùng, công cụ không có đăng nhập hay phân quyền người dùng; quyền hạn trong bảng là quyền của từng agent.

## Loại ticket

Có ba loại ticket; bug phát hiện sau deploy không tạo ticket mới mà ghi vào chính ticket của tính năng đó.

|  | FEAT – Tính năng mới | MAINT – Bảo trì | BUG – Bug độc lập |
| --- | --- | --- | --- |
| Ví dụ | Hoàn tiền một phần, đăng nhập Google | Nâng Spring Boot, vá bảo mật, refactor, tối ưu hiệu năng | Lỗi không thuộc ticket nào đã làm trong DevMap |
| Ai khởi tạo | PMO | PMO; agent SA có thể gợi ý | PMO |
| Agent SA phân tích | Nghiệp vụ, user story, AC, impact, giải pháp, việc theo repo | Lý do, phạm vi, impact, kế hoạch rollback | Tái hiện, nguyên nhân gốc, impact, gợi ý cách sửa |
| PMO chốt | Giải pháp | Giải pháp và thứ tự các ticket bảo trì | Cách sửa |
| Nghiệm thu bằng | Đạt từng AC | Hành vi không đổi, test hồi quy pass | Test tái hiện bug chuyển từ fail sang pass |
| Branch mặc định | `feature/{mã}-{slug}` | `chore/{mã}-{slug}` | `bugfix/{mã}-{slug}` |

**Mã ticket**

- PMO nhập mã khi tạo ticket, theo quy ước riêng của từng dự án (ví dụ mã từ hệ thống quản lý của khách hàng).
- Để trống thì công cụ tự sinh theo loại, tăng dần trong dự án: `FEAT-012`, `MAINT-005`, `BUG-003`.
- Một ticket chỉ có một mã, dùng chung cho mọi repo/service mà ticket chạm vào: cùng tên branch ở mọi repo, mọi commit liên quan đều mang mã này.

**Mở lại ticket đã Done: báo bug hoặc bổ sung logic**

- PMO chọn một ticket đã Done, bấm "Báo bug" hoặc "Bổ sung logic"; ticket mở lại với đúng mã cũ.
- Bug được ghi thành một mục trong ticket (folder `bugs/`), không sinh mã mới.
- Sau khi sửa xong, agent SA cập nhật analysis.md để tài liệu luôn mô tả logic đúng hiện tại, thêm case vừa phát hiện vào AC, tránh đọc sót ở lần xử lý sau.

## Luồng một ticket

Mọi ticket đi qua 8 trạng thái; PMO quyết ở hai điểm là chốt giải pháp và xác nhận push, không agent nào tự vượt qua hai điểm này.

&#91;embedded content: luồng một ticket · 8 trạng thái, 2 cổng duyệt\]

Trước khi triển khai, agent Dev luôn pull code mới nhất từ nhánh nền đã cấu hình của từng repo. PMO có thể tự sửa code ở bất kỳ bước nào; agent Dev đọc lại thay đổi đó trước khi làm tiếp. Nhiều ticket có thể triển khai song song, mỗi ticket một worktree riêng; nếu không, các ticket ở trạng thái Sẵn sàng được làm lần lượt theo thứ tự PMO xếp.

Agent Dev xong không báo thẳng PMO: agent SA đối chiếu yêu cầu gốc và AC với diff PR/commit ở mọi repo, ghi verify.md. Có thay đổi không gắn với AC nào, hoặc chỗ gọi tới code bị đổi chưa được kiểm, thì trả về Dev; chỉ khi không lệch phạm vi và không hồi quy, ticket mới sang Chờ PMO xác nhận.

**Khác biệt theo loại ticket**

- **MAINT**: PMO chốt cả giải pháp và thứ tự các ticket bảo trì.
- **BUG độc lập**: agent SA tái hiện và tìm nguyên nhân gốc trước khi gợi ý cách sửa.
- **Mở lại** (bug sau deploy, bổ sung logic): giữ mã cũ, đi lại từ bước SA phân tích; khi xong, agent SA cập nhật analysis.md theo logic đúng.

## Yêu cầu chức năng

Có 45 yêu cầu chức năng chia 9 module; cột Ưu tiên (MoSCoW) là thứ tự xây công cụ, không phải mức ưu tiên của ticket.

| Mã | Module | Yêu cầu | Ưu tiên |
| --- | --- | --- | --- |
| FR-01 | Dự án | Đăng ký nhiều dự án: tên, màu, danh sách repo/service thuộc dự án; thêm/xoá qua giao diện hoặc `devmap workspace add` | Must |
| FR-02 | Dự án | PMO chọn folder lưu dữ liệu DevMap của từng dự án, tách khỏi source code | Must |
| FR-03 | Dự án | Tự phát hiện service trong các repo; PMO sửa được danh sách | Must |
| FR-04 | Dự án | Cấu hình theo dự án: quy ước mã ticket, mẫu branch, nhánh nền để pull của từng repo, môi trường test | Must |
| FR-05 | Ticket | PMO tạo ticket bằng ngôn ngữ tự nhiên; nhập mã theo dự án hoặc để công cụ tự sinh | Must |
| FR-06 | Ticket | Một ticket một mã duy nhất, dùng chung tên branch và commit ở mọi repo/service bị chạm | Must |
| FR-07 | Ticket | Không có mức ưu tiên; PMO cho làm lần lượt theo hàng đợi hoặc song song nhiều ticket | Must |
| FR-08 | Ticket | Chuyển trạng thái theo luồng; chỉ PMO được chốt giải pháp và xác nhận push | Must |
| FR-09 | Ticket | Mỗi lần sửa analysis.md hoặc plan.md tạo version mới, xem được diff | Must |
| FR-10 | Ticket | Chọn ticket đã Done để "Báo bug" hoặc "Bổ sung logic"; mở lại với mã cũ, ghi bug vào ticket, không sinh mã mới | Must |
| FR-11 | Ticket | Sau khi sửa bug hoặc bổ sung, agent SA cập nhật analysis.md theo logic đúng và thêm case mới vào AC | Must |
| FR-12 | Agent SA | Viết user story, AC (Given/When/Then), business rule, phạm vi từ yêu cầu của PMO | Must |
| FR-13 | Agent SA | Đặt câu hỏi làm rõ khi yêu cầu mơ hồ và chờ PMO trả lời | Must |
| FR-14 | Agent SA | Xác định repo, service, API, function, DB table, topic bị ảnh hưởng | Must |
| FR-15 | Agent SA | Với bug: tái hiện, tìm nguyên nhân gốc, gợi ý cách sửa | Must |
| FR-16 | Agent SA | Gợi ý giải pháp (tối đa 2 phương án khi có đánh đổi), tách việc theo repo/service và thứ tự làm | Must |
| FR-17 | Agent SA | Mặc định chỉ lấy ngữ cảnh 2–3 tầng quanh file đích dựa trên analysis.md và graph; nút "Quét toàn bộ dự án" do PMO bật, mặc định tắt, chỉ cập nhật chỉ mục chữ ký trên máy, không gửi code cho agent | Must |
| FR-18 | Agent SA | Cảnh báo thay đổi contract API (OpenAPI/proto) ảnh hưởng service đang gọi | Should |
| FR-19 | Graph | Graph dạng JSON gọn xuyên nhiều repo: service, API, chữ ký class/method, DB table, topic và các quan hệ | Must |
| FR-20 | Graph | Phát hiện lời gọi giữa service qua OpenAPI, HTTP client, config, docker-compose; edge có độ tin cậy | Must |
| FR-21 | Graph | Không quét toàn bộ khi khởi động; sau mỗi lần pull, cập nhật graph tăng dần chỉ với file sửa hoặc mới theo git diff | Should |
| FR-22 | Graph | PMO thêm/xoá edge thủ công, không bị ghi đè khi re-index | Should |
| FR-23 | Graph | Hiển thị 3 mức Service → API → Function, tô sáng phần thuộc một ticket, blast radius | Should |
| FR-24 | Agent Dev | Trước khi làm luôn pull code mới nhất từ nhánh nền đã cấu hình (ví dụ test hoặc main) của từng repo | Must |
| FR-25 | Agent Dev | Code theo giải pháp đã chốt và coding convention của dự án; sinh test từ AC, chạy test | Must |
| FR-26 | Agent Dev | Commit ở mọi repo đều mang mã ticket; ghi branch, commit, file sửa theo từng repo vào changes.md | Must |
| FR-27 | Agent Dev | Chỉ push sau khi PMO bấm xác nhận cho từng lần push; trước đó hiển thị diff và kết quả test | Must |
| FR-28 | Agent Dev | Nhận biết code PMO tự sửa và đưa vào changes.md trước khi làm tiếp | Should |
| FR-29 | Rule | Rule theo service trong `.devrules.yml`, kế thừa rule mặc định của dự án; giao diện checkbox và preset | Must |
| FR-30 | Rule | Git: cách tạo branch và commit cấu hình được; push luôn cần PMO xác nhận, không push branch protected, không force-push (không tắt được) | Must |
| FR-31 | Rule | Chất lượng: phân tích tĩnh (ESLint, SonarQube, Checkstyle), unit test, integration test, kiểm tách lớp Controller → Service → Repository; lệnh chạy cấu hình theo service | Must |
| FR-32 | Rule | Phạm vi: thư mục cấm sửa; đổi DB schema và dependency cần PMO chốt | Must |
| FR-33 | Convention | Agent thu thập coding convention từ code hiện có và cấu hình lint/formatter thành conventions.md theo dự án và ngôn ngữ; PMO duyệt; agent Dev tuân theo và tự kiểm trước khi commit | Must |
| FR-34 | Bảo mật | Không đọc file env và file bí mật (`.env*`, `*.pem`, `*.key`, file credentials); chặn ở tầng công cụ, không phụ thuộc agent tự giác | Must |
| FR-35 | Bảo mật | Che IP, token, key, mật khẩu, connection string trước khi đưa nội dung code cho agent | Must |
| FR-36 | Bảo mật | Che các giá trị đó trước khi ghi vào bất kỳ tài liệu nào trong folder dữ liệu | Must |
| FR-37 | Chatbox | Mỗi câu hỏi của PMO chạy một phiên agent mới; ngữ cảnh lấy từ file của ticket, không đọc lại chat cũ | Must |
| FR-38 | Chatbox | Cuối mỗi phiên, agent cập nhật file của ticket (analysis.md, plan.md, summary.md) để câu hỏi sau dùng | Must |
| FR-39 | Chatbox | Stream từng bước agent làm; thẻ xác nhận khi cần PMO quyết | Must |
| FR-40 | Chatbox | Hiển thị token đã dùng cho mỗi câu hỏi, cộng dồn theo ticket và dự án | Should |
| FR-41 | Tổng quan | Trang chung: danh sách dự án, ticket đang chạy, việc chờ PMO quyết ở mọi dự án | Must |
| FR-42 | CLI | Lệnh `devmap`: init, serve, index, workspace, task new/list/show, prompt, rules check, doctor | Must |
| FR-43 | Agent SA | Sau khi agent Dev xong, agent SA đối chiếu yêu cầu gốc và AC với diff PR/commit ở mọi repo, ghi kết quả vào verify.md: AC nào được đáp ứng bởi thay đổi nào, thay đổi nào không thuộc AC nào (lệch phạm vi), chỗ gọi nào tới chữ ký bị đổi đã kiểm (hồi quy); có sai lệch thì trả về Dev, không báo PMO | Must |
| FR-44 | Tối ưu token | Làm sạch cục bộ, không gọi LLM: bỏ câu chữ thừa, che bí mật, chuyển yêu cầu của PMO thành spec.json (mã ticket, module đích, loại hành động); LLM chỉ nhận spec.json, không nhận nguyên văn | Must |
| FR-45 | Tối ưu token | Khi PMO ký đóng task, xoá ngay ngữ cảnh phiên và bộ nhớ tạm của task; chỉ giữ tài liệu trong folder ticket; mở lại task thì ngữ cảnh dựng lại từ tài liệu | Must |

## Lưu trữ dữ liệu

Dữ liệu DevMap của mỗi dự án nằm trong một folder do PMO chọn, tách khỏi các repo source code; chép folder này sang máy khác là có đủ ticket, tài liệu và lịch sử.

Lý do tách khỏi repo: một dự án gồm nhiều repo, ticket chạm nhiều repo nên không thuộc riêng repo nào. PMO có thể biến folder này thành một repo git riêng để đồng bộ giữa các máy.

**Cấu trúc folder dữ liệu của một dự án**

```
<folder dữ liệu>/nyxhub/
├── project.md            # mô tả hệ thống, danh sách repo/service
├── config.yml            # repo, nhánh nền, quy ước mã ticket, branch, môi trường
├── conventions.md        # coding convention đã được PMO duyệt
├── rules/                # rule mặc định và rule từng service
├── graph/                # dependency graph, tạo lại được
└── tasks/
    └── NYX-231-dang-nhap-google/
        ├── task.md        # yêu cầu gốc của PMO, trạng thái
        ├── spec.json      # yêu cầu đã làm sạch, đầu vào cho LLM
        ├── analysis.md    # nghiệp vụ và logic đúng hiện tại, AC
        ├── plan.md        # impact, giải pháp đã chốt, việc theo repo
        ├── summary.md     # tóm tắt ngắn, ngữ cảnh cho phiên agent tiếp theo
        ├── history.md     # nhật ký hỏi đáp, có giờ
        ├── changes.md     # branch, commit, file sửa theo từng repo
        ├── tests.md       # test và kết quả
        ├── verify.md      # SA đối chiếu yêu cầu với diff: phạm vi, hồi quy
        └── bugs/          # bug sau deploy, cùng mã ticket
```

**config.yml của dự án**

```yaml
project: nyxhub
ticket_code: manual          # manual: PMO nhập, để trống thì tự sinh
repos:
  - path: D:/code/nyxhub/order-service
    base_branch: test
  - path: D:/code/nyxhub/payment-service
    base_branch: main
scan_full_project: false     # mặc định không quét toàn bộ dự án
```

**Đầu file task.md**

```yaml
id: NYX-231                  # mã PMO nhập, hoặc FEAT-012 nếu tự sinh
type: FEAT                   # FEAT | MAINT | BUG
title: Đăng nhập bằng Google
status: sa_analysis
repos: [auth-service, user-service]
version: 2
reopened: 0                  # số lần mở lại vì bug hoặc bổ sung logic
```

**Dữ liệu trên máy, ngoài folder dữ liệu**

- `~/.devmap/workspaces.yml`: danh sách dự án và đường dẫn folder dữ liệu của từng dự án.
- Bộ nhớ đệm graph: tạo lại được, không cần sao lưu.
- API key, token Git: lưu trong kho bảo mật của hệ điều hành, không ghi vào file.

**Quy tắc ghi file**

- IP, token, key, mật khẩu, connection string luôn được che trước khi ghi vào bất kỳ file nào.
- analysis.md luôn mô tả logic đúng hiện tại; lịch sử thay đổi nằm trong version và history.md.
- summary.md được agent cập nhật cuối mỗi phiên để câu hỏi sau không cần đọc lại chat.

## Giao diện

Mỗi dự án một URL; trong một dự án, giao diện bố cục kiểu Messenger: danh sách task bên trái, nội dung task ở giữa, thông số và các nút quyết định của PMO bên phải.

&#91;embedded content: bố cục một dự án · kiểu Messenger, 3 cột\]

Bấm vào một task chỉ tải lịch sử chat hoặc tài liệu nghiệp vụ của task đó; nút quyết định luôn ở cột phải để PMO không phải tìm.

**Đường dẫn**

| URL | Nội dung |
| --- | --- |
| `localhost:7070` | Trang khởi động: danh sách dự án, ticket đang chạy và việc chờ PMO ở mọi dự án, thêm dự án |
| `localhost:7070/nyxhub` | Không gian làm việc của nyxhub |
| `localhost:7070/lendbiz` | Không gian làm việc của lendbiz |

Trong trang dự án không có nút chuyển dự án; muốn sang dự án khác thì mở tab khác.

**Ba cột trong một dự án**

| Cột | Nội dung |
| --- | --- |
| Trái – danh sách task | Mọi ticket của dự án; mỗi dòng có mã, tên, trạng thái; chấm báo khi có việc chờ PMO; lọc theo trạng thái và loại; nút tạo ticket |
| Giữa – nội dung task | Hai tab: Chat (lịch sử hỏi đáp và ô nhập câu hỏi) hoặc Tài liệu (analysis.md, plan.md, changes.md, bugs/) |
| Phải – thông số | Trạng thái, repo/service bị ảnh hưởng, branch, commit, kết quả test, token đã dùng; nút Chốt giải pháp, Xác nhận push, Báo bug, Bổ sung logic, Quét toàn bộ dự án |

Các màn hình phụ mở từ menu: Graph, Rule và coding convention, Cấu hình dự án (repo, nhánh nền, folder dữ liệu, quy ước mã ticket).

**Chatbox**

- Tin nhắn PMO bên phải; tin nhắn agent SA hoặc Dev bên trái, khung nét đứt, ghi rõ "phiên mới" và các file dùng làm ngữ cảnh.
- Các bước agent làm thu gọn thành một dòng, mở ra xem lệnh, đầu ra, diff.
- Thẻ xác nhận khi cần PMO quyết: chốt giải pháp, push, đổi DB schema, sửa thư mục cấm.
- Lịch sử chat chỉ để PMO đọc lại; agent không đọc lại chat mà đọc tài liệu của ticket.

**Hiển thị tiến trình khi làm nhiều tab**

- Tiêu đề tab đổi theo trạng thái, ví dụ `(1) lendbiz · chờ PMO xác nhận`, `nyxhub · đang chạy test…`.
- Biểu tượng tab mang màu dự án, có chấm trạng thái: xanh đang chạy, vàng chờ PMO, đỏ lỗi.
- Thông báo trình duyệt khi agent cần PMO quyết, chạy xong hoặc lỗi; bấm vào mở đúng tab và đúng ticket.
- Đóng tab không dừng agent; tắt `devmap serve` khi còn agent chạy thì phải có cảnh báo.

## Kiến trúc và công nghệ

Toàn bộ công cụ là một file `devmap.jar` chạy trên máy PMO, vừa là CLI vừa là server localhost; agent AI chạy bên dưới, chỉ thấy code đã qua lớp lọc bí mật và chỉ làm việc trong các repo của dự án sở hữu ticket.

&#91;embedded content: kiến trúc · giao diện, devmap.jar, agent, folder dự án\]

Giao diện và CLI cùng gọi vào một lõi; chỉ Agent runner khởi động agent, mỗi câu hỏi một phiên mới, ngữ cảnh lấy từ folder dữ liệu của ticket. Folder dữ liệu DevMap nằm riêng ở nơi PMO chọn, không nằm trong repo nào.

| Lớp | Công nghệ |
| --- | --- |
| Lõi, server, CLI | Java 21, Spring Boot 3, Picocli, đóng gói một file jar |
| Giao diện | HTML, CSS, JavaScript thuần (ES modules), không framework, không bước build; thư viện đi kèm sẵn trong jar (Cytoscape.js cho graph, trình hiển thị Markdown) để chạy được khi không có Internet |
| Giao tiếp | HTTP JSON; Server-Sent Events để stream tiến trình agent và đồng bộ giữa các tab |
| Phân tích code | JavaParser cho Java; tree-sitter cho TypeScript, JavaScript, Python, Go; swagger-parser cho OpenAPI; parser cho file .proto |
| Git | JGit; pull nhánh nền trước mỗi lần triển khai; git worktree cho mỗi ticket đang làm |
| Agent AI | Mặc định Claude Code chạy không giao diện, mỗi câu hỏi một phiên, prompt caching cho quy tắc kiến trúc, coding convention và schema chung, ngữ cảnh chỉ gồm chữ ký và đoạn code, stream từng bước; công cụ sinh hướng dẫn và cấu hình quyền cho agent theo vai SA hoặc Dev và rule service; lớp kết nối chung để thay agent khác |
| Lớp lọc bí mật | Chặn mọi lệnh đọc file env và file bí mật trước khi agent thực hiện; quét và che IP, token, key, mật khẩu, connection string trong nội dung trả cho agent và trước khi ghi tài liệu; danh sách mẫu cấu hình được |
| Lưu trữ | Folder dữ liệu PMO chọn cho từng dự án là nguồn chính; bộ nhớ đệm graph trên máy, tạo lại được |
| Bảo mật kết nối | Chỉ nghe 127.0.0.1, token mỗi phiên; kho khóa bảo mật của hệ điều hành cho API key và token Git |

## Yêu cầu phi chức năng

Ưu tiên hàng đầu là không lộ bí mật và không để agent vượt quyền PMO; tiếp theo là tiết kiệm token, rồi mới đến hiệu năng.

| Mã | Nhóm | Yêu cầu |
| --- | --- | --- |
| NFR-01 | Bí mật | Lớp lọc chặn đọc file env và file bí mật, che IP/token/key trước khi agent thấy nội dung; chặn ở tầng công cụ, áp dụng cả với lệnh shell agent tự chạy |
| NFR-02 | Bí mật | Có bộ test tự động với repo mẫu chứa bí mật: không giá trị nào xuất hiện trong đầu vào của agent hay trong tài liệu |
| NFR-03 | Quyền PMO | Push, chốt giải pháp, đổi DB schema chỉ thực hiện sau xác nhận của PMO; kiểm ở server, không chỉ ở giao diện |
| NFR-04 | Token | Mỗi câu hỏi một phiên agent; ngữ cảnh mặc định chỉ gồm file của ticket và phần code liên quan, không gửi lịch sử chat |
| NFR-05 | Token | Quét toàn bộ dự án mặc định tắt; khi PMO bật, hiển thị cảnh báo chi phí trước khi chạy |
| NFR-06 | Tách biệt dự án | Agent chỉ chạy trong các repo của dự án sở hữu ticket và folder dữ liệu của dự án đó |
| NFR-07 | Tách biệt dự án | Mỗi ticket đang triển khai dùng git worktree riêng để chạy song song không giẫm lên nhau |
| NFR-08 | Kết nối | Server chỉ nhận kết nối từ 127.0.0.1, mọi request kèm token sinh ngẫu nhiên mỗi lần chạy |
| NFR-09 | Khóa | API key AI và token Git lưu trong kho bảo mật của hệ điều hành, không ghi ra file hay log |
| NFR-10 | Truy vết | Mọi hành động của agent và của PMO được ghi nhật ký: thời gian, ticket, repo, service, rule áp dụng |
| NFR-11 | Hiệu năng | Cập nhật graph tăng dần sau mỗi lần pull dưới 20 giây; quét toàn bộ (chỉ khi PMO bật) khoảng 200.000 dòng code dưới 3 phút |
| NFR-12 | Hiệu năng | Giới hạn số agent chạy đồng thời trên máy (mặc định 2), việc sau xếp hàng |
| NFR-13 | Tương thích | Chạy trên Windows, macOS, Linux; giao diện hỗ trợ Chrome và Edge bản mới |
| NFR-14 | Mở rộng | Lớp kết nối agent dùng chung để thay Claude Code bằng agent hoặc model khác |
| NFR-15 | Triển khai | Một file `devmap.jar` chứa cả CLI, server và giao diện; giao diện tiếng Việt, có tiếng Anh |

## Quy tắc thực thi cho agent AI

Agent SA và Dev chỉ làm việc trên chỉ mục code tăng dần theo `git diff`, đưa vào ngữ cảnh chữ ký class/method và đoạn code liên quan trong phạm vi 2–3 tầng phụ thuộc, không bao giờ gửi nguyên file hay cả thư mục.

| Mã | Nhóm | Quy tắc |
| --- | --- | --- |
| AIR-01 | Luồng thực thi | PMO đưa yêu cầu bằng ngôn ngữ tự nhiên, giọng nghiệp vụ |
| AIR-02 | Luồng thực thi | Agent SA chuyển yêu cầu thành đặc tả kỹ thuật bằng cách truy vấn chỉ mục codebase và dependency graph trên máy; xác nhận logic nghiệp vụ với PMO trước khi chốt |
| AIR-03 | Luồng thực thi | Agent Dev triển khai code, chạy phân tích tĩnh (linter/CI) trên máy, rồi chuyển cho agent SA kiểm chứng; chỉ khi SA xác nhận mới báo hoàn thành về PMO |
| AIR-04 | Repo và đồng bộ | Luôn `git pull` repo của service đích trước khi xử lý bất kỳ task hay phân tích nào |
| AIR-05 | Repo và đồng bộ | Không quét toàn bộ và không index lại toàn bộ codebase khi khởi động |
| AIR-06 | Repo và đồng bộ | Theo dõi thay đổi tăng dần bằng `git diff`: chỉ quét file/module bị sửa hoặc mới thêm để cập nhật topology hệ thống |
| AIR-07 | Graph và token | Dependency graph dạng text/JSON gọn (AST, chữ ký class/method), xây và cập nhật từ dữ liệu quét tăng dần |
| AIR-08 | Graph và token | Agent không sinh Mermaid hay sơ đồ hình; màn hình Graph của giao diện tự vẽ từ file JSON, không tốn token |
| AIR-09 | Graph và token | Lấy ngữ cảnh tối đa 2–3 tầng phụ thuộc quanh các file đích |
| AIR-10 | Graph và token | Chỉ đưa chữ ký class/method và đoạn code liên quan (RAG) vào ngữ cảnh LLM; không gửi nguyên file hay cả thư mục |
| AIR-11 | Graph và token | Dùng prompt caching cho phần tĩnh: quy tắc kiến trúc hệ thống, coding convention, schema chung |
| AIR-12 | Chất lượng | Bắt buộc tách lớp Controller → Service → Repository; không gọi tắt qua lớp |
| AIR-13 | Chất lượng | Mọi thay đổi code phải qua phân tích tĩnh (ESLint, SonarQube, Checkstyle tuỳ ngôn ngữ) và unit test trước khi báo hoàn thành cho PMO |
| AIR-14 | Kiểm chứng | Trước khi báo PMO, agent SA phải đối chiếu tường minh yêu cầu gốc của task (task.md, AC trong analysis.md) với diff của PR/commit: mọi thay đổi phải gắn được với một AC (không lệch phạm vi), mọi chỗ gọi tới chữ ký bị đổi phải được kiểm (không hồi quy) |

**Ảnh hưởng đến các phần khác của tài liệu**

- Bỏ bước quét toàn bộ khi khởi tạo; AC-01 và NFR-11 đã sửa theo.
- Nút "Quét toàn bộ dự án" (FR-17) chỉ cập nhật chỉ mục chữ ký trên máy, không gửi code cho agent; ngữ cảnh vẫn theo AIR-09 và AIR-10.
- Lớp lọc bí mật (FR-34 đến FR-36) chạy trên cả đoạn code trích theo AIR-10.

**Nguyên văn chỉ dẫn hệ thống, dùng làm system prompt cho agent**

```markdown
[SYSTEM REQUIREMENT & AI EXECUTION INSTRUCTIONS]

1. CORE EXECUTION WORKFLOW
- Step 1 (PMO): Receive requirements in natural language / business voice.
- Step 2 (SA/BA & AI Agent): Translate requirement into technical spec by querying the local Codebase Index & Dependency Tree-Map. Confirm business logic with PMO.
- Step 3 (Dev & AI Code Assistant): Implement code, execute local static code analysis (Linter/CI), and confirm completed task status back to PMO.

2. REPOSITORY & SYNC RULES
- Always execute `git pull` on the target service repository before processing any task or analysis.
- Do NOT perform a full initial scan or full codebase re-indexing on startup.
- Track updates incrementally using `git diff`: Only scan and analyze modified or newly added files/modules to update the system topology.

3. DEPENDENCY TREE-MAP & TOKEN OPTIMIZATION RULES
- Build and maintain a lightweight text/JSON-based Dependency Graph (AST / Class / Method Signatures) from the incremental scan data.
- Do NOT generate Mermaid charts or visual diagrams to avoid unnecessary overhead and token bloat.
- Limit context retrieval to a maximum depth of 2-3 dependency layers around the target files.
- Inject only Class/Method Signatures and targeted code snippets (RAG) into the LLM context window. Never send full source files or entire directories.
- Strictly adhere to Prompt Caching for static system architecture rules, coding conventions, and global schemas.

4. QUALITY GATES & CONVENTIONS
- Enforce strict layer separation (Controller -> Service -> Repository).
- Every code modification must pass static analysis (ESLint, SonarQube, Checkstyle) and unit tests before confirming task closure to PMO.
```

## Cơ chế tối ưu token

Token được tiết kiệm qua 5 lớp: yêu cầu của PMO được nén thành JSON ngay trên máy trước khi tới LLM, rồi mọi lần gọi LLM chỉ nhận phụ thuộc cục bộ, chữ ký AST và đoạn code liên quan, với phần tĩnh được cache và phiên bị xoá khi task đóng.

**Luồng 6 bước có tối ưu token**

1. **PMO yêu cầu**: PMO nhập yêu cầu tính năng hoặc bug bằng ngôn ngữ tự nhiên; nguyên văn lưu ở task.md cho PMO đọc lại.
2. **Làm sạch cục bộ (không gọi LLM)**: công cụ dùng luật và NLP nhẹ trên máy để bỏ câu chữ thừa, che bí mật và chuyển yêu cầu thành spec.json gồm mã ticket, module đích, loại hành động.
3. **SA phân tích và map**: agent SA truy vấn dependency graph tối đa 2–3 tầng để xác định file bị ảnh hưởng, viết đặc tả giải pháp kỹ thuật ngắn gọn, xác nhận business rule với PMO.
4. **Dev triển khai**: agent Dev viết code đúng theo đặc tả đã chốt, chạy quality gate trên máy (linter, unit test).
5. **SA kiểm chứng code**: agent SA dùng `git diff` đối chiếu code với spec gốc của PMO: không hồi quy, đúng kiến trúc, đúng convention, không lệch phạm vi.
6. **Đóng và xác nhận**: agent SA gửi bằng chứng kiểm chứng (verify.md) cho PMO xác nhận và ký đóng task; sau đó push, chuyển Done và xoá ngữ cảnh phiên của task.

**Năm lớp tối ưu**

| Lớp | Tên | Cách làm | Áp dụng ở |
| --- | --- | --- | --- |
| 1 | Nén đầu vào | Bỏ câu chữ xã giao trong yêu cầu của PMO; chuyển dữ liệu thô thành JSON gọn trước khi đưa cho LLM; nguyên văn chỉ lưu ở task.md, không gửi LLM | Bước 2 |
| 2 | Định tuyến theo graph | Chỉ đưa phụ thuộc của module cục bộ vào ngữ cảnh; duyệt graph tối đa 2–3 tầng quanh tính năng đích | Bước 3, 4, 5 |
| 3 | Trừu tượng code bằng AST | Không gửi nguyên file `.java`, `.ts` hay file nguồn nào; chỉ chữ ký class/method (AST) và đoạn method liên quan (RAG) | Mọi lần gọi LLM |
| 4 | Không sơ đồ hình | Cấm agent sinh Mermaid hay sơ đồ topology; dữ liệu graph chỉ ở dạng JSON/text | Mọi đầu ra của agent |
| 5 | Prompt caching và cô lập phiên | Cache system prompt cho coding convention, quy tắc tách lớp Controller → Service → Repository, DB schema; mỗi câu hỏi một phiên; khi task đóng, xoá ngay ngữ cảnh và bộ nhớ tạm của task, chỉ giữ tài liệu trong folder ticket | Toàn hệ thống |

**spec.json sau bước làm sạch**

```json
{
  "issue_id": "NYX-231",
  "project": "nyxhub",
  "action_type": "FEAT",
  "target_modules": ["auth-service"],
  "summary": "Thêm đăng nhập bằng Google",
  "constraints": [],
  "source": "task.md"
}
```

`action_type` nhận một trong các giá trị: `FEAT`, `MAINT`, `BUG`, `REOPEN_BUG`, `ADD_LOGIC`. Khi luật cục bộ không xác định được module đích, `target_modules` để trống và agent SA tự tìm qua graph ở bước 3, vẫn chỉ nhận spec.json chứ không nhận nguyên văn.

**Nguyên văn đặc tả, dùng làm system prompt cho agent**

```markdown
[SYSTEM ARCHITECTURE & BUSINESS WORKFLOW SPECIFICATION]

1. END-TO-END BUSINESS WORKFLOW
- Step 1 (PMO Request): PMO inputs feature requests or bug issues using plain natural language.
- Step 2 (AI/Local Sanitization): The system automatically parses raw input into a structured JSON Spec using local rules/lightweight NLP (Extracting: Issue_ID, Target_Module, Action_Type).
- Step 3 (SA/BA Analysis & Mapping):
  * SA/BA queries the local Dependency Tree-Map (Max 2-3 depth levels) to pinpoint affected files.
  * SA/BA generates a concise Technical Solution Spec and confirms business rules with PMO.
- Step 4 (Dev Implementation): Dev/AI Assistant writes code based strictly on the Technical Spec and runs local quality gates (Linter, Unit Tests).
- Step 5 (SA/BA Code Verification): SA/BA verifies the generated code against the original PMO spec via `git diff` to ensure zero regression, architectural adherence, and convention compliance.
- Step 6 (Closure & Confirmation): SA/BA reports verification proof to PMO for final confirmation and task sign-off.

2. REPOSITORY & INCREMENTAL SYNC RULES
- Mandatory Synchronization: Always execute `git pull` on the target service repository before processing any new task or analysis.
- No Full Initialization: NEVER perform a full repository scan or full re-indexing on tool startup.
- Incremental Tracking: Use `git diff` to detect modified/added files post-pull and incrementally update only the affected nodes in the local Dependency Tree-Map.

3. TOKEN OPTIMIZATION ENGINE (CORE STRATEGY)
- Layer 1 (Input Compression): Strip all conversational filler from PMO inputs. Convert raw data into compact JSON schemas before feeding to LLMs.
- Layer 2 (Tree-Map Routing): Inject only localized module dependencies into LLM context. Limit graph traversal to a maximum depth of 2-3 layers around the target feature.
- Layer 3 (Code Abstraction via AST):
  * Never transmit full `.java`, `.ts`, or source files.
  * Extract and inject ONLY Class/Method Signatures (AST) and targeted method snippets (RAG) relevant to the task.
- Layer 4 (No Visual Diagrams): Strictly prohibit generating Mermaid charts or visual topology diagrams to prevent token bloat. Maintain graph data purely in lightweight JSON/Text formats.
- Layer 5 (Prompt Caching & Session Isolation):
  * Apply System Prompt Caching for global coding conventions, layered architecture rules (Controller -> Service -> Repository), and DB schemas.
  * Enforce strict session isolation: Purge/flush context memory immediately after a task is closed to prevent context window bloat across different issues.

4. QUALITY GATES & CONVENTIONS ENFORCEMENT
- Architectural Compliance: Enforce strict separation of layers (Controller -> Service -> Repository).
- Automated Validation: Every code change must pass static analysis tools (ESLint, SonarQube, Checkstyle) and unit test suites prior to SA verification.
- Zero Scope Creep: SA verification must explicitly diff commit outputs against PMO requirements before triggering final PMO notification.
```

## Tiêu chí nghiệm thu sản phẩm

Phiên bản đầu được coi là đạt khi chạy trọn 12 kịch bản dưới đây trên một dự án mẫu gồm 3 repo và trên ít nhất một dự án thật (nyxhub hoặc lendbiz).

- [ ] **AC-01 Khởi tạo**: chọn folder dữ liệu, khai báo nhiều repo và nhánh nền, chạy `devmap serve`; mở `localhost:7070/<dự án>` thấy danh sách service; không có lần quét toàn bộ nào tự chạy, graph chỉ cập nhật theo git diff sau khi pull.
- [ ] **AC-02 FEAT trọn luồng**: PMO tạo yêu cầu với mã tự nhập → agent SA hỏi lại, viết analysis.md và plan.md chạm 2 repo → PMO yêu cầu phân tích lại một lần → chốt → agent Dev pull nhánh nền, code theo convention, test pass, commit ở cả 2 repo cùng mã → PMO xác nhận push → Done.
- [ ] **AC-03 Mở lại ticket**: chọn ticket Done, báo bug → ticket mở lại cùng mã, không sinh mã mới; agent SA tìm nguyên nhân, PMO chốt, agent Dev sửa; analysis.md cập nhật logic đúng và có thêm case mới.
- [ ] **AC-04 MAINT**: PMO chốt giải pháp và thứ tự 2 ticket bảo trì; chạy đúng thứ tự, test hồi quy pass.
- [ ] **AC-05 Push**: không lần push nào xảy ra khi PMO chưa xác nhận; không cách cấu hình nào push được vào branch protected.
- [ ] **AC-06 Bí mật**: repo mẫu có `.env` và IP, token viết cứng trong code; agent không đọc được `.env`, kể cả qua lệnh shell; không giá trị bí mật nào có trong đầu vào agent hay trong tài liệu.
- [ ] **AC-07 Token**: hỏi 3 câu liên tiếp trong một ticket; mỗi câu là một phiên mới, không gửi lại chat cũ, câu sau vẫn trả lời đúng nhờ summary.md; token mỗi câu hiển thị trên giao diện.
- [ ] **AC-08 Hai dự án song song**: hai tab nyxhub và lendbiz chạy agent cùng lúc; tiêu đề tab báo trạng thái; không file nào của dự án này bị agent dự án kia đọc hay sửa.
- [ ] **AC-09 Quét toàn bộ**: mặc định tắt; khi PMO bật cho một câu hỏi, agent quét toàn dự án đúng một lần cho câu đó rồi tự tắt lại.

* [ ] **AC-10 Ngữ cảnh gọn và chất lượng**: nhật ký đầu vào của agent chỉ có chữ ký class/method và đoạn code trong 2–3 tầng phụ thuộc, không có nguyên file; phần quy tắc tĩnh được cache; một thay đổi gọi thẳng Controller → Repository bị chặn; ticket chỉ báo hoàn thành sau khi phân tích tĩnh và unit test pass.

- [ ] **AC-11 Kiểm chứng phạm vi**: cố ý để agent Dev sửa thêm một file ngoài yêu cầu và đổi chữ ký một method có nơi khác gọi; agent SA phát hiện cả hai trong verify.md, trả ticket về Dev, PMO không nhận thông báo hoàn thành.

* [ ] **AC-12 Tối ưu token**: yêu cầu có câu chữ xã giao và một token viết trong câu được chuyển thành spec.json gọn, đã che token, trước khi gọi LLM; nhật ký đầu vào của LLM không có nguyên văn yêu cầu; sau khi PMO ký đóng task, ngữ cảnh phiên của task bị xoá và mở lại task vẫn trả lời đúng nhờ tài liệu.

## Lộ trình triển khai

Làm theo 6 phase nối tiếp; luồng ticket, graph và rule phải chạy được bằng tay trước, rồi mới giao cho agent ở phase 5.

&#91;embedded content: lộ trình · 6 phase, mỗi phase một cổng nghiệm thu\]

Làm agent sau cùng vì agent dựa vào cả ba phần trước: ticket để có ngữ cảnh, graph để biết impact, rule để giới hạn quyền. Thời gian từng phase sẽ ước lượng sau khi PMO chốt tài liệu này; bản cài đặt .exe (Electron) để lại sau phase 6 nếu cần.

## Rủi ro và điểm cần PMO chốt

Rủi ro lớn nhất là bí mật lọt sang agent vì agent tự chạy lệnh đọc file; cách giảm là chặn ở tầng công cụ trước mỗi lệnh đọc, không dựa vào việc agent tự giác, và kiểm bằng test tự động.

**Rủi ro**

| Rủi ro | Ảnh hưởng | Cách giảm |
| --- | --- | --- |
| Agent đọc file bí mật bằng lệnh shell, vượt qua lớp lọc | Lộ key, token cho AI | Chặn lệnh đọc file env và file bí mật trước khi chạy; giới hạn lệnh shell agent được dùng; test với repo mẫu |
| Bộ che bỏ sót bí mật có định dạng lạ | Lộ thông tin | Danh sách mẫu cấu hình được, PMO thêm mẫu riêng của dự án; cảnh báo khi phát hiện chuỗi giống bí mật |
| Mỗi câu hỏi một phiên, tài liệu chưa cập nhật đủ | Agent trả lời thiếu ngữ cảnh | Agent bắt buộc cập nhật summary.md và analysis.md cuối mỗi phiên; PMO xem được trong tab Tài liệu |
| Graph thiếu quan hệ giữa service (URL ghép chuỗi, service discovery) | Impact thiếu, sót repo | Edge có độ tin cậy, agent SA kiểm chứng trên code, PMO thêm edge tay, nút quét toàn bộ khi cần |
| PMO là người duy nhất quyết, nhiều ticket chạy song song | Ticket chờ lâu | Chấm báo trên danh sách task, tiêu đề tab và thông báo trình duyệt |
| Agent sửa nhầm repo hoặc nhầm dự án | Hỏng code | Worktree riêng, agent giới hạn trong repo của dự án, chỉ push sau xác nhận |
| Chi phí token khi quét toàn bộ dự án | Tốn chi phí | Mặc định tắt, cảnh báo trước khi chạy, hiển thị token theo câu hỏi |

**Điểm còn cần PMO chốt**

- [ ] Bug không thuộc ticket nào đã làm trong DevMap: tạo ticket loại BUG riêng như tài liệu đang ghi, hay luôn tạo dưới dạng FEAT/MAINT?
- [ ] Mã ticket do PMO nhập: có cần kiểm theo mẫu riêng từng dự án (ví dụ `NYX-` cho nyxhub) không?
- [ ] Mỗi ticket dùng chung một tên branch ở mọi repo bị chạm (ví dụ `feature/NYX-231-...`): đúng ý chưa?
- [ ] Folder dữ liệu DevMap có nên là một repo git riêng để đồng bộ giữa các máy không?
- [ ] Che bí mật: có loại giá trị nào cần giữ nguyên để agent hiểu đúng (ví dụ tên host nội bộ, tên database) không?
- [ ] Coding convention: lấy từ code hiện có của từng dự án, hay PMO có sẵn tài liệu chuẩn để nạp vào?
- [ ] Agent AI chính thức: Claude Code, hay cần agent hoặc model khác?

* [ ] Khi thêm một dự án mới, chỉ mục còn trống nên graph xuyên service chưa có: cho phép một lần quét nền chỉ trích chữ ký (không gửi cho agent), hay để graph lớn dần theo các ticket?
* [ ] SonarQube cần server riêng: dùng SonarQube có sẵn của dự án, hay chỉ dùng công cụ chạy cục bộ (ESLint, Checkstyle, SonarLint)?

- [ ] Làm sạch cục bộ bằng luật và NLP nhẹ khó hiểu chính xác tiếng Việt tự do: khi không tách được module đích, chấp nhận để agent SA tự tìm qua graph (như tài liệu đang ghi), hay hỏi lại PMO một câu ngắn?

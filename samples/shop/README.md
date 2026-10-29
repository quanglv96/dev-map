# samples/shop – dự án mẫu kiểm thử DevMap

Ba service Spring Boot tối giản:

| Service | Vai trò |
| --- | --- |
| order-service | REST `/api/orders`; gọi payment qua REST (`PaymentClient`); publish `order.created` |
| payment-service | REST `/api/payments`, có OpenAPI (`src/main/resources/openapi.yaml`) |
| notification-service | consume `order.created` |

Cài cắm có chủ đích để kiểm nghiệm thu:

- `.env` và IP/token **giả** viết cứng (`PaymentClient`, `application.yml`) – AC-06, AC-12.
- `OrderReportController` gọi thẳng `OrderRepository` – vi phạm tách lớp, AC-10.

Mã nguồn ở đây được repo DevMap theo dõi. Để có 3 repo git riêng (nhánh `main` và `test`, `test` đi trước một commit), chạy:

```sh
sh samples/shop/init-repos.sh            # tạo samples/shop/repos/<service>
```

Sau đó đăng ký dự án mẫu:

```sh
java -jar target/devmap.jar init shop --data D:/devmap-data \
  --repo samples/shop/repos/order-service@test \
  --repo samples/shop/repos/payment-service@main \
  --repo samples/shop/repos/notification-service@main
```

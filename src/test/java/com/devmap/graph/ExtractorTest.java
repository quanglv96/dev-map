package com.devmap.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.devmap.graph.FileIndex.Endpoint;
import com.devmap.graph.FileIndex.HttpCall;
import com.devmap.graph.FileIndex.Ref;
import com.devmap.graph.FileIndex.TableRef;
import com.devmap.graph.FileIndex.TopicRef;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ExtractorTest {

  final SourceIndexer indexer = new SourceIndexer();

  private FileIndex sample(String path) throws Exception {
    return indexer.extract(path, Files.readAllBytes(Path.of("samples/shop", path)));
  }

  private FileIndex code(String path, String text) {
    return indexer.extract(path, text.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void javaSpringServiceAndClients() throws Exception {
    String root = "order-service/src/main/java/com/shop/order/";
    FileIndex controller = sample(root + "web/OrderController.java");
    assertThat(controller.endpoints()).containsExactly(new Endpoint("POST", "/api/orders", "OrderController.create", "code"));
    assertThat(controller.refs()).contains(new Ref("OrderController", "OrderService", "depends"),
        new Ref("OrderController.create", "OrderService.create", "calls"));
    assertThat(controller.symbols())
        .anySatisfy(s -> assertThat(s.signature()).contains("public Order create(@RequestBody CreateOrder req)"));

    FileIndex service = sample(root + "service/OrderService.java");
    assertThat(service.topics()).containsExactly(new TopicRef("order.created", "publish", "OrderService.create"));
    assertThat(service.refs()).contains(new Ref("OrderService.create", "PaymentClient.charge", "calls"),
        new Ref("OrderService.create", "OrderRepository.save", "calls"));

    FileIndex client = sample(root + "client/PaymentClient.java");
    assertThat(client.httpCalls()).contains(new HttpCall("POST", null, "/api/payments", "PaymentClient.charge"));

    assertThat(sample(root + "domain/Order.java").tables()).containsExactly(new TableRef("orders", "Order"));
    assertThat(sample(root + "repository/OrderRepository.java").refs()).contains(new Ref("OrderRepository", "Order", "entity"));
    assertThat(sample(root + "web/OrderReportController.java").refs())
        .contains(new Ref("OrderReportController", "OrderRepository", "depends"));
    assertThat(sample("notification-service/src/main/java/com/shop/notification/listener/OrderCreatedListener.java").topics())
        .containsExactly(new TopicRef("order.created", "consume", "OrderCreatedListener.onOrderCreated"));
  }

  @Test
  void javaFeignClientIsAnOutgoingCall() {
    FileIndex f = code("A.java", """
        @FeignClient(name = "payment-service")
        interface PaymentApi {
          @GetMapping("/api/payments/{id}")
          Payment get(@PathVariable Long id);
        }
        """);
    assertThat(f.endpoints()).isEmpty();
    assertThat(f.httpCalls()).containsExactly(new HttpCall("GET", "payment-service", "/api/payments/{id}", "PaymentApi.get"));
  }

  @Test
  void configComposeAndOpenApi() throws Exception {
    assertThat(sample("order-service/src/main/resources/application.yml").httpCalls())
        .contains(new HttpCall(null, "payment-service", "", "config:base-url"));
    FileIndex compose = sample("order-service/docker-compose.yml");
    assertThat(compose.composeDeps()).contains("order-service->payment-service", "order-service->kafka");
    assertThat(compose.httpCalls()).contains(new HttpCall(null, "payment-service", "", "compose:order-service"));
    assertThat(sample("payment-service/src/main/resources/openapi.yaml").endpoints()).containsExactlyInAnyOrder(
        new Endpoint("POST", "/api/payments", "createPayment", "openapi"),
        new Endpoint("GET", "/api/payments/{id}", "getPayment", "openapi"));
    assertThat(code("api.proto", "service Billing {\n  rpc Charge (Req) returns (Res);\n}\n").endpoints())
        .containsExactly(new Endpoint("RPC", "Billing/Charge", "", "proto"));
  }

  @Test
  void typescriptExpressAndAxios() {
    FileIndex f = code("src/routes.ts", """
        import axios from "axios";
        export async function listOrders(req: Request, res: Response): Promise<void> {
          const r = await axios.get("http://payment-service/api/payments/1");
          res.json(format(r.data));
        }
        class OrderApi { async create(body: OrderBody) { return save(body); } }
        app.post("/api/orders/:id", listOrders);
        """);
    assertThat(f.symbols()).extracting(FileIndex.Symbol::name).contains("listOrders", "OrderApi", "OrderApi.create");
    assertThat(f.symbols()).anySatisfy(s -> assertThat(s.signature())
        .isEqualTo("async function listOrders(req: Request, res: Response): Promise<void>"));
    assertThat(f.endpoints()).containsExactly(new Endpoint("POST", "/api/orders/:id", "listOrders", "code"));
    assertThat(f.httpCalls()).containsExactly(new HttpCall("GET", "payment-service", "/api/payments/1", "listOrders"));
    assertThat(f.refs()).contains(new Ref("listOrders", "format", "calls"), new Ref("OrderApi.create", "save", "calls"));
  }

  @Test
  void pythonFastApiAndGoNetHttp() {
    FileIndex py = code("app/main.py", """
        import requests
        @app.post("/api/refunds")
        def refund(order_id: int) -> dict:
            return requests.post("http://payment-service/api/payments").json()
        """);
    assertThat(py.endpoints()).containsExactly(new Endpoint("POST", "/api/refunds", "refund", "code"));
    assertThat(py.httpCalls()).containsExactly(new HttpCall("POST", "payment-service", "/api/payments", "refund"));
    assertThat(py.symbols()).anySatisfy(s -> assertThat(s.signature()).isEqualTo("def refund(order_id: int) -> dict"));

    FileIndex go = code("cmd/server.go", """
        package main
        type Server struct{}
        func (s *Server) Health(w http.ResponseWriter, r *http.Request) {}
        func main() {
          http.HandleFunc("/health", healthHandler)
          resp, _ := http.Get("http://order-service/api/orders")
          _ = resp
        }
        """);
    assertThat(go.symbols()).extracting(FileIndex.Symbol::name).contains("Server", "Server.Health", "main");
    assertThat(go.endpoints()).containsExactly(new Endpoint("ANY", "/health", "healthHandler", "code"));
    assertThat(go.httpCalls()).containsExactly(new HttpCall("GET", "order-service", "/api/orders", "main"));
  }

  @Test
  void neverSelectsSecretsTestsOrBuildOutput() {
    assertThat(indexer.indexable("order-service/.env")).isFalse();
    assertThat(indexer.indexable("config/prod.key")).isFalse();
    assertThat(indexer.indexable("src/test/java/A.java")).isFalse();
    assertThat(indexer.indexable("web/node_modules/x/index.js")).isFalse();
    assertThat(indexer.indexable("web/src/a.spec.ts")).isFalse();
    assertThat(indexer.indexable("target/classes/application.yml")).isFalse();
    assertThat(indexer.indexable("src/main/java/A.java")).isTrue();
    assertThat(indexer.indexable("src/main/resources/application-prod.yml")).isTrue();
    assertThat(indexer.indexable("api/spec/openapi.yaml")).isTrue();
    assertThat(indexer.indexable("README.md")).isFalse();
  }
}

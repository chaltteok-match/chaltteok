package com.chaltteok.hcx;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HcxOpenAiCompatibilityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();

    private HttpServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/openai/chat/completions", this::respondWithCompletion);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void sendsHcxCompatibleOpenAiRequest() throws Exception {
        var properties = new HcxProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://localhost:" + server.getAddress().getPort() + "/v1/openai");
        properties.setApiKey("test-key");

        var client = new HcxConfiguration().hcxClient(properties);
        var result = client.generate(new HcxPrompt("근거만 사용하세요.", "매출액은?"));

        assertThat(result.content()).isEqualTo("테스트 답변");
        assertThat(result.model()).isEqualTo("HCX-005");
        assertThat(result.totalTokens()).isEqualTo(15);
        assertThat(requestPath.get()).isEqualTo("/v1/openai/chat/completions");
        assertThat(authorization.get()).isEqualTo("Bearer test-key");

        JsonNode body = objectMapper.readTree(requestBody.get());
        assertThat(body.path("model").asText()).isEqualTo("HCX-005");
        assertThat(body.path("messages")).hasSize(2);
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(body.path("messages").get(1).path("role").asText()).isEqualTo("user");
        assertThat(body.has("frequency_penalty")).isFalse();
        assertThat(body.has("presence_penalty")).isFalse();
    }

    private void respondWithCompletion(HttpExchange exchange) throws IOException {
        requestPath.set(exchange.getRequestURI().getPath());
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

        var response = """
                {
                  "id": "chatcmpl-test",
                  "object": "chat.completion",
                  "created": 0,
                  "model": "HCX-005",
                  "choices": [
                    {
                      "index": 0,
                      "message": {"role": "assistant", "content": "테스트 답변"},
                      "finish_reason": "stop"
                    }
                  ],
                  "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
                }
                """.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}

package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.Json;
import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Telegram atom, started the way the runner starts it, against the fixture in {@code tools/fake_telegram.py}.
 *
 * <p>The checks the old {@code tools/smoke.sh} made: the endpoint and the body the Bot API receives, the token kept out
 * of the log, a 200-shaped refusal that still fails the step, the opt-out, and the two missing inputs.
 */
class TelegramNotifyIT {

    private Atom atom;
    private final List<Process> servers = new ArrayList<>();
    private Path log;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
        log = Files.createTempFile(Path.of(System.getProperty("atom.scratch")), "telegram-", ".log");
    }

    @AfterEach
    void tearDown() {
        servers.forEach(Process::destroy);
        servers.clear();
    }

    private int serve(String... extra) throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        List<String> command = new ArrayList<>(List.of("python3",
                Path.of(System.getProperty("atom.module")).resolve("tools/fake_telegram.py").toString(),
                String.valueOf(port), log.toString()));
        command.addAll(List.of(extra));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(
                        Path.of(System.getProperty("atom.scratch")).resolve("fake-telegram.out").toFile()));
        Process server = builder.start();
        servers.add(server);
        for (int attempt = 0; attempt < 100; attempt++) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return port;
            } catch (IOException notYet) {
                Thread.sleep(100);
            }
        }
        throw new IllegalStateException("the fake Telegram did not start");
    }

    /** The last request the fake recorded, parsed: {@code path} and the JSON {@code body} it was sent. */
    private Map<String, Object> lastRequest() throws IOException {
        List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank()).toList();
        assertTrue(!lines.isEmpty(), "the fake never received a request");
        return Json.parseObject(lines.get(lines.size() - 1));
    }

    @Test
    @DisplayName("the send targets the bot endpoint with chat, text and thread, and the token stays out of the log")
    void sendsToTheBotEndpoint() throws Exception {
        int port = serve();
        AtomResult run = atom.input("{\"token\": \"123:SECRET\", \"chatId\": \"-100123\", \"text\": \"build ok\","
                + " \"apiBase\": \"http://127.0.0.1:" + port + "\", \"threadId\": \"7\"}").run();

        run.assertOk();
        assertEquals("4242", run.field("messageId"));
        assertEquals("true", run.field("sent"));

        Map<String, Object> request = lastRequest();
        assertEquals("/bot123:SECRET/sendMessage", request.get("path"));
        Map<String, Object> body = Json.parseObject(String.valueOf(request.get("body")));
        assertEquals("-100123", body.get("chat_id"));
        assertEquals("build ok", body.get("text"));
        assertEquals("7", body.get("message_thread_id"));

        assertTrue(run.console().contains("bot***/sendMessage"),
                "the token is in the log: " + run.console());
    }

    @Test
    @DisplayName("a refused send fails the step and keeps the API's reason")
    void refusedSendFailsTheStep() throws Exception {
        int port = serve("--refuse", "chat not found");
        AtomResult run = atom.input("{\"token\": \"1:S\", \"chatId\": \"0\", \"text\": \"x\","
                + " \"apiBase\": \"http://127.0.0.1:" + port + "\"}").run();

        assertEquals("error", run.status(), "a refusal must fail the step");
        assertTrue(run.file("output.json").contains("chat not found"),
                "the reason is missing: " + run.file("output.json"));
    }

    @Test
    @DisplayName("failOnError=false records the refusal and the step stays green")
    void optOutKeepsTheStepGreen() throws Exception {
        int port = serve("--refuse", "chat not found");
        AtomResult run = atom.input("{\"token\": \"1:S\", \"chatId\": \"0\", \"text\": \"x\","
                + " \"apiBase\": \"http://127.0.0.1:" + port + "\", \"failOnError\": \"false\"}").run();

        assertEquals("success", run.status(), "failOnError=false must not fail");
        assertEquals("false", run.field("sent"));
    }

    @Test
    @DisplayName("a missing token is refused")
    void refusesWithoutAToken() throws Exception {
        AtomResult run = atom.input("{\"chatId\": \"1\", \"text\": \"x\"}").run();

        assertEquals("error", run.status(), "missing token must be refused");
    }

    @Test
    @DisplayName("a missing chat id is refused")
    void refusesWithoutAChatId() throws Exception {
        AtomResult run = atom.input("{\"token\": \"1:S\", \"text\": \"x\"}").run();

        assertEquals("error", run.status(), "missing chatId must be refused");
    }
}

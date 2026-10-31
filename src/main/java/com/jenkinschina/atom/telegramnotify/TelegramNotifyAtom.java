package com.jenkinschina.atom.telegramnotify;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import com.jenkinschina.atom.sdk.Json;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Send a message through a Telegram bot.
 *
 * <p>Not a webhook: the bot API takes the token in the path and the chat in the body, and answers
 * {@code {"ok":false,"description":…}} with HTTP 200 when the token or chat is wrong — so "the request finished"
 * is not "the message was sent".
 */
public class TelegramNotifyAtom implements AtomTask {

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        String token = context.getParam("token", "").trim();
        String chatId = context.getParam("chatId", "").trim();
        String text = context.getParam("text", "");
        String threadId = context.getParam("threadId", "").trim();
        String parseMode = context.getParam("parseMode", "").trim();
        String apiBase = context.getParam("apiBase", "https://api.telegram.org").trim();
        boolean silent = context.getBooleanParam("silent", false);
        boolean noPreview = context.getBooleanParam("disablePreview", false);
        boolean failOnError = context.getBooleanParam("failOnError", true);
        int timeoutSeconds = Math.max(5, Math.min(context.getIntParam("timeoutSeconds", 30), 120));

        if (token.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40001, "token is required (a bot token from @BotFather)");
        }
        if (chatId.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40002, "chatId is required");
        }
        if (text.isBlank()) {
            return AtomResult.error(ErrorType.USER, 40003, "text is required");
        }

        URI uri;
        try {
            uri = URI.create(apiBase.replaceAll("/+$", "") + "/bot" + token + "/sendMessage");
        } catch (RuntimeException e) {
            return AtomResult.error(ErrorType.USER, 40004, "apiBase is not a URL: " + e.getMessage());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("text", text);
        if (!threadId.isEmpty()) {
            body.put("message_thread_id", threadId);
        }
        if (!parseMode.isEmpty()) {
            body.put("parse_mode", parseMode);
        }
        if (silent) {
            body.put("disable_notification", true);
        }
        if (noPreview) {
            body.put("link_preview_options", Map.of("is_disabled", true));
        }
        String payload = Json.write(body);

        // the token is in the path: the log must show the endpoint without it
        context.log("[telegram] POST " + apiBase.replaceAll("/+$", "") + "/bot***/sendMessage chat=" + chatId);

        HttpResponse<String> response;
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("User-Agent", "jenkins-atom-telegram-notify")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.net.http.HttpConnectTimeoutException e) {
            return AtomResult.error(ErrorType.ENVIRONMENT, 40005,
                    "could not connect to the Telegram API within " + timeoutSeconds + "s");
        } catch (java.io.IOException e) {
            return AtomResult.error(ErrorType.THIRD_PART, 40006, "posting to Telegram failed: " + e.getMessage());
        }

        int status = response.statusCode();
        String responseBody = response.body() == null ? "" : response.body();
        String messageId = "";
        String refusal = "";
        try {
            Object parsed = Json.parse(responseBody);
            if (parsed instanceof Map<?, ?> map) {
                if (Boolean.FALSE.equals(map.get("ok"))) {
                    refusal = "Telegram refused the message: " + String.valueOf(map.get("description"));
                }
                Object result = map.get("result");
                if (result instanceof Map<?, ?> resultMap && resultMap.get("message_id") != null) {
                    messageId = String.valueOf(resultMap.get("message_id"));
                }
            }
        } catch (RuntimeException ignored) {
            // a non-JSON body is judged by the status code alone
        }
        if (refusal.isEmpty() && (status < 200 || status >= 300)) {
            refusal = "HTTP " + status + " from the Telegram API: " + excerpt(responseBody);
        }

        if (!refusal.isEmpty() && failOnError) {
            return AtomResult.error(ErrorType.THIRD_PART, 40007, refusal);
        }
        if (!refusal.isEmpty()) {
            context.log("[telegram] warning: " + refusal);
        } else {
            context.log("[telegram] sent, message_id=" + (messageId.isEmpty() ? "?" : messageId));
        }

        return AtomResult.success()
                .data("statusCode", String.valueOf(status))
                .data("messageId", messageId)
                .data("sent", refusal.isEmpty() ? "true" : "false")
                .data("response", excerpt(responseBody));
    }

    private static String excerpt(String body) {
        String oneLine = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + "…" : oneLine;
    }
}

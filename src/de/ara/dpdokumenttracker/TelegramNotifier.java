package de.ara.dpdokumenttracker;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public class TelegramNotifier {


    private static final String BOT_TOKEN =
            System.getenv("TELEGRAM_BOT_TOKEN");

    private static final String CHAT_ID =
            System.getenv("TELEGRAM_CHAT_ID");

    private static final HttpClient HTTP_CLIENT =
            HttpClient.newHttpClient();

    public static void sendMessage(String text)
            throws IOException, InterruptedException {

        String url = "https://api.telegram.org/bot"
                + BOT_TOKEN
                + "/sendMessage";

        String body =
                "chat_id=" + URLEncoder.encode(CHAT_ID, StandardCharsets.UTF_8)
                + "&text=" + URLEncoder.encode(text, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header(
                        "Content-Type",
                        "application/x-www-form-urlencoded"
                )
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );

        System.out.println(
                "Telegram HTTP Status: " + response.statusCode()
        );
    }
}

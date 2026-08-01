package com.wilder0p.lorecleaner.util;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class DiscordWebhook {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private DiscordWebhook() {}

    public static void send(String webhookUrl, String playerName, int itemCount, int barrelCount) {
        if (webhookUrl == null || webhookUrl.isBlank()) return;

        try {
            String json = """
                    {
                      "embeds": [{
                        "title": "LoreCleaner — Inactive Player Processed",
                        "description": "**Player:** %s\\n**Lore items moved:** %d\\n**Barrels placed:** %d\\n\\n_Coordinates intentionally omitted._",
                        "color": 15844367
                      }]
                    }
                    """.formatted(playerName, itemCount, barrelCount);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(8))
                    .build();

            CLIENT.sendAsync(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            // Silently ignore webhook failures
        }
    }
}

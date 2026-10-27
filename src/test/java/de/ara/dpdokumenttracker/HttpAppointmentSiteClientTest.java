package de.ara.dpdokumenttracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class HttpAppointmentSiteClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void returnsFullyBookedForBusyMessage() throws IOException {
        String html = "<html>Наразі всі місця зайняті.</html>";

        startServer(exchange -> respond(exchange, 200, html));

        HttpAppointmentSiteClient client = newClient();
        AvailabilityResult result = client.checkAvailability();

        assertEquals(AppointmentStatus.FULLY_BOOKED, result.getStatus());
        assertEquals(List.of(), result.getAvailableDates());
        assertEquals(html, result.getPageHtml());
    }

    @Test
    void requestsAndExtractsAvailableDaysForBookingForm()
            throws IOException {

        AtomicInteger requestCount = new AtomicInteger();
        AtomicReference<String> postBody = new AtomicReference<>();
        AtomicReference<String> secFetchSite = new AtomicReference<>();
        AtomicReference<String> origin = new AtomicReference<>();

        String html = """
                <html>
                  <form id="services"
                        x-data="form({&quot;csrf&quot;:&quot;csrf-token&quot;,&quot;center&quot;:&quot;1&quot;})">
                  </form>
                </html>
                """;

        startServer(exchange -> {
            requestCount.incrementAndGet();

            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, html);
                return;
            }

            postBody.set(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
            secFetchSite.set(
                    exchange.getRequestHeaders().getFirst("Sec-Fetch-Site")
            );
            origin.set(exchange.getRequestHeaders().getFirst("Origin"));

            respond(
                    exchange,
                    200,
                    "{\"days\":[{\"date\":\"2026-11-01\"},"
                            + "{\"date\":\"2026-11-02\"}]}"
            );
        });

        HttpAppointmentSiteClient client = newClient();
        AvailabilityResult result = client.checkAvailability();

        assertEquals(AppointmentStatus.AVAILABLE, result.getStatus());
        assertEquals(
                List.of("2026-11-01", "2026-11-02"),
                result.getAvailableDates()
        );
        assertEquals(html, result.getPageHtml());
        assertEquals(2, requestCount.get());
        assertEquals("same-origin", secFetchSite.get());
        assertEquals(baseUri().toString(), origin.get());
        assertTrue(postBody.get().contains("name=\"form\"\r\n\r\ndays"));
        assertTrue(postBody.get().contains(
                "name=\"ServiceCenterId\"\r\n\r\n1"
        ));
        assertTrue(postBody.get().contains(
                "name=\"ServiceId\"\r\n\r\n4"
        ));
        assertTrue(postBody.get().contains(
                "name=\"csrf-token\"\r\n\r\n1"
        ));
    }

    @Test
    void mapsForbiddenResponseAndPreservesResponseHtml()
            throws IOException {

        String html = "<html>Forbidden</html>";
        startServer(exchange -> respond(exchange, 403, html));

        HttpAppointmentSiteClient client = newClient();
        AvailabilityResult result = client.checkAvailability();

        assertEquals(
                AppointmentStatus.ACCESS_FORBIDDEN,
                result.getStatus()
        );
        assertEquals(html, result.getPageHtml());
    }

    @Test
    void returnsPageChangedForUnrecognizedSuccessfulPage()
            throws IOException {

        String html = "<html>Unexpected page</html>";
        startServer(exchange -> respond(
                exchange,
                200,
                html
        ));

        AvailabilityResult result = newClient().checkAvailability();

        assertEquals(AppointmentStatus.PAGE_CHANGED, result.getStatus());
        assertEquals(html, result.getPageHtml());
    }

    @Test
    void returnsNullHtmlWhenGetFailsBeforeReceivingResponse()
            throws IOException {

        startServer(exchange -> respond(exchange, 200, "unused"));
        HttpAppointmentSiteClient client = newClient();
        server.stop(0);
        server = null;

        AvailabilityResult result = client.checkAvailability();

        assertEquals(AppointmentStatus.NETWORK_ERROR, result.getStatus());
        assertNull(result.getPageHtml());
    }

    @Test
    void preservesGetHtmlWhenDaysRequestFails() throws IOException {
        String html = bookingFormHtml();

        startServer(exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, html);
            }
        });

        AvailabilityResult result = newClient().checkAvailability();

        assertEquals(AppointmentStatus.NETWORK_ERROR, result.getStatus());
        assertEquals(html, result.getPageHtml());
    }

    @Test
    void consecutiveChecksNeverReuseHtmlFromPreviousCheck()
            throws IOException {

        AtomicInteger requestCount = new AtomicInteger();
        String firstHtml = "<html>Наразі всі місця зайняті.</html>";

        startServer(exchange -> {
            if (requestCount.incrementAndGet() == 1) {
                respond(exchange, 200, firstHtml);
            }
        });

        HttpAppointmentSiteClient client = newClient();

        AvailabilityResult firstResult = client.checkAvailability();
        AvailabilityResult secondResult = client.checkAvailability();

        assertEquals(firstHtml, firstResult.getPageHtml());
        assertEquals(AppointmentStatus.NETWORK_ERROR, secondResult.getStatus());
        assertNull(secondResult.getPageHtml());
    }

    private HttpAppointmentSiteClient newClient() {
        return new HttpAppointmentSiteClient(
                baseUri().resolve("/solutions/e-queue").toString(),
                Duration.ofSeconds(2),
                Duration.ofSeconds(2)
        );
    }

    private String bookingFormHtml() {
        return """
                <html>
                  <form id="services"
                        x-data="form({&quot;csrf&quot;:&quot;csrf-token&quot;,&quot;center&quot;:&quot;1&quot;})">
                  </form>
                </html>
                """;
    }

    private URI baseUri() {
        return URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort()
        );
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );
        server.createContext("/solutions/e-queue", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private void respond(
            HttpExchange exchange,
            int status,
            String body) throws IOException {

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}

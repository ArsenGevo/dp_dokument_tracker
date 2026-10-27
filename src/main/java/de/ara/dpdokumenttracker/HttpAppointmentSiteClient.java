package de.ara.dpdokumenttracker;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HttpAppointmentSiteClient implements AppointmentSiteClient {

    private static final String DEFAULT_URL =
            "https://munich.pasport.org.ua/solutions/e-queue";

    private static final String BUSY_MESSAGE =
            "Наразі всі місця зайняті.";

    private static final String SERVICES_FORM = "id=\"services\"";

    private static final Duration DEFAULT_REQUEST_TIMEOUT =
            Duration.ofSeconds(60);

    private static final Duration DEFAULT_CONNECT_TIMEOUT =
            Duration.ofSeconds(5);

    private static final Logger LOGGER = TrackerLogger.getLogger();

    private final String url;
    private final Duration requestTimeout;
    private final HttpClient httpClient;

    public HttpAppointmentSiteClient() {
        this(
                DEFAULT_URL,
                DEFAULT_CONNECT_TIMEOUT,
                DEFAULT_REQUEST_TIMEOUT
        );
    }

    HttpAppointmentSiteClient(
            String url,
            Duration connectTimeout,
            Duration requestTimeout) {

        CookieManager cookieManager =
                new CookieManager(null, CookiePolicy.ACCEPT_ALL);

        this.url = url;
        this.requestTimeout = requestTimeout;
        this.httpClient = HttpClient.newBuilder()
                //.version(HttpClient.Version.HTTP_1_1)
                .cookieHandler(cookieManager)
                .connectTimeout(connectTimeout)
                .build();
    }

    @Override
    public AvailabilityResult checkAvailability() {

        String pageHtml = null;

        try {
            HttpResponse<String> response = loadPage();

            int httpStatusCode = response.statusCode();
            pageHtml = response.body();

            AvailabilityResult result;

            if (httpStatusCode >= 200 && httpStatusCode < 300) {
                result = checkStatus(pageHtml);
            } else if (httpStatusCode == 429) {
                result = result(AppointmentStatus.RATE_LIMITED, pageHtml);
            } else if (httpStatusCode == 403) {
                result = result(AppointmentStatus.ACCESS_FORBIDDEN, pageHtml);
            } else if (httpStatusCode >= 500 && httpStatusCode < 600) {
                result = result(AppointmentStatus.SERVER_ERROR, pageHtml);
            } else {
                result = result(AppointmentStatus.ERROR, pageHtml);
            }

            logHttpResult(httpStatusCode, result.getStatus());
            return result;

        } catch (IOException e) {
            LOGGER.log(
                    Level.WARNING,
                    "NETWORK_ERROR | " + e.getClass().getSimpleName()
            );

            return result(AppointmentStatus.NETWORK_ERROR, pageHtml);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            LOGGER.log(
                    Level.WARNING,
                    "Check thread was interrupted",
                    e
            );

            return result(AppointmentStatus.ERROR, pageHtml);

        } catch (Exception e) {
            LOGGER.log(
                    Level.WARNING,
                    "UNEXPECTED_ERROR | " + e.getClass().getSimpleName(),
                    e
            );

            return result(AppointmentStatus.ERROR, pageHtml);
        }
    }

    private HttpResponse<String> loadPage()
            throws IOException, InterruptedException {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(requestTimeout)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );

        System.out.println("HTTP version: " + response.version());
        return response;
    }

    private AvailabilityResult checkStatus(String html) {

        // MODE A: BUSY_MESSAGE
        if (html.contains(BUSY_MESSAGE)) {
            return result(AppointmentStatus.FULLY_BOOKED, html);
        }

        // MODE B: BOOKING_FORM
        if (isFormMode(html)) {
            LOGGER.info("PAGE_MODE | BOOKING_FORM");

            String csrf = extractCsrf(html);
            String centerId = extractCenterId(html);

            if (csrf == null || centerId == null) {
                return result(AppointmentStatus.PAGE_CHANGED, html);
            }

            try {
                // API-response check
                return checkDays(csrf, centerId, html);

            } catch (IOException e) {
                LOGGER.warning(
                        "DAYS_API_NETWORK_ERROR | "
                                + e.getClass().getSimpleName()
                );

                return result(AppointmentStatus.NETWORK_ERROR, html);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.warning("DAYS_API_INTERRUPTED");

                return result(AppointmentStatus.ERROR, html);
            }
        }

        // UNKNOWN PAGE
        return result(AppointmentStatus.PAGE_CHANGED, html);
    }

    private boolean isFormMode(String html) {
        return html.contains(SERVICES_FORM);
    }

    private String extractCsrf(String html) {
        String normalizedHtml = normalizeHtml(html);
        Pattern pattern = Pattern.compile("\"csrf\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(normalizedHtml);

        if (matcher.find()) {
            return matcher.group(1);
        }

        return null;
    }

    private String extractCenterId(String html) {
        String normalizedHtml = normalizeHtml(html);
        Pattern pattern = Pattern.compile("\"center\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(normalizedHtml);

        if (matcher.find()) {
            return matcher.group(1);
        }

        return null;
    }

    private String normalizeHtml(String html) {
        return html.replace("&quot;", "\"");
    }

    private AvailabilityResult checkDays(
            String csrf,
            String centerId,
            String pageHtml
    ) throws IOException, InterruptedException {

        String boundary =
                "----DpTrackerBoundary" + System.currentTimeMillis();

        String body = createMultipartBody(boundary, csrf, centerId);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(requestTimeout)
                .header(
                        "Content-Type",
                        "multipart/form-data; boundary=" + boundary
                )
                .header("Origin", origin())
                .header("Referer", url)
                .header("Accept", "application/json, text/plain, */*")
                // Required by the back-end for the same-origin form request
                .header("Sec-Fetch-Site", "same-origin")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );

        String responseBody = response.body().trim();
        int apiStatusCode = response.statusCode();

        LOGGER.info("DAYS_API | HTTP " + apiStatusCode);

        if (apiStatusCode == 429) {
            return result(AppointmentStatus.RATE_LIMITED, pageHtml);
        }

        if (apiStatusCode == 403) {
            return result(AppointmentStatus.ACCESS_FORBIDDEN, pageHtml);
        }

        if (apiStatusCode >= 500 && apiStatusCode < 600) {
            return result(AppointmentStatus.SERVER_ERROR, pageHtml);
        }

        if (apiStatusCode == 200) {
            if (!responseBody.contains("\"days\"")) {
                LOGGER.warning("DAYS_API | UNEXPECTED_RESPONSE");
                return result(AppointmentStatus.ERROR, pageHtml);
            }

            List<String> availableDates =
                    extractAvailableDates(responseBody);

            if (availableDates.isEmpty()) {
                LOGGER.info("DAYS_API | days=0");
                return result(AppointmentStatus.FULLY_BOOKED, pageHtml);
            }

            LOGGER.info(
                    "DAYS_API | days="
                            + availableDates.size()
                            + " | "
                            + String.join(", ", availableDates)
            );

            return new AvailabilityResult(
                    AppointmentStatus.AVAILABLE,
                    availableDates,
                    pageHtml
            );
        }

        return result(AppointmentStatus.ERROR, pageHtml);
    }

    private String origin() {
        URI uri = URI.create(url);
        return uri.getScheme() + "://" + uri.getAuthority();
    }

    private String createMultipartBody(
            String boundary,
            String csrf,
            String centerId) {

        String lineBreak = "\r\n";
        StringBuilder body = new StringBuilder();

        addFormField(body, boundary, "form", "days", lineBreak);
        addFormField(
                body,
                boundary,
                "ServiceCenterId",
                centerId,
                lineBreak
        );
        addFormField(body, boundary, "ServiceId", "4", lineBreak);
        addFormField(body, boundary, csrf, "1", lineBreak);

        body.append("--")
                .append(boundary)
                .append("--")
                .append(lineBreak);

        return body.toString();
    }

    private void addFormField(
            StringBuilder body,
            String boundary,
            String name,
            String value,
            String lineBreak) {

        body.append("--")
                .append(boundary)
                .append(lineBreak);

        body.append("Content-Disposition: form-data; name=\"")
                .append(name)
                .append("\"")
                .append(lineBreak);

        body.append(lineBreak);
        body.append(value).append(lineBreak);
    }

    private List<String> extractAvailableDates(String responseBody) {
        List<String> dates = new ArrayList<>();
        Pattern pattern =
                Pattern.compile("\"date\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(responseBody);

        while (matcher.find()) {
            dates.add(matcher.group(1));
        }

        return dates;
    }

    private void logHttpResult(
            int httpStatusCode,
            AppointmentStatus status) {

        String message = "HTTP " + httpStatusCode + " | " + status;

        if (httpStatusCode >= 200 && httpStatusCode < 300) {
            LOGGER.info(message);
        } else {
            LOGGER.warning(message);
        }
    }

    private AvailabilityResult result(
            AppointmentStatus status,
            String pageHtml) {

        return new AvailabilityResult(status, List.of(), pageHtml);
    }
}

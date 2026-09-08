package de.ara.dpdokumenttracker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import java.time.Instant;

public class Main {

	private static final String URL = "https://munich.pasport.org.ua/solutions/e-queue";
	 
	//private static final String URL = "http://127.0.0.1:5500/index.html";

	private static final String BUSY_MESSAGE = "Наразі всі місця зайняті.";

	private static final String SERVICES_FORM = "id=\"services\"";
		
	private static final CookieManager COOKIE_MANAGER =
	        new CookieManager(null, CookiePolicy.ACCEPT_ALL);
	
	private static final Duration REQUEST_TIMEOUT =
	        Duration.ofSeconds(60);
	private static final Duration CONNECT_TIMEOUT =
	        Duration.ofSeconds(5);

	private static final HttpClient HTTP_CLIENT = 
			HttpClient.newBuilder()
			//.version(HttpClient.Version.HTTP_1_1)
			.cookieHandler(COOKIE_MANAGER)
			.connectTimeout(CONNECT_TIMEOUT)
			.build();

	private static final Logger LOGGER = TrackerLogger.getLogger();


	enum PageMode {
	    BUSY_MESSAGE,
	    BOOKING_FORM,
	    UNKNOWN
	}

	private static AppointmentStatus previousStatus = null;
	
	private static List<String> previousAvailableDates = List.of();
	
	private static int consecutiveForbiddenCount = 0;
	
	private static final int FORBIDDEN_BACKOFF_THRESHOLD = 2;
	private static final Duration FORBIDDEN_BACKOFF_DURATION =
	        Duration.ofMinutes(30);
	private static Instant forbiddenBackoffUntil =
	        Instant.EPOCH;
	private static boolean forbiddenAlertSent = false;
	
	private static final Duration RATE_LIMIT_BACKOFF_DURATION =
	        Duration.ofMinutes(30);
	private static Instant rateLimitBackoffUntil =
	        Instant.EPOCH;
	private static boolean rateLimitAlertSent = false;
	
	private static final int TECHNICAL_FAILURE_ALERT_THRESHOLD = 3;
	private static int consecutiveTechnicalFailureCount = 0;
	private static boolean technicalFailureAlertSent = false;
	
	

	
	private static ScheduledExecutorService scheduler;

	public static void main(String[] args) {

		
		scheduler = Executors.newSingleThreadScheduledExecutor();

		scheduler.scheduleWithFixedDelay(Main::safeCheckOnce, 0, 2, TimeUnit.MINUTES);

		//scheduler.scheduleWithFixedDelay(Main::safeCheckOnce, 0, 30, TimeUnit.SECONDS);

	}
	
	private static void safeCheckOnce() {

		try {
			checkOnce();

		} catch (Exception e) {

			e.printStackTrace();

			LOGGER.log(Level.SEVERE, "Unexpected error in scheduled check", e);
		}
	}
	
	public static void checkOnce() {

		AvailabilityResult result;
		String html = null;
		
		if (Instant.now().isBefore(forbiddenBackoffUntil)) {

		    LOGGER.info(
		            "BACKOFF_ACTIVE | until="
		            + forbiddenBackoffUntil
		    );

		    return;
		}
		
		if (Instant.now().isBefore(rateLimitBackoffUntil)) {

		    LOGGER.info(
		            "RATE_LIMIT_BACKOFF_ACTIVE | until="
		            + rateLimitBackoffUntil
		    );

		    return;
		}

		try {

			HttpResponse<String> response = loadPage();

			int httpStatusCode = response.statusCode();
			
			html = response.body();

			if (httpStatusCode >= 200 && httpStatusCode < 300) {

				result = checkStatus(response.body());

			} else if (httpStatusCode == 429) {

				result = new AvailabilityResult(
	                    AppointmentStatus.RATE_LIMITED,
	                    List.of()
	            );

			} else if (httpStatusCode == 403) {

				result = new AvailabilityResult(
	                    AppointmentStatus.ACCESS_FORBIDDEN,
	                    List.of()
	            );

			} else if (httpStatusCode >= 500 && httpStatusCode < 600) {

				result = new AvailabilityResult(
	                    AppointmentStatus.SERVER_ERROR,
	                    List.of()
	            );

			} else {

				result = new AvailabilityResult(
	                    AppointmentStatus.ERROR,
	                    List.of()
	            );
			}

			logHttpResult(httpStatusCode,
	                result.getStatus()
			        );

		} catch (IOException e) {

			result = new AvailabilityResult(
	                AppointmentStatus.NETWORK_ERROR,
	                List.of()
	        );

			LOGGER.log(Level.WARNING, "NETWORK_ERROR | " + e.getClass().getSimpleName());
		} catch (InterruptedException e) {

			Thread.currentThread().interrupt();

			 result = new AvailabilityResult(
		                AppointmentStatus.ERROR,
		                List.of()
		        );

			LOGGER.log(Level.WARNING, "Check thread was interrupted", e);

		}

		catch (Exception e) {

			 result = new AvailabilityResult(
		                AppointmentStatus.ERROR,
		                List.of()
		        );
			 
			 LOGGER.log(
			            Level.WARNING,
			            "UNEXPECTED_ERROR | "
			            + e.getClass().getSimpleName(),
			            e
			    );
		}
		
		AppointmentStatus status = result.getStatus();
		
		// HTTP 403 response backoff: 
		if (status == AppointmentStatus.ACCESS_FORBIDDEN) {
			
			consecutiveTechnicalFailureCount = 0;
			technicalFailureAlertSent = false;
			
			consecutiveForbiddenCount++;

		    LOGGER.warning(
		            "ACCESS_FORBIDDEN | consecutive="
		            + consecutiveForbiddenCount
		    );
		    
		    if (consecutiveForbiddenCount
		            >= FORBIDDEN_BACKOFF_THRESHOLD) {
		    	
		    	forbiddenBackoffUntil =
		                Instant.now()
		                .plus(FORBIDDEN_BACKOFF_DURATION);

		        LOGGER.warning(
		                "ACCESS_FORBIDDEN | BACKOFF_STARTED | until="
		                + forbiddenBackoffUntil
		        );
		        
		        if (!forbiddenAlertSent) {

		            notifyStatusChange(result);

		            forbiddenAlertSent = true;
		        }
		    }
		    return;
		}
		
				
		boolean successfulCheck =
		        status == AppointmentStatus.FULLY_BOOKED
		        || status == AppointmentStatus.AVAILABLE
		        || status == AppointmentStatus.PAGE_CHANGED;

		if (successfulCheck) {

		    if (consecutiveForbiddenCount > 0) {

		        LOGGER.info(
		                "ACCESS_RESTORED | after="
		                + consecutiveForbiddenCount
		                + " forbidden responses"
		        );
		    }
		    
		    if (!rateLimitBackoffUntil.equals(Instant.EPOCH)) {

		        LOGGER.info("RATE_LIMIT_RESTORED");

		        rateLimitBackoffUntil = Instant.EPOCH;
		    }
		    
		    if (consecutiveTechnicalFailureCount > 0) {

		        LOGGER.info(
		                "TECHNICAL_FAILURE_RESTORED | after="
		                + consecutiveTechnicalFailureCount
		                + " failed checks"
		        );
		    }
		    
		    rateLimitAlertSent = false;
		    forbiddenAlertSent = false;
		    
		    consecutiveTechnicalFailureCount = 0;
		    technicalFailureAlertSent = false;
		}
		
		consecutiveForbiddenCount = 0;
		forbiddenBackoffUntil = Instant.EPOCH; 
		
		// HTTP 429 response backoff: 
		if (status == AppointmentStatus.RATE_LIMITED) {
			
			consecutiveTechnicalFailureCount = 0;
			technicalFailureAlertSent = false;

		    rateLimitBackoffUntil =
		            Instant.now()
		            .plus(RATE_LIMIT_BACKOFF_DURATION);

		    LOGGER.warning(
		            "RATE_LIMITED | BACKOFF_STARTED | until="
		            + rateLimitBackoffUntil
		    );
		    
		    if (!rateLimitAlertSent) {

		        notifyStatusChange(result);

		        rateLimitAlertSent = true;
		    }

		    return;
		}
		
		if (isGeneralTechnicalFailure(status)) {

		    consecutiveTechnicalFailureCount++;

		    LOGGER.warning(
		            "TECHNICAL_FAILURE | status="
		            + status
		            + " | consecutive="
		            + consecutiveTechnicalFailureCount
		    );
		    
		    if (consecutiveTechnicalFailureCount
		            >= TECHNICAL_FAILURE_ALERT_THRESHOLD
		            && !technicalFailureAlertSent) {

		        notifyStatusChange(result);

		        technicalFailureAlertSent = true;
		    }

		    return;
		}
		
		if (isTechnicalFailure(status)) {
		    return;
		}
		
		List<String> currentDates = result.getAvailableDates();		
		
		boolean statusChanged =
		        status != previousStatus;
		
		boolean datesChanged =
		        status == AppointmentStatus.AVAILABLE
		        && !currentDates.equals(previousAvailableDates);
		
		if (statusChanged || datesChanged) {

		    if (statusChanged) {

		        LOGGER.info(
		                "STATUS_CHANGE | "
		                + previousStatus
		                + " -> "
		                + status
		        );
		    }
		    
		    if (datesChanged) {

		        LOGGER.info(
		                "AVAILABLE_DATES_CHANGE | "
		                + previousAvailableDates
		                + " -> "
		                + currentDates
		        );
		    }
		    
		    if (status == AppointmentStatus.PAGE_CHANGED) {
		        saveSnapshot(html);
		    }
		   
			
			notifyStatusChange(result);
			
			previousStatus = status;
			previousAvailableDates = List.copyOf(currentDates);
		}

	}

	private static HttpResponse<String> loadPage() throws IOException, InterruptedException {

		HttpRequest request = HttpRequest.newBuilder().uri(URI.create(URL)).timeout(REQUEST_TIMEOUT).GET()
				.build();

		HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

		return response;
	}

	private static AvailabilityResult checkStatus(String html) {
		
		//MODE A: BUSY_MESSAGE
		if (html.contains(BUSY_MESSAGE)) {
			
			return new AvailabilityResult(
		            AppointmentStatus.FULLY_BOOKED,
		            List.of()
		    ); 
			
			} 
		
		//MODE B: BOOKING_FORM
		if (isFormMode(html)) {
				
				LOGGER.info("PAGE_MODE | BOOKING_FORM");
				
				String csrf = extractCsrf(html);
			    String centerId = extractCenterId(html);
			    
			    if (csrf == null || centerId == null) {
		            return new AvailabilityResult(
		                    AppointmentStatus.PAGE_CHANGED,
		                    List.of()
		            );
		        }			    
			        
			        try {
			        	
			        	//API-responce check:
			           return checkDays(csrf, centerId);

			        } catch (IOException e) {

			        	LOGGER.warning(
			                    "DAYS_API_NETWORK_ERROR | "
			                    + e.getClass().getSimpleName()
			            );

			            return new AvailabilityResult(
			                    AppointmentStatus.NETWORK_ERROR,
			                    List.of()
			            );

			        } catch (InterruptedException e) {

			            Thread.currentThread().interrupt();

			            LOGGER.warning(
			                    "DAYS_API_INTERRUPTED"
			            );

			            return new AvailabilityResult(
			                    AppointmentStatus.ERROR,
			                    List.of()
	                    );
			        }
		}
			
				// UNKNOW PAGE
				return new AvailabilityResult(
				        AppointmentStatus.PAGE_CHANGED,
				        List.of()
				);
					
	}
	
	private static boolean isTechnicalFailure(
	        AppointmentStatus status) {

	    return status == AppointmentStatus.ACCESS_FORBIDDEN
	            || status == AppointmentStatus.NETWORK_ERROR
	            || status == AppointmentStatus.RATE_LIMITED
	            || status == AppointmentStatus.SERVER_ERROR
	            || status == AppointmentStatus.ERROR;
	}
	
	private static boolean isGeneralTechnicalFailure(
	        AppointmentStatus status) {

	    return status == AppointmentStatus.NETWORK_ERROR
	            || status == AppointmentStatus.SERVER_ERROR
	            || status == AppointmentStatus.ERROR;
	}
	
	private static String normalizeHtml(String html) {
		return html.replace("&quot;", "\"");
	}
	
	private static boolean isFormMode(String html) {
		return html.contains(SERVICES_FORM);
	}

	private static String extractCsrf(String html) {
		
		String normalizedHtml = normalizeHtml(html);

		Pattern pattern = Pattern.compile("\"csrf\"\\s*:\\s*\"([^\"]+)\"");

		Matcher matcher = pattern.matcher(normalizedHtml);

		if (matcher.find()) {
			return matcher.group(1);
		}

		return null;
	}

	private static String extractCenterId(String html) {
		
		String normalizedHtml = normalizeHtml(html);

		Pattern pattern = Pattern.compile("\"center\"\\s*:\\s*\"([^\"]+)\"");

		Matcher matcher = pattern.matcher(normalizedHtml);

		if (matcher.find()) {
			return matcher.group(1);
		}

		return null;
	}

	private static String getStatusMessage(AppointmentStatus status) {

		switch (status) {

		case FULLY_BOOKED:
			return "DP Dokument: В данный момент нет опубликованных дат для записи.";

		case PAGE_CHANGED:
			return "DP Dokument: состояние страницы изменилось!";

		case RATE_LIMITED:
			return "DP Dokument: превышение лимита запросов! (HTTP 429)";

		case ACCESS_FORBIDDEN:
			return "DP Dokument: доступ запрещён сервером (HTTP 403).";

		case SERVER_ERROR:
			return "DP Dokument: ошибка сервера (HTTP 500).";

		case NETWORK_ERROR:
			return "Oшибка сети или соединения.";

		case ERROR:
			return "Непредвиденная ошибка программы.";
			
		case AVAILABLE:
			return "DP Dokument: появились даты для записи. Перейдите на сайт!";

		default:
			return "Неизвестное состояние.";
		}
	}

	private static void notifyStatusChange(AvailabilityResult result) {
		
		AppointmentStatus status = result.getStatus();
		
		List<String> availableDates = result.getAvailableDates();
		
		String message;
		
		if (status == AppointmentStatus.AVAILABLE) {
			if (availableDates.isEmpty()) {
		        message =
		                time()
		                + " 🔥 Є доступні дати для запису. Перейдіть на сайт.";
		    } else {

		        String datesText =
		                String.join("\n", availableDates);

		        message =
		                time()
		                + " 🔥 Є доступні дати для запису:\n"
		                + datesText;
		    }
		} else {
			message = time()
					+ " " + getStatusMessage(status);
		}

		// Message for telegram in console: System.out.println(message);

		try {

			TelegramNotifier.sendMessage(message);

		} catch (IOException e) {

			LOGGER.warning("TELEGRAM_SEND_ERROR | " + e.getClass().getSimpleName());

		} catch (InterruptedException e) {

			Thread.currentThread().interrupt();

			LOGGER.warning("TELEGRAM_SEND_INTERRUPTED");
		}
	}

	private static void saveSnapshot(String html) {

		try {

			Path directory = Path.of("snapshots");

			Files.createDirectories(directory);

			String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));

			String fileName = timestamp + "_PAGE_CHANGED.html";

			Path file = directory.resolve(fileName);

			Files.writeString(file, html, StandardCharsets.UTF_8);

			LOGGER.info("SNAPSHOT_SAVED | " + file.toAbsolutePath());

		} catch (IOException e) {

			LOGGER.warning("SNAPSHOT_SAVE_ERROR | " + e.getClass().getSimpleName());
		}
	}

	private static void logHttpResult(int httpStatusCode, AppointmentStatus status) {

		String message = "HTTP " + httpStatusCode + " | " + status;

		if (httpStatusCode >= 200 && httpStatusCode < 300) {
			LOGGER.info(message);
		} else {
			LOGGER.warning(message);
		}
	}

	private static LocalTime time() {
		LocalTime now = LocalTime.now();
		LocalTime time = now.truncatedTo(ChronoUnit.MINUTES);
		return time;
	}
	
	private static AvailabilityResult checkDays(
	        String csrf,
	        String centerId
	) throws IOException, InterruptedException {

	    String boundary =
	            "----DpTrackerBoundary" + System.currentTimeMillis();

	    String body =
	            createMultipartBody(boundary, csrf, centerId);

	    HttpRequest request =
	            HttpRequest.newBuilder()
	                    .uri(URI.create(URL))
	                    .timeout(REQUEST_TIMEOUT)
	                    .header(
	                            "Content-Type",
	                            "multipart/form-data; boundary=" + boundary
	                    )
	                    .header(
	                            "Origin",
	                            "https://munich.pasport.org.ua"
	                    )
	                    .header(
	                            "Referer",
	                            URL
	                    )
	                    .header(
	                            "Accept",
	                            "application/json, text/plain, */*"
	                    )
	                    
	                    // Required by the back-end for the same-origin form request
	                    .header("sec-fetch-site", "same-origin")
	                    
	                    .POST(
	                            HttpRequest.BodyPublishers.ofString(body)
	                    )
	                    .build();


	    HttpResponse<String> response =
	            HTTP_CLIENT.send(
	                    request,
	                    HttpResponse.BodyHandlers.ofString()
	            );
	    
	    String responseBody = response.body().trim();
	    
	    
	    System.out.println(
	            "DAYS API HTTP: " + response.statusCode()
	    );

	    System.out.println(
	            "DAYS API RESPONSE: " + response.body()
	    );
	    
	    int apiStatusCode = response.statusCode();
	    
	    if (apiStatusCode == 429) {
	        return new AvailabilityResult(
	        		AppointmentStatus.RATE_LIMITED,
	                List.of()
	        		);
	    }

	    if (apiStatusCode == 403) {
	        return new AvailabilityResult(
	                AppointmentStatus.ACCESS_FORBIDDEN,
	                List.of()
	        );
	    }

	    if (apiStatusCode >= 500 && apiStatusCode < 600) {
	        return new AvailabilityResult(
	                AppointmentStatus.SERVER_ERROR,
	                List.of()
	        );
	    }
	    
	    if (response.statusCode() == 200) {

	        if (responseBody.equals("{\"days\":[]}")) {
	        	
	        	LOGGER.info("DAYS_API | days=0");
	            return new AvailabilityResult(
	                    AppointmentStatus.FULLY_BOOKED,
	                    List.of()
                );
	        }
	        
	        if (!responseBody.contains("\"days\"")) {

	            LOGGER.warning(
	                    "DAYS_API | UNEXPECTED_RESPONSE"
	            );

	            return new AvailabilityResult(
	                    AppointmentStatus.ERROR,
	                    List.of()
	            );
	        }
	        
	        List<String> availableDates =
	                extractAvailableDates(responseBody);
	        
	        if (!availableDates.isEmpty()) {

	            LOGGER.info(
	                    "DAYS_API | days="
	                    + availableDates.size()
	                    + " | "
	                    + String.join(", ", availableDates)
	            );

	            return new AvailabilityResult(
	                    AppointmentStatus.AVAILABLE,
	                    availableDates
	            );
	        }

	        LOGGER.warning(
	                "DAYS_API | UNKNOWN_RESPONSE"
	        );        
	    }
	    return new AvailabilityResult(
                AppointmentStatus.ERROR,
                List.of()
        );
	    	    	    
	}
	
	private static String createMultipartBody(
	        String boundary,
	        String csrf,
	        String centerId
	) {

	    String lineBreak = "\r\n";

	    StringBuilder body = new StringBuilder();

	    addFormField(
	            body,
	            boundary,
	            "form",
	            "days",
	            lineBreak
	    );

	    addFormField(
	            body,
	            boundary,
	            "ServiceCenterId",
	            centerId,
	            lineBreak
	    );

	    addFormField(
	            body,
	            boundary,
	            "ServiceId",
	            "4",
	            lineBreak
	    );

	    addFormField(
	            body,
	            boundary,
	            csrf,
	            "1",
	            lineBreak
	    );

	    body.append("--")
	            .append(boundary)
	            .append("--")
	            .append(lineBreak);

	    return body.toString();
	}
	
	private static void addFormField(
	        StringBuilder body,
	        String boundary,
	        String name,
	        String value,
	        String lineBreak
	) {

	    body.append("--")
	            .append(boundary)
	            .append(lineBreak);

	    body.append(
	            "Content-Disposition: form-data; name=\""
	    )
	            .append(name)
	            .append("\"")
	            .append(lineBreak);

	    body.append(lineBreak);

	    body.append(value)
	            .append(lineBreak);
	}
	
	private static List<String> extractAvailableDates(String responseBody) {

	    List<String> dates = new ArrayList<>();

	    Pattern pattern =
	            Pattern.compile("\"date\"\\s*:\\s*\"([^\"]+)\"");

	    Matcher matcher = pattern.matcher(responseBody);

	    while (matcher.find()) {
	        dates.add(matcher.group(1));
	    }

	    return dates;
	}
	
	
}

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

public class Main {

	private static final String URL = "https://munich.pasport.org.ua/solutions/e-queue";
	 
	//private static final String URL = "http://127.0.0.1:5500/index.html";

	private static final String BUSY_MESSAGE = "Наразі всі місця зайняті.";

	private static final String SERVICES_FORM = "id=\"services\"";

	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()

			
			//.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(5))
			.build();

	private static final Logger LOGGER = TrackerLogger.getLogger();

	private enum AppointmentStatus {
		FULLY_BOOKED, PAGE_CHANGED, RATE_LIMITED, ACCESS_FORBIDDEN, SERVER_ERROR, NETWORK_ERROR, ERROR
	}

	private static AppointmentStatus previousStatus = null;

	public static void main(String[] args) {

		ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

		 scheduler.scheduleWithFixedDelay(Main::safeCheckOnce, 0, 5, TimeUnit.MINUTES);
		 
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

		AppointmentStatus status;
		String html = null;

		try {

			HttpResponse<String> response = loadPage();

			int httpStatusCode = response.statusCode();

			if (httpStatusCode >= 200 && httpStatusCode < 300) {

				status = checkStatus(response.body());
				html = response.body();

			} else if (httpStatusCode == 429) {

				status = AppointmentStatus.RATE_LIMITED;

			} else if (httpStatusCode == 403) {

				status = AppointmentStatus.ACCESS_FORBIDDEN;

			} else if (httpStatusCode >= 500 && httpStatusCode < 600) {

				status = AppointmentStatus.SERVER_ERROR;

			} else {

				status = AppointmentStatus.ERROR;
			}

			logHttpResult(httpStatusCode, status);

		} catch (IOException e) {

			status = AppointmentStatus.NETWORK_ERROR;

			LOGGER.log(Level.WARNING, "NETWORK_ERROR | " + e.getClass().getSimpleName());
		} catch (InterruptedException e) {

			Thread.currentThread().interrupt();
			status = AppointmentStatus.ERROR;

			LOGGER.log(Level.WARNING, "Check thread was interrupted", e);

		}

		catch (Exception e) {
			// temporally for fix
			e.printStackTrace();
			status = AppointmentStatus.ERROR;
		}

		if (status != previousStatus) {

			LOGGER.info("STATUS_CHANGE | " + previousStatus + " -> " + status);

			if (status == AppointmentStatus.PAGE_CHANGED) {
				saveSnapshot(html);
			}
			notifyStatusChange(status);
			previousStatus = status;
		}

	}

	private static HttpResponse<String> loadPage() throws IOException, InterruptedException {

		HttpRequest request = HttpRequest.newBuilder().uri(URI.create(URL)).timeout(Duration.ofSeconds(10)).GET()
				.build();

		HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

		return response;
	}

	/*
	 * private static AppointmentStatus checkStatus(String html) {
	 * 
	 * if (html.contains(BUSY_MESSAGE)) { return AppointmentStatus.FULLY_BOOKED; }
	 * 
	 * return AppointmentStatus.PAGE_CHANGED; }
	 */
	private static AppointmentStatus checkStatus(String html) {
		if (html.contains(BUSY_MESSAGE)) {
			return AppointmentStatus.FULLY_BOOKED; 
			} else if (isFormMode(html)) {
				
				System.out.println("Mode B");
				
				String csrf = extractCsrf(html);
			    String centerId = extractCenterId(html);
			    
			    System.out.println("CSRF: " + csrf);
			    System.out.println("Center ID: " + centerId);
			    return AppointmentStatus.SERVER_ERROR;
			} else {
				return AppointmentStatus.PAGE_CHANGED;
			}
		
	}
	
	private static boolean isFormMode(String html) {
		return html.contains(SERVICES_FORM);
	}

	private static String extractCsrf(String html) {

		Pattern pattern = Pattern.compile("'csrf'\\s*:\\s*'([^']+)'");

		Matcher matcher = pattern.matcher(html);

		if (matcher.find()) {
			return matcher.group(1);
		}

		return null;
	}

	private static String extractCenterId(String html) {

		Pattern pattern = Pattern.compile("'center'\\s*:\\s*'([^']+)'");

		Matcher matcher = pattern.matcher(html);

		if (matcher.find()) {
			return matcher.group(1);
		}

		return null;
	}

	private static String getStatusMessage(AppointmentStatus status) {

		switch (status) {

		case FULLY_BOOKED:
			return "DP Dokument: Наразі всі місця зайняті.";

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

		default:
			return "Неизвестное состояние.";
		}
	}

	private static void notifyStatusChange(AppointmentStatus status) {

		String message = time() + " " + getStatusMessage(status);

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
}

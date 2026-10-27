package de.ara.dpdokumenttracker;

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
import java.util.List;
import java.time.Instant;
import java.net.InetAddress;
import java.net.UnknownHostException;

public class Main {

	private static final Logger LOGGER = TrackerLogger.getLogger();

	private static final AppointmentSiteClient APPOINTMENT_SITE_CLIENT =
			new HttpAppointmentSiteClient();


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
		
		System.out.println("=== ENVIRONMENT DIAGNOSTICS ===");

		System.out.println("OS: "
		        + System.getProperty("os.name")
		        + " "
		        + System.getProperty("os.version"));

		System.out.println("Java version: "
		        + System.getProperty("java.version"));

		System.out.println("Java vendor: "
		        + System.getProperty("java.vendor"));

		System.out.println("Java home: "
		        + System.getProperty("java.home"));

		System.out.println("preferIPv4Stack: "
		        + System.getProperty("java.net.preferIPv4Stack"));

		System.out.println("preferIPv6Addresses: "
		        + System.getProperty("java.net.preferIPv6Addresses"));
		
		try {
		    InetAddress[] addresses =
		            InetAddress.getAllByName("munich.pasport.org.ua");

		    for (InetAddress address : addresses) {
		        System.out.println(
		                "Resolved: "
		                + address.getHostAddress()
		                + " | "
		                + address.getClass().getSimpleName()
		        );
		    }

		} catch (UnknownHostException e) {
		    System.out.println(
		            "DNS ERROR: " + e.getMessage()
		    );
		}
		

		scheduler = Executors.newSingleThreadScheduledExecutor();

		scheduler.scheduleWithFixedDelay(Main::safeCheckOnce, 0, 2, TimeUnit.MINUTES);

		//scheduler.scheduleWithFixedDelay(Main::safeCheckOnce, 0, 30, TimeUnit.SECONDS);

	}
	
	private static void safeCheckOnce() {

		try {
			checkOnce();

		} catch (Exception e) {

			LOGGER.log(
					Level.SEVERE, 
					"Unexpected error in scheduled check", 
					e
					);
		}
	}
	
	public static void checkOnce() {

		if (isBackoffActive()) {
		    return;
		}
		
		AvailabilityResult result =
		        APPOINTMENT_SITE_CLIENT.checkAvailability();

		String html = result.getPageHtml();
		
		AppointmentStatus status = result.getStatus();
		
		if (status == AppointmentStatus.ACCESS_FORBIDDEN) {			
			handleAccessForbidden(result);			
		    return;
		}
			
		if (status == AppointmentStatus.RATE_LIMITED) {
			
			resetForbiddenSequence();
			handleRateLimited(result);

		    return;
		}
		
		if (isGeneralTechnicalFailure(status)) {
			
			resetForbiddenSequence();
			handleGeneralTechnicalFailure(result);

		    return;
		}
		
		if (isTechnicalFailure(status)) {
			
			resetForbiddenSequence();
		    return;
		}
		
		handleSuccessfulCheck(status);
		
		resetForbiddenSequence();
		
		handleStateChange(
				result,
				html
		);

	}
	
	private static boolean isBackoffActive() {

	    if (Instant.now().isBefore(forbiddenBackoffUntil)) {

	        LOGGER.info(
	                "BACKOFF_ACTIVE | until="
	                + forbiddenBackoffUntil
	        );

	        return true;
	    }

	    if (Instant.now().isBefore(rateLimitBackoffUntil)) {

	        LOGGER.info(
	                "RATE_LIMIT_BACKOFF_ACTIVE | until="
	                + rateLimitBackoffUntil
	        );

	        return true;
	    }

	    return false;
	}
	
	private static void handleSuccessfulCheck(
	        AppointmentStatus status) {
		
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
		
	}
	
	private static void handleGeneralTechnicalFailure(
	        AvailabilityResult result) {
		
		AppointmentStatus status = result.getStatus();
		
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
		
	}
	
	private static void handleRateLimited(AvailabilityResult result) {
		
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
		
	}
	
	private static void handleAccessForbidden(AvailabilityResult result) {
		
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
		
	}
	
	private static void resetForbiddenSequence() {

	    consecutiveForbiddenCount = 0;
	    forbiddenBackoffUntil = Instant.EPOCH;
	}
	
	private static void handleStateChange(
	        AvailabilityResult result,
	        String html) {

	    AppointmentStatus status =
	            result.getStatus();

	    List<String> currentDates =
	            result.getAvailableDates();

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

	        previousAvailableDates =
	                List.copyOf(currentDates);
	    }
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
		if (html == null) {
			return;
		}

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

	private static LocalTime time() {
		LocalTime now = LocalTime.now();
		LocalTime time = now.truncatedTo(ChronoUnit.MINUTES);
		return time;
	}
	
}

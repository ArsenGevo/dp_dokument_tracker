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

public class Main {

	private static final String URL = "https://munich.pasport.org.ua/solutions/e-queue";
	private static final String BUSY_MESSAGE = "Наразі всі місця зайняті.";
	private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

	private enum AppointmentStatus {
		FULLY_BOOKED, PAGE_CHANGED, ERROR, RATE_LIMITED
	}
	private static AppointmentStatus previousStatus = null;
	
		
	public static void main(String[] args) {
		
		
	    ScheduledExecutorService scheduler =
	            Executors.newSingleThreadScheduledExecutor();
	    
	    scheduler.scheduleAtFixedRate(Main::checkOnce, 0, 1, TimeUnit.MINUTES);
		
		
	}
	
	public static void checkOnce() {

		AppointmentStatus status;

		try {

			HttpResponse<String> response = loadPage();

			int httpStatusCode = response.statusCode();

			System.out.println(time() + " HTTP Status: " + httpStatusCode);

			if (httpStatusCode >= 200 && httpStatusCode < 300) {
				
				status = checkStatus(response.body());

			} else if (httpStatusCode == 429) {
				
				status = AppointmentStatus.RATE_LIMITED;
				
			} else {
				
				status = AppointmentStatus.ERROR;
			}

		} catch (IOException e) {

			status = AppointmentStatus.ERROR;

		} catch (InterruptedException e) {

			Thread.currentThread().interrupt();
			status = AppointmentStatus.ERROR;
		}
		
		if (status != previousStatus) {
			printStatus(status);
		}
		
	}

	private static HttpResponse<String> loadPage() throws IOException, InterruptedException {

		HttpRequest request = HttpRequest.newBuilder().uri(URI.create(URL)).GET().build();

		HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

		return response;
	}

	private static AppointmentStatus checkStatus(String html) {

		if (html.contains(BUSY_MESSAGE)) {
			return AppointmentStatus.FULLY_BOOKED;
		}

		return AppointmentStatus.PAGE_CHANGED;
	}
	
	private static void printStatus(AppointmentStatus status) {
		switch (status) {
		case FULLY_BOOKED:
			System.out.println("DP Dokument: Наразі всі місця зайняті.");
			break;
		case PAGE_CHANGED:
			System.out.println("DP Dokument: Cостояние страницы изменилось!");
			break;
		case ERROR:
			System.out.println("ошибка проверки!");
			break;
		case RATE_LIMITED:
			System.out.println("превышение лимита запросов!");
			break;
		}
	}

	private static LocalTime time() {
		LocalTime now = LocalTime.now();
		LocalTime time = now.truncatedTo(ChronoUnit.MINUTES);
		return time;
	}	
}



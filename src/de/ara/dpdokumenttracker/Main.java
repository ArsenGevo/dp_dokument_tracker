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

public class Main {

	private static final String URL = "https://munich.pasport.org.ua/solutions/e-queue";
	
	//private static final String URL = "http://127.0.0.1:5500/index.html";
	
	private static final String BUSY_MESSAGE = "Наразі всі місця зайняті.";
	
	private static final HttpClient HTTP_CLIENT =
	        HttpClient.newBuilder()
	                //.version(HttpClient.Version.HTTP_1_1)
	                .connectTimeout(Duration.ofSeconds(5))
	                .build();

	private enum AppointmentStatus {
		FULLY_BOOKED, 
		PAGE_CHANGED, 
		RATE_LIMITED, 
		ACCESS_FORBIDDEN,
		ERROR
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
				
			} else if (httpStatusCode == 403) {

			    status = AppointmentStatus.ACCESS_FORBIDDEN;

			} else {
				
				status = AppointmentStatus.ERROR;
			}

		} catch (IOException e) {

			status = AppointmentStatus.ERROR;

		} catch (InterruptedException e) {

			Thread.currentThread().interrupt();
			status = AppointmentStatus.ERROR;
		}
		
		catch (Exception e) {
			//temporally for fix
		    e.printStackTrace();
		    status = AppointmentStatus.ERROR;
		}
		
		if (status != previousStatus) {
			notifyStatusChange(status);
			previousStatus = status;
		}
		
	}

	private static HttpResponse<String> loadPage() throws IOException, InterruptedException {

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(URL))
				.timeout(Duration.ofSeconds(10))
				.GET()
				.build();
		

		HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
		


		return response;
	}

	private static AppointmentStatus checkStatus(String html) {

		if (html.contains(BUSY_MESSAGE)) {
			return AppointmentStatus.FULLY_BOOKED;
		}

		return AppointmentStatus.PAGE_CHANGED;
	}
	
	
	private static String getStatusMessage(AppointmentStatus status) {

	    switch (status) {

	    case FULLY_BOOKED:
	        return "DP Dokument: Наразі всі місця зайняті.";

	    case PAGE_CHANGED:
	        return "DP Dokument: состояние страницы изменилось!";

	    case RATE_LIMITED:
	        return "DP Dokument: превышение лимита запросов!";
	        
	    case ACCESS_FORBIDDEN:
	        return "DP Dokument: доступ запрещён сервером (HTTP 403).";

	    case ERROR:
	        return "DP Dokument: ошибка проверки!";

	    default:
	        return "DP Dokument: неизвестное состояние.";
	    }
	}
	
	
	private static void notifyStatusChange(AppointmentStatus status) {

	    String message = getStatusMessage(status);

	    System.out.println(message);

	    try {

	        TelegramNotifier.sendMessage(message);

	    } catch (IOException e) {

	        System.out.println(
	                "Ошибка отправки Telegram: " + e.getMessage()
	        );

	    } catch (InterruptedException e) {

	        Thread.currentThread().interrupt();

	        System.out.println(
	                "Отправка Telegram была прервана."
	        );
	    }
	}

	private static LocalTime time() {
		LocalTime now = LocalTime.now();
		LocalTime time = now.truncatedTo(ChronoUnit.MINUTES);
		return time;
	}	
}



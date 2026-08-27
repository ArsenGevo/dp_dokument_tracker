package de.ara.dpdokumenttracker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.IOException;

public class Main {

	private static final String URL = "https://munich.pasport.org.ua/solutions/e-queue";
	private static final String BUSY_MESSAGE = "Наразі всі місця зайняті.";
	private enum AppointmentStatus {
        FULLY_BOOKED,
        PAGE_CHANGED,
        ERROR
    }

	public static void main(String[] args) throws IOException, InterruptedException {
		
	    AppointmentStatus status;

	    try {

	        String html = loadPage();

	        status = checkStatus(html);

	    } catch (IOException e) {

	        status = AppointmentStatus.ERROR;

	    } catch (InterruptedException e) {

	        Thread.currentThread().interrupt();
	        status = AppointmentStatus.ERROR;
	    }

	    printStatus(status);

 
	}
	
	public static void printStatus(AppointmentStatus status) {
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
		}
	}

	public static String loadPage() throws IOException, InterruptedException {

		HttpClient client = HttpClient.newHttpClient();

		HttpRequest request = HttpRequest.newBuilder().uri(URI.create(URL)).GET().build();

		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

		System.out.println("HTTP Status: " + response.statusCode());

		return response.body();
	}

	
	public static AppointmentStatus checkStatus(String html) {

	    if (html.contains(BUSY_MESSAGE)) {
	        return AppointmentStatus.FULLY_BOOKED;
	    }

	    return AppointmentStatus.PAGE_CHANGED;
	}
}

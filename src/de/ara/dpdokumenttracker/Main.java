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
		FULLY_BOOKED, PAGE_CHANGED, ERROR, RATE_LIMITED
	}

	public static void main(String[] args) {

		AppointmentStatus status;

		try {

			HttpResponse<String> response = loadPage();

			String html = response.body();

			int httpStatusCode = response.statusCode();
			
			System.out.println("HTTP Status: " + response.statusCode());

			if (httpStatusCode >= 200 && httpStatusCode < 300) {
				status = checkStatus(html);
				
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
		case RATE_LIMITED: 
			System.out.println("превышение лимита запросов!");
			break;
		}
	}

	public static HttpResponse<String> loadPage() throws IOException, InterruptedException {

		HttpClient client = HttpClient.newHttpClient();

		HttpRequest request = HttpRequest.newBuilder().uri(URI.create(URL)).GET().build();

		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

		return response;
	}

	public static AppointmentStatus checkStatus(String html) {

		if (html.contains(BUSY_MESSAGE)) {
			return AppointmentStatus.FULLY_BOOKED;
		}

		return AppointmentStatus.PAGE_CHANGED;
	}
}

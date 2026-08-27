package de.ara.dpdokumenttracker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.IOException;


public class Main {

	public static void main(String[] args) 
			throws IOException, InterruptedException{
		
		String url = "https://munich.pasport.org.ua/solutions/e-queue";
		
		HttpClient client = HttpClient.newHttpClient();
		
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.GET()
				.build();
		
		HttpResponse<String> response =
                client.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );

        String html = response.body();

        System.out.println("HTTP Status: " + response.statusCode());
        
        String busyMassage = "Наразі всі місця зайняті.";
        
        if (html.contains(busyMassage)) {
        	System.out.println("DP Dokument Berlin: Наразі всі місця зайняті.");
        } else {
        	System.out.println("DP Dokument Berlin: Cостояние страницы изменилось!");
        }
        
		
	}

}

package de.ara.dpdokumenttracker;

import java.util.List;

public class AvailabilityResult {

	private final AppointmentStatus status;
    private final List<String> availableDates;
    private final String pageHtml;

    public AvailabilityResult(
            AppointmentStatus status,
            List<String> availableDates
    ) {
        this(status, availableDates, null);
    }

    public AvailabilityResult(
            AppointmentStatus status,
            List<String> availableDates,
            String pageHtml
    ) {
        this.status = status;
        this.availableDates = List.copyOf(availableDates);
        this.pageHtml = pageHtml;
    }

    public AppointmentStatus getStatus() {
        return status;
    }

    public List<String> getAvailableDates() {
        return availableDates;
    }

    public String getPageHtml() {
        return pageHtml;
    }
	
}

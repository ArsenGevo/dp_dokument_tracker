package de.ara.dpdokumenttracker;

import java.util.List;

public class AvailabilityResult {

	private final AppointmentStatus status;
    private final List<String> availableDates;

    public AvailabilityResult(
            AppointmentStatus status,
            List<String> availableDates
    ) {
        this.status = status;
        this.availableDates = List.copyOf(availableDates);
    }

    public AppointmentStatus getStatus() {
        return status;
    }

    public List<String> getAvailableDates() {
        return availableDates;
    }
	
}

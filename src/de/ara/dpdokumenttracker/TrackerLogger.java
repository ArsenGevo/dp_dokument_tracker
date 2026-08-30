package de.ara.dpdokumenttracker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class TrackerLogger {
	
	private static final Logger LOGGER =
            Logger.getLogger("DpDokumentTracker");

    static {

        try {
        	
        	System.setProperty(
                    "java.util.logging.SimpleFormatter.format",
                    "%1$tF %1$tT | %4$s | %5$s%6$s%n"
            );

            Files.createDirectories(Path.of("logs"));

            FileHandler fileHandler =
                    new FileHandler(
                    		"logs/tracker.log",
                    		1_000_000,
                    		5,
                    		true
                    		);

            fileHandler.setFormatter(new SimpleFormatter());

            LOGGER.addHandler(fileHandler);
            LOGGER.setLevel(Level.INFO);

        } catch (IOException e) {

            System.err.println(
                    "Не удалось создать лог-файл: "
                    + e.getMessage()
            );
        }
    }

    public static Logger getLogger() {
        return LOGGER;
    }

}

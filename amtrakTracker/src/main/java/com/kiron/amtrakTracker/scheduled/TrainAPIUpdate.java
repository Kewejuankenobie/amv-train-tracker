package com.kiron.amtrakTracker.scheduled;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kiron.amtrakTracker.model.TrainApiModel;
import com.kiron.amtrakTracker.model.TrainParsed;
import com.kiron.amtrakTracker.model.gtfs.Station;
import com.kiron.amtrakTracker.service.StationService;
import com.kiron.amtrakTracker.service.TrainService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.NoSuchElementException;

@Component
@Slf4j
public class TrainAPIUpdate {

    @Autowired
    private TrainService trainService;

    @Autowired
    private StationService stationService;

    @Scheduled(fixedRate = 120000)
    public void updateTrains() {
        //Updates all trains currently running at a fixed rate of every 2 minutes
        try {
            URL trainUrl = new URI("https://asm-backend.transitdocs.com/map").toURL();
            ObjectMapper mapper = new ObjectMapper();
            TrainApiModel[] trains = mapper.readValue(trainUrl, TrainApiModel[].class);

            trainService.setAllInactive();

            for (TrainApiModel train : trains) {
                TrainParsed parsedTrain = new TrainParsed(train);

                //Get the next station, we need this to set the correct arrival time
                Station station;
                try {
                    station = stationService.getStationByCode(parsedTrain.getNext_station()).iterator().next();
                } catch (NoSuchElementException e) {
                    continue;
                }


                //Set the correct time for arrival
                Instant instant = Instant.ofEpochSecond(parsedTrain.getArrival_epoch());
                ZoneId zone = ZoneId.of(station.getTime_zone());
                LocalDateTime localDateTime = instant.atZone(zone).toLocalDateTime();
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("hh:mm a");
                parsedTrain.setScheduled_arrival(formatter.format(localDateTime));

                trainService.addTrain(parsedTrain);
            }

            trainService.deleteInactiveTrains();
            log.info("Updated Trains, there are " + trains.length + " trains");
        } catch (IOException | URISyntaxException e) {
            log.error("Error updating train information due to error:", e);
        }
    }

}

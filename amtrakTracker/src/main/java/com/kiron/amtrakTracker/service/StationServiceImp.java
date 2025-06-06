package com.kiron.amtrakTracker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.transit.realtime.GtfsRealtime;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.kiron.amtrakTracker.model.StationTimeboard;
import com.kiron.amtrakTracker.model.TimeboardRow;
import com.kiron.amtrakTracker.model.gtfs.Route;
import com.kiron.amtrakTracker.model.gtfs.Station;
import com.kiron.amtrakTracker.model.gtfs.StopTimes;
import com.kiron.amtrakTracker.model.gtfs.Trip;
import com.kiron.amtrakTracker.repository.RouteRepository;
import com.kiron.amtrakTracker.repository.StationRepository;
import com.kiron.amtrakTracker.repository.StopTimeRepository;
import com.kiron.amtrakTracker.repository.TripRepository;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Service
public class StationServiceImp implements StationService {


    @Autowired
    private StationRepository stationRepository;

    @Autowired
    private StopTimeRepository stopTimeRepository;
    @Autowired
    private RouteRepository routeRepository;
    @Autowired
    private TripRepository tripRepository;

    @Override
    public Set<Station> getStationByCode(String query) {
        List<Station> stations = stationRepository.findByCodeContainsIgnoreCase(query);
        return new HashSet<>(stations);
    }

    @Override
    public Set<Station> getStationByName(String query) {
        Set<Station> stations = new HashSet<>(stationRepository.findByNameContainsIgnoreCase(query));
        String nextQuery = query.replace("e", "é");
        stations.addAll(stationRepository.findByNameContainsIgnoreCase(nextQuery));
        return new HashSet<>(stations);
    }

    @Override
    public List<Station> getAllStations() {
        return stationRepository.findAll();
    }

    @Override
    public StationTimeboard getTrainsAtStation(String code) throws IOException {
        //Gets all trains at a station and returns them as a full timeboard

        //First, get all train gtfs-rt
        URL urlAm = new URL("https://asm-backend.transitdocs.com/gtfs/amtrak");
        URL urlVia = new URL("https://asm-backend.transitdocs.com/gtfs/via");
        FeedMessage amFeed = FeedMessage.parseFrom(urlAm.openStream());
        FeedMessage viaFeed = FeedMessage.parseFrom(urlVia.openStream());

        Station station = stationRepository.findByCode(code);
        if (station == null) {
            return null;
        }

        StationTimeboard timeboard = new StationTimeboard(code, station.getName(), station.getWebsite(),
                station.getAdmin_area());


        List<StopTimes> allStops = stopTimeRepository.findAllByStop_Id(station.getId());

        for (StopTimes stopTime : allStops) {

            if (code.length() == 3) {
                buildRow(amFeed, stopTime, timeboard);
            } else {
                buildRow(viaFeed, stopTime, timeboard);
            }
        }

        timeboard.sortTimeboard();
        return timeboard;
    }

    private void buildRow(FeedMessage feed, StopTimes stopTime, StationTimeboard timeboard) {
        //Builds an individual row in the station timeboard, being the train and its arrival and departure times

        //Calculates the timezone offset from EST
        String timeZone =stationRepository.findById(stopTime.getStop_id()).isPresent() ?
                stationRepository.findById(stopTime.getStop_id()).get().getTime_zone() : "America/New_York";

        ZonedDateTime t1 = ZonedDateTime.now(ZoneId.of(timeZone));
        ZonedDateTime t2 = ZonedDateTime.now(ZoneId.of("America/New_York"));

        int hourOffset = t1.getHour() - t2.getHour();

        TimeboardRow row = new TimeboardRow();
        row.setScheduled_arrival(parseTime(stopTime.getArrival_time(), hourOffset));
        row.setScheduled_departure(parseTime(stopTime.getDeparture_time(), hourOffset));
        row.setLate_arrival(false);
        row.setLate_departure(false);
        Trip trip = tripRepository.findById(stopTime.getTrip_id()).orElse(null);
        if (trip == null) {
            return;
        }

        row.setNumber(trip.getNumber());
        row.setDestination(trip.getDestination());
        Route route = routeRepository.findById(trip.getRoute_id()).orElse(null);
        if (route == null) {
            return;
        }
        row.setName(route.getRoute_name());
        int stopSequence = stopTime.getStop_sequence() - 1;
        if (trip.getRoute_id().equals("SJ2")) {
            ++stopSequence;
        }

        //Next, check updated data, if there, then we add to the timeboard and change arrival and departure times if needed
        //We need to loop through all entities because one trip id can have multiple entities (different days)
        for (int i = 0; i < feed.getEntityCount(); i++) {
            FeedEntity entity = feed.getEntity(i);
            if (entity.hasTripUpdate()) {
                String tripId = entity.getTripUpdate().getTrip().getTripId();
                if (!tripId.equals(trip.getTrip_id()) && !tripId.contains("_AMTK_" + trip.getTrip_id())) {
                    continue;
                }
                //There are a few cases where the stop sequence of the stop time is out of range (Empire
                // Builder from PDX at CHI for instance)
                if (entity.getTripUpdate().getStopTimeUpdateCount() <= stopSequence) {
                    continue;
                }


                GtfsRealtime.TripUpdate.StopTimeUpdate update = entity.getTripUpdate().getStopTimeUpdate(stopSequence);

                //If we are on another entity for the same trip id, then we need to add a new row
                if (row.getDate() != null) {
                    row = new TimeboardRow(row, false);
                }

                if (update.hasArrival()) {
                    row.setActual_time(update.getArrival().getTime());
                    row.setDate(formatDate(row.getActual_time()));
                    row.setArrival(formatEpoch(update.getArrival().getTime(), timeZone));
                    if (update.getArrival().getDelay() > 0) {
                        row.setLate_arrival(true);
                    }
                }
                if (update.hasDeparture()) {
                    if (row.getDate() == null) {
                        row.setActual_time(update.getDeparture().getTime());
                        row.setDate(formatDate(row.getActual_time()));
                    }
                    row.setDeparture(formatEpoch(update.getDeparture().getTime(), timeZone));
                    if (update.getDeparture().getDelay() > 0) {
                        row.setLate_departure(true);
                    }
                }
                //If there is no arrival or departure, it is most likely a rescheduled train (1xxx), so we will assume
                //its date is today
                if (!update.hasArrival() && !update.hasDeparture()) {
                    row.setDate(formatDate(Instant.now().getEpochSecond()));
                }
                timeboard.addRow(row);
            }
        }
    }

    @Override
    public void updateGTFS() throws IOException, CsvValidationException {
        //Updates the static GTFS database tables for Amtrak Trains

        //First, we need to get the gtfs data and get their zip entries
        URL urlAm = new URL("https://content.amtrak.com/content/gtfs/GTFS.zip");
        URL urlVia = new URL("https://www.viarail.ca/sites/all/files/gtfs/viarail.zip");
        URL urlSanJ = new URL("https://d34tiw64n5z4oh.cloudfront.net/wp-content/uploads/SJJPA_03182025-1.zip");

        List<Station> stations = new ArrayList<>();
        List<StopTimes> stopTimes = new ArrayList<>();
        List<Route> routes = new ArrayList<>();
        List<Trip> trips = new ArrayList<>();


        updateGTFSFromCSV(urlAm, stations, stopTimes, routes, trips, 0);
        updateGTFSFromCSV(urlVia, stations, stopTimes, routes, trips, 1);
        updateGTFSFromCSV(urlSanJ, stations, stopTimes, routes, trips, 2);

        setStations(stations);

        //stopTimeRepository.deleteAllInBatch();
        //Instead of deleting, if something is inserted in GTFS stop times, since we are using a seperate id that
        //is generated, it causes some doubling of stop times. Instead, we need to have the ids be a composed id
        //of the trip id, stop sequence, and departure time

        routeRepository.saveAll(routes);
        log.info("Finished updating route GTFS");
        tripRepository.saveAll(trips);
        log.info("Finished updating trip GTFS");
        stopTimeRepository.saveAll(stopTimes);
        log.info("Finished updating stop time GTFS");
        stationRepository.saveAll(stations);
        log.info("Finished updating station GTFS");
    }

    private void updateGTFSFromCSV(URL url, List<Station> stations, List<StopTimes> stopTimes,
                                   List<Route> routes, List<Trip> trips,
                                   int type) throws IOException, CsvValidationException {
        //Updates the GTFS lists passed by reference from the csv files provided, this assumes the zip is correct format

        log.info("Updating GTFS from {}", url.toString());
        HttpURLConnection conn;
        InputStream inputStream;
        try {
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            inputStream = conn.getInputStream();
        } catch (SocketTimeoutException e) {
            //If the input stream does not load fast enough, we will not update that gtfs data
            throw new IOException("Connection timed out");
        }
        ZipInputStream zipInputStream = new ZipInputStream(inputStream);
        ZipEntry zipEntry;

        while ((zipEntry = zipInputStream.getNextEntry()) != null) {
            if (zipEntry.getName().equals("stop_times.txt")
                    || zipEntry.getName().equals("routes.txt") || zipEntry.getName().equals("trips.txt")) {

                log.info("Zip file has name {} on url {}", zipEntry.getName(), url.toString());

                //Reads the zip files input stream to byte arrays, which will later be read from to prevent
                //the inability to read all zip files
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int length;
                while ((length = zipInputStream.read(buffer)) > 0) {
                    baos.write(buffer, 0, length);
                }


                try(InputStream stream = new ByteArrayInputStream(baos.toByteArray());
                    InputStreamReader isr = new InputStreamReader(stream, StandardCharsets.UTF_8);
                    CSVReader csvReader = new CSVReader(isr);) {

                    String[] line;
                    boolean firstLine = true;
                    Long lineNum = 0L;
                    while ((line = csvReader.readNext()) != null) {
                        //Reading each line into an array, we add each index of line to the according object based on
                        //which csv file is being read
                        if (firstLine) {
                            firstLine = false;
                            continue;
                        }

                        if (type == 0) {
                            updateAmtrakGTFS(zipEntry.getName(), line, stations, stopTimes, routes, trips, lineNum);

                        } else if (type == 1) {
                            updateViaGTFS(zipEntry.getName(), line, stations, stopTimes, routes, trips, lineNum);
                        } else {
                            updateSanJGTFS(zipEntry.getName(), line, stations, stopTimes, routes, trips, lineNum);
                        }
                        lineNum++;
                    }
                }
                zipInputStream.closeEntry();
            } else {
                zipInputStream.closeEntry();
            }
        }
    }

    private void updateSanJGTFS(String name, String[] line, List<Station> stations, List<StopTimes> stopTimes,
                                List<Route> routes, List<Trip> trips, Long lineNum) {
        //Parses Amtrak San Joaquin csv data

//        if (name.equals("stops.txt") && line[0].length() == 3 && !line[7].contains("acerail")) {
//            Station station = new Station();
//            station.setId(line[0]);
//            station.setCode(line[0]);
//            station.setName(line[2]);
//            station.setWebsite(line[7]);
//            station.setAdmin_area("CA");
//            station.setTime_zone("America/Los_Angeles");
//            stations.add(station);
        if (name.equals("stop_times.txt") && line[0].length() == 3) {
            StopTimes stopTime = new StopTimes();
            stopTime.setTrip_id(line[0]);
            stopTime.setArrival_time(line[3]);
            stopTime.setDeparture_time(line[4]);
            stopTime.setStop_id(line[2]);
            stopTime.setStop_sequence(Integer.parseInt(line[1]));
            stopTimes.add(stopTime);
        } else if (name.equals("routes.txt") && line[0].equals("SJ2")) {
            Route route = new Route();
            route.setRoute_id(line[0]);
            route.setRoute_name("San Joaquins");
            routes.add(route);
        } else if (name.equals("trips.txt") && line[0].length() == 3) {
            Trip trip = new Trip();
            trip.setTrip_id(line[0]);
            trip.setRoute_id(line[1]);
            trip.setNumber(Integer.parseInt(line[0]));
            trip.setDestination(line[3]);
            trips.add(trip);
        }
    }

    private void updateAmtrakGTFS(String name, String[] line, List<Station> stations, List<StopTimes> stopTimes,
                                  List<Route> routes, List<Trip> trips, Long lineNum) {
        //Parses Amtrak csv data

//        if (name.equals("stops.txt")) {
//            Station station = new Station();
//            station.setId(line[0]);
//            station.setCode(line[0]);
//            station.setName(getAmtrakStationName(line[0], line[1]));
//            station.setWebsite(line[2]);
//            station.setTime_zone(line[3]);
//            stations.add(station);
        if (name.equals("stop_times.txt")) {
            StopTimes stopTime = new StopTimes();
            stopTime.setTrip_id(line[0]);
            stopTime.setArrival_time(line[1]);
            stopTime.setDeparture_time(line[2]);
            stopTime.setStop_id(line[3]);
            stopTime.setStop_sequence(Integer.parseInt(line[4]));
            stopTimes.add(stopTime);
        } else if (name.equals("routes.txt")) {
            Route route = new Route();
            route.setRoute_id(line[0]);
            route.setRoute_name(line[3]);
            routes.add(route);
        } else if (name.equals("trips.txt")) {
            Trip trip = new Trip();
            trip.setTrip_id(line[2]);
            trip.setRoute_id(line[0]);
            trip.setNumber(Integer.parseInt(line[3]));
            trip.setDestination(line[6]);
            trips.add(trip);
        }
    }

    private void updateViaGTFS(String name, String[] line, List<Station> stations,
                               List<StopTimes> stopTimes, List<Route> routes, List<Trip> trips, Long lineNum) {
        //Parses VIA Rail csv data

//        if (name.equals("stops.txt")) {
//            Station station = new Station();
//            station.setId(line[0]);
//            station.setCode(line[1]);
//            station.setName(line[2]);
//            station.setTime_zone(line[6]);
//            stations.add(station);
        if (name.equals("stop_times.txt")) {
            StopTimes stopTime = new StopTimes();
            stopTime.setTrip_id(line[0]);
            stopTime.setArrival_time(line[1]);
            stopTime.setDeparture_time(line[2]);
            stopTime.setStop_id(line[3]);
            stopTime.setStop_sequence(Integer.parseInt(line[4]));
            stopTimes.add(stopTime);
        } else if (name.equals("routes.txt")) {
            Route route = new Route();
            route.setRoute_id(line[0]);
            route.setRoute_name(getViaRouteName(line[2]));
            routes.add(route);
        } else if (name.equals("trips.txt")) {
            Trip trip = new Trip();
            trip.setTrip_id(line[2]);
            trip.setRoute_id(line[0]);
            if (line[4].isEmpty()) {
                trip.setNumber(0);
            } else if (line[4].contains("-")) {
                StringTokenizer st = new StringTokenizer(line[4], "-");
                trip.setNumber(Integer.parseInt(st.nextToken()));
            } else {
                trip.setNumber(Integer.parseInt(line[4]));
                //maple leaf case
            }
            trip.setDestination(line[5]);
            trips.add(trip);
        }
    }

    private String getAdmin1(JsonNode json) {
        //Gets the admin area of a Google Geolocate api call response json object

        return Optional.ofNullable(json)
                .map(j -> j.get("results"))
                .map(j -> j.get(0))
                .map(j -> j.get("address_components"))
                .map(j -> j.get(0))
                .map(j -> j.get("short_name"))
                .map(JsonNode::asText)
                .orElse(null);
    }

    private String getAmtrakStationName(String code, String defaultName) {
        //With the csv way of retriving station info, this is not used right now
        return switch (code) {
            case "BON" -> "Boston North Station";
            case "BOS" -> "Boston South Station";
            case "BBY" -> "Boston Back Bay Station";
            case "NYP" -> "New York Moynihan Train Hall at Penn Station";
            case "BFX" -> "Buffalo Exchange Street Station";
            case "BUF" -> "Buffalo Depew Station";
            default -> defaultName;
        };
    }

    private String getViaRouteName(String defaultRoute) {
        return switch (defaultRoute) {
            case "Vancouver - Toronto" -> "Canadian";
            case "Montréal - Halifax" -> "Ocean";
            case "Toronto - New York" -> "Maple Leaf";
            case "Sudbury - White River" -> "Lake Superior";
            case "Jasper - Prince Rupert" -> "Skeena";
            case "Winnipeg - Churchill", "The Pas - Churchill" -> "Hudson Bay";
            case "Montréal - Senneterre" -> "Abitibi";
            case "Montréal - Jonquière" -> "Saguenay";
            default -> "Corridor: " + defaultRoute;
        };
    }

    private String parseTime(String time, int offset) {
        //Converts time in the total time format to a standard 12 hour format
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("hh:mm a");
        DateTimeFormatter formatter2 = DateTimeFormatter.ofPattern("HH:mm:ss");

        StringTokenizer st = new StringTokenizer(time, ":");
        String[] parsedTokens = new String[3];
        parsedTokens[0] = st.nextToken();
        if (Integer.parseInt(parsedTokens[0]) > 23) {
            //Handles when we have more than 23 hours
            int replacement = Integer.parseInt(parsedTokens[0]);
            while (replacement > 23) {
                replacement -= 24;
            }
            replacement += offset;
            if (replacement < 0) {
                replacement += 24;
            }
            parsedTokens[0] = String.format("%02d", replacement);
        } else {
            int replacement = Integer.parseInt(parsedTokens[0]) + offset;
            if (replacement < 0) {
                replacement += 24;
            }
            parsedTokens[0] = String.format("%02d", replacement);
        }
        parsedTokens[1] = st.nextToken();
        parsedTokens[2] = st.nextToken();

        return formatter.format(formatter2.parse(parsedTokens[0] + ":" + parsedTokens[1] + ":" + parsedTokens[2]));
    }

    private String formatEpoch(Long epoch, String timeZone) {
        //Formats epoch time to the 12 hour format
        Instant instant = Instant.ofEpochSecond(epoch);
        ZoneId zone = ZoneId.of(timeZone);
        LocalDateTime localDateTime = instant.atZone(zone).toLocalDateTime();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("hh:mm a");
        return formatter.format(localDateTime);
    }

    private String formatDate(Long epoch) {
        //Formats epoch time to the month/day date format
        Instant instant = Instant.ofEpochSecond(epoch);
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime localDateTime = instant.atZone(zone).toLocalDateTime();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM/dd");
        return formatter.format(localDateTime);
    }


    private void setStations(List<Station> stations) throws IOException, CsvValidationException {
        //Sets the stations from a pre made csv file
        CSVReader csvReader = new CSVReader(new FileReader("src/main/resources/static/station.csv"));
        String[] line;
        boolean firstLine = true;
        while ((line = csvReader.readNext()) != null) {
            //Reading each line into an array, we add each index of line to the according object based on
            if (firstLine) {
                firstLine = false;
                continue;
            }
            Station s = new Station();
            s.setId(line[0]);
            s.setAdmin_area(line[1]);
            s.setCode(line[2]);
            s.setName(line[3]);
            s.setTime_zone(line[4]);
            s.setWebsite(line[5]);
            stations.add(s);
        }
    }

    @Override
    public void addStationAdmin(String code, double lat, double lng, String geolocKey) throws IOException {
        /*Expensive api calling method, do not do this too frequently, depricated as of now*/
                Station station = stationRepository.findByCode(code);
                if (station.getAdmin_area() != null) {
                    return;
                }
                String geostr = "https://maps.googleapis.com/maps/api/geocode/json?latlng=" +
                        lat + "," + lng + "&result_type=administrative_area_level_1&key=" + geolocKey;
                try {
                    URL geoloc = new URI(geostr).toURL();
                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode json = mapper.readValue(geoloc, JsonNode.class);

                    String admin1 = getAdmin1(json);
                    if (admin1 != null) {
                        station.setAdmin_area(admin1);
                        stationRepository.save(station);
                    }
                } catch (URISyntaxException | MalformedURLException e) {
                    log.error("Error parsing geolocation for station {}", station.getName());
                }
    }

}

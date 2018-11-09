package com.nexenio.bleindoorpositioning;

import com.nexenio.bleindoorpositioning.ble.beacon.Beacon;
import com.nexenio.bleindoorpositioning.ble.beacon.BeaconManager;
import com.nexenio.bleindoorpositioning.ble.beacon.BeaconUpdateListener;
import com.nexenio.bleindoorpositioning.ble.beacon.filter.BeaconFilter;
import com.nexenio.bleindoorpositioning.ble.beacon.filter.GenericBeaconFilter;
import com.nexenio.bleindoorpositioning.location.Location;
import com.nexenio.bleindoorpositioning.location.LocationListener;
import com.nexenio.bleindoorpositioning.location.LocationPredictor;
import com.nexenio.bleindoorpositioning.location.LocationUtil;
import com.nexenio.bleindoorpositioning.location.distance.DistanceUtil;
import com.nexenio.bleindoorpositioning.location.multilateration.Multilateration;
import com.nexenio.bleindoorpositioning.location.provider.LocationProvider;

import org.apache.commons.math3.exception.TooManyEvaluationsException;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class IndoorPositioning implements LocationProvider, BeaconUpdateListener {

    public static final long UPDATE_INTERVAL_IMMEDIATE = 50;
    public static final long UPDATE_INTERVAL_FAST = 100;
    public static final long UPDATE_INTERVAL_MEDIUM = 500;
    public static final long UPDATE_INTERVAL_SLOW = 3000;

    public static final int ROOT_MEAN_SQUARE_THRESHOLD_STRICT = 5;
    public static final int ROOT_MEAN_SQUARE_THRESHOLD_MEDIUM = 10;
    public static final int ROOT_MEAN_SQUARE_THRESHOLD_LIGHT = 25;

    private static final int MINIMUM_BEACON_COUNT = 3; // multilateration requires at least 3 beacons
    private static final int MAXIMUM_BEACON_COUNT = 10;

    public static final double MAXIMUM_MOVEMENT_SPEED_NOT_SET = -1;
    private double maximumMovementSpeed = MAXIMUM_MOVEMENT_SPEED_NOT_SET;
    private double rootMeanSquareThreshold = ROOT_MEAN_SQUARE_THRESHOLD_LIGHT;
    private int minimumRssiThreshold = -70;

    private static volatile IndoorPositioning instance;

    private Location lastKnownLocation;
    private long maximumLocationUpdateInterval = UPDATE_INTERVAL_MEDIUM;
    private Set<LocationListener> locationListeners = new HashSet<>();
    private BeaconFilter indoorPositioningBeaconFilter = null;
    private GenericBeaconFilter usableIndoorPositioningBeaconFilter = createUsableIndoorPositioningBeaconFilter();

    private LocationPredictor locationPredictor = new LocationPredictor();


    private IndoorPositioning() {
        BeaconManager.registerBeaconUpdateListener(this);
    }

    public static IndoorPositioning getInstance() {
        if (instance == null) {
            synchronized (IndoorPositioning.class) {
                if (instance == null) {
                    instance = new IndoorPositioning();
                }
            }
        }
        return instance;
    }

    @Override
    public Location getLocation() {
        return lastKnownLocation;
    }

    public static Location getMeanLocation(long amount, TimeUnit timeUnit) {
        return LocationUtil.calculateMeanLocationFromLast(getInstance().locationPredictor.getRecentLocations(), amount, timeUnit);
    }

    @Override
    public void onBeaconUpdated(Beacon beacon) {
        if (shouldUpdateLocation()) {
            updateLocation();
        }
    }

    private void updateLocation() {
        List<Beacon> usableBeacons = getUsableBeacons(BeaconManager.getInstance().getBeaconMap().values());

        if (usableBeacons.size() < MINIMUM_BEACON_COUNT) {
            return;
        } else if (usableBeacons.size() > MINIMUM_BEACON_COUNT) {
            Collections.sort(usableBeacons, Beacon.RssiComparator);
            int maximumBeaconIndex = Math.min(MAXIMUM_BEACON_COUNT, usableBeacons.size());
            int firstRemovableBeaconIndex = maximumBeaconIndex;
            for (int beaconIndex = MINIMUM_BEACON_COUNT; beaconIndex < maximumBeaconIndex; beaconIndex++) {
                if (usableBeacons.get(beaconIndex).getFilteredRssi() < minimumRssiThreshold) {
                    firstRemovableBeaconIndex = beaconIndex;
                    break;
                }
            }
            usableBeacons.subList(firstRemovableBeaconIndex, usableBeacons.size()).clear();
        }

        Multilateration multilateration = new Multilateration(usableBeacons);
        try {
            Location location = multilateration.getLocation();

            // The root mean square of multilateration is used to filter out inaccurate locations.
            // Adjust value to allow location updates with higher deviation
            if (multilateration.getRMS() < rootMeanSquareThreshold) {
                locationPredictor.addLocation(location);
                onLocationUpdated(location);
            }
        } catch (TooManyEvaluationsException e) {
            // see https://github.com/neXenio/BLE-Indoor-Positioning/issues/73
        }
    }

    public static <B extends Beacon> List<B> getUsableBeacons(Collection<B> availableBeacons) {
        return getInstance().usableIndoorPositioningBeaconFilter.getMatches(availableBeacons);
    }

    private void onLocationUpdated(Location location) {
        if (maximumMovementSpeed != MAXIMUM_MOVEMENT_SPEED_NOT_SET && lastKnownLocation != null) {
            location = DistanceUtil.speedFilter(lastKnownLocation, location, maximumMovementSpeed);
        }
        lastKnownLocation = location;
        for (LocationListener locationListener : locationListeners) {
            locationListener.onLocationUpdated(this, lastKnownLocation);
        }
    }

    private boolean shouldUpdateLocation() {
        if (lastKnownLocation == null) {
            return true;
        }
        return lastKnownLocation.getTimestamp() < System.currentTimeMillis() - maximumLocationUpdateInterval;
    }

    public static boolean registerLocationListener(LocationListener locationListener) {
        return getInstance().locationListeners.add(locationListener);
    }

    public static boolean unregisterLocationListener(LocationListener locationListener) {
        return getInstance().locationListeners.remove(locationListener);
    }

    public static GenericBeaconFilter<? extends Beacon> createUsableIndoorPositioningBeaconFilter() {
        return new GenericBeaconFilter<Beacon>() {

            @Override
            public boolean matches(Beacon beacon) {
                if (getInstance().indoorPositioningBeaconFilter != null && !getInstance().indoorPositioningBeaconFilter.matches(beacon)) {
                    return false;
                }
                if (!beacon.hasLocation()) {
                    return false; // beacon has no location assigned, can't use it for multilateration
                }
                if (!beacon.hasBeenSeenInThePast(2, TimeUnit.SECONDS)) {
                    return false; // beacon hasn't been in range recently, avoid using outdated data
                }
                return true;
            }

        };
    }

    /*
        Getter & Setter
     */

    public double getMaximumMovementSpeed() {
        return maximumMovementSpeed;
    }

    public void setMaximumMovementSpeed(double maximumMovementSpeed) {
        this.maximumMovementSpeed = maximumMovementSpeed;
    }

    public long getMaximumLocationUpdateInterval() {
        return maximumLocationUpdateInterval;
    }

    public void setMaximumLocationUpdateInterval(long maximumLocationUpdateInterval) {
        this.maximumLocationUpdateInterval = maximumLocationUpdateInterval;
    }

    public static LocationPredictor getLocationPredictor() {
        return getInstance().locationPredictor;
    }

    public void setLocationPredictor(LocationPredictor locationPredictor) {
        this.locationPredictor = locationPredictor;
    }

    public BeaconFilter getIndoorPositioningBeaconFilter() {
        return indoorPositioningBeaconFilter;
    }

    public void setIndoorPositioningBeaconFilter(BeaconFilter indoorPositioningBeaconFilter) {
        this.indoorPositioningBeaconFilter = indoorPositioningBeaconFilter;
    }

    public GenericBeaconFilter getUsableIndoorPositioningBeaconFilter() {
        return usableIndoorPositioningBeaconFilter;
    }

    public void setUsableIndoorPositioningBeaconFilter(GenericBeaconFilter usableIndoorPositioningBeaconFilter) {
        this.usableIndoorPositioningBeaconFilter = usableIndoorPositioningBeaconFilter;
    }

    public void setRootMeanSquareThreshold(double rootMeanSquareThreshold) {
        this.rootMeanSquareThreshold = rootMeanSquareThreshold;
    }

    public double getRootMeanSquareThreshold() {
        return rootMeanSquareThreshold;
    }

    public int getMinimumRssiThreshold() {
        return minimumRssiThreshold;
    }

    public void setMinimumRssiThreshold(int minimumRssiThreshold) {
        this.minimumRssiThreshold = minimumRssiThreshold;
    }
}

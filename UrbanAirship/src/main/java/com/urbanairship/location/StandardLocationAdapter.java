/*
Copyright 2009-2014 Urban Airship Inc. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
this list of conditions and the following disclaimer in the documentation
and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE URBAN AIRSHIP INC ``AS IS'' AND ANY EXPRESS OR
IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO
EVENT SHALL URBAN AIRSHIP INC OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.urbanairship.location;

import android.app.PendingIntent;
import android.content.Context;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;

import com.urbanairship.Logger;
import com.urbanairship.PendingResult;
import com.urbanairship.util.UAStringUtil;

import java.util.List;

/**
 * Location adapter for the standard Android location.
 * <p/>
 * The adapter tries to mimic the Fused Location Provider as much as possible by
 * automatically selecting the best provider based on the request settings. It
 * will reevaluate the best provider when providers are enabled and disabled.
 */
class StandardLocationAdapter implements LocationAdapter {

    /**
     * Passive provider name. Only available in api 8.
     */
    private static final String PASSIVE_PROVIDER = "passive";

    private final LocationManager locationManager;

    /**
     * Creates a standard location provider.
     *
     * @param context The application context.
     */
    StandardLocationAdapter(Context context) {
        this.locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    @Override
    public void requestLocationUpdates(LocationRequestOptions options, PendingIntent intent) {
        Criteria criteria = createCriteria(options);

        List<String> providers = locationManager.getProviders(criteria, false);
        if (providers != null) {
            for (String provider : providers) {
                Logger.verbose("Standard Android location update " +
                        "listening provider enable/disabled for: " + provider);
                locationManager.requestLocationUpdates(provider, Long.MAX_VALUE, Float.MAX_VALUE, intent);
            }
        }


        String bestProvider = getBestProvider(criteria, options);
        if (!UAStringUtil.isEmpty(bestProvider)) {
            Logger.verbose("Standard Android location requesting location updates from provider: " + bestProvider);

            locationManager.requestLocationUpdates(
                    bestProvider,
                    options.getMinTime(),
                    options.getMinDistance(),
                    intent);
        }
    }

    @Override
    public boolean connect() {
        return true;
    }

    @Override
    public void disconnect() {
    }

    @Override
    public void cancelLocationUpdates(PendingIntent intent) {
        Logger.verbose("Standard Android location canceling location updates.");
        locationManager.removeUpdates(intent);
    }

    @Override
    public PendingResult<Location> requestSingleLocation(LocationRequestOptions options) {
        return new SingleLocationRequest(options);
    }


    /**
     * Creates a location criteria from a LocationRequestOptions.
     *
     * @param options The locationRequestOptions.
     * @return A criteria created from the supplied options.
     */
    private Criteria createCriteria(LocationRequestOptions options) {

        Criteria criteria = new Criteria();

        switch (options.getPriority()) {
            case LocationRequestOptions.PRIORITY_HIGH_ACCURACY:
                criteria.setAccuracy(Criteria.ACCURACY_FINE);
                criteria.setPowerRequirement(Criteria.POWER_HIGH);
                break;
            case LocationRequestOptions.PRIORITY_BALANCED_POWER_ACCURACY:
                criteria.setAccuracy(Criteria.ACCURACY_COARSE);
                criteria.setPowerRequirement(Criteria.POWER_MEDIUM);
                break;
            case LocationRequestOptions.PRIORITY_LOW_POWER:
            case LocationRequestOptions.PRIORITY_NO_POWER:
                criteria.setAccuracy(Criteria.NO_REQUIREMENT);
                criteria.setPowerRequirement(Criteria.POWER_LOW);
                break;
        }

        return criteria;
    }

    /**
     * Gets the best provider for the given criteria and location request options.
     *
     * @param criteria The criteria that the returned providers must match.
     * @param options The location request options.
     * @return The best provider, or null if one does not exist.
     */
    private String getBestProvider(Criteria criteria, LocationRequestOptions options) {
        if (options.getPriority() == LocationRequestOptions.PRIORITY_NO_POWER) {
            List<String> availableProviders = locationManager.getProviders(criteria, true);
            if (availableProviders == null) {
                return null;
            }
            if (availableProviders.contains(PASSIVE_PROVIDER)) {
                return PASSIVE_PROVIDER;
            } else {
                return null;
            }
        } else {
            return locationManager.getBestProvider(criteria, true);
        }
    }


    /**
     * Class that encapsulated the actual request to the standard Android
     * location.
     */
    private class SingleLocationRequest extends PendingLocationResult {

        private Criteria criteria;
        private LocationRequestOptions options;
        private String currentProvider = null;

        private AndroidLocationListener currentProviderListener;
        private AndroidLocationListener providerEnabledListeners;

        /**
         * SingleLocationRequest constructor.
         *
         * @param options The locationRequestOptions.
         */
        SingleLocationRequest(final LocationRequestOptions options) {

            this.options = options;
            this.criteria = createCriteria(options);

            currentProviderListener = new AndroidLocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    stopUpdates();
                    setResult(location);
                }

                @Override
                public void onProviderDisabled(String provider) {
                    Logger.verbose("Standard Android location provider disabled: " + provider);
                    synchronized (this) {
                        if (!isDone()) {
                            listenForLocationChanges();
                        }
                    }
                }
            };

            providerEnabledListeners = new AndroidLocationListener() {
                @Override
                public void onProviderEnabled(String provider) {
                    Logger.verbose("Standard Android location provider enabled: " + provider);
                    synchronized (this) {
                        if (!isDone()) {
                            String bestProvider = getBestProvider(criteria, options);
                            if (bestProvider != null && !bestProvider.equals(currentProvider)) {
                                listenForLocationChanges();
                            }
                        }
                    }
                }
            };

            if (options.getPriority() != LocationRequestOptions.PRIORITY_NO_POWER) {
                listenForProvidersEnabled();
            }

            listenForLocationChanges();
        }

        private void listenForLocationChanges() {
            if (currentProvider != null) {
                locationManager.removeUpdates(currentProviderListener);
            }

            String bestProvider = getBestProvider(criteria, options);

            currentProvider = bestProvider;

            if (bestProvider != null) {
                Logger.verbose("Standard Android location single request using provider: " + bestProvider);

                locationManager.requestLocationUpdates(bestProvider, 0, 0, currentProviderListener);
            }
        }

        /**
         * Adds a listener to every provider to be notified when providers
         * are enabled/disabled.
         */
        private void listenForProvidersEnabled() {
            List<String> providers = locationManager.getProviders(criteria, false);
            if (providers != null) {
                for (String provider : providers) {
                    Logger.verbose("Standard Android location single location request " +
                            "listening provider enable/disabled for: " + provider);
                    locationManager.requestLocationUpdates(provider,
                            Long.MAX_VALUE,
                            Float.MAX_VALUE,
                            providerEnabledListeners);
                }
            }
        }

        @Override
        protected void onCancel() {
            Logger.verbose("Standard Android location canceling single request.");
            stopUpdates();
        }

        /**
         * Stop listening for updates.
         */
        private void stopUpdates() {
            locationManager.removeUpdates(currentProviderListener);
            locationManager.removeUpdates(providerEnabledListeners);
        }
    }


    /**
     * Android location listener used to listen for changes on the current best provider.
     */
    private static class AndroidLocationListener implements android.location.LocationListener {
        @Override
        public void onLocationChanged(Location location) {
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    }
}
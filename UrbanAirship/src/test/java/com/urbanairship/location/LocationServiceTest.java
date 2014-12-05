package com.urbanairship.location;

import android.app.PendingIntent;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import com.urbanairship.RobolectricGradleTestRunner;
import com.urbanairship.TestApplication;
import com.urbanairship.analytics.Analytics;
import com.urbanairship.analytics.LocationEvent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;
import org.robolectric.Robolectric;
import org.robolectric.shadows.ShadowLooper;

import static junit.framework.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Location Service
 */
@RunWith(RobolectricGradleTestRunner.class)
public class LocationServiceTest {

    LocationService locationService;
    ShadowLooper shadowLooper;
    UALocationProvider mockProvider;
    UALocationManager locationManager;
    Messenger mockMessenger;

    Analytics mockAnalytics;

    Handler locationHandler;

    @Before
    public void setup() {
        mockMessenger = mock(Messenger.class);

        mockProvider = mock(UALocationProvider.class);

        mockAnalytics = mock(Analytics.class);
        TestApplication.getApplication().setAnalytics(mockAnalytics);

        shadowLooper = Robolectric.shadowOf(Looper.myLooper());

        locationManager = new UALocationManager(TestApplication.getApplication(), TestApplication.getApplication().preferenceDataStore);
        TestApplication.getApplication().setLocationManager(locationManager);

        locationService = new LocationService() {
            @Override
            public void onCreate() {
                handler = new IncomingHandler(Looper.myLooper());
                locationProvider = mockProvider;
                locationHandler = handler;
            }
        };


        // Reset the static properties
        LocationService.areUpdatesStopped = false;
        LocationService.lastUpdateOptions = null;

        locationService.onCreate();

        locationManager.setBackgroundLocationAllowed(true);
        locationManager.setLocationUpdatesEnabled(true);
    }

    /**
     * Test starting updates requests updates from the provider.
     */
    @Test
    public void testStartUpdates() {
        LocationRequestOptions options = locationManager.getLocationRequestOptions();

        // Request updates
        sendIntent(LocationService.ACTION_START_UPDATES);

        // Request the update again, should ignore the update for the same request options.
        sendIntent(LocationService.ACTION_START_UPDATES);

        // Should of connected to the provider
        verify(mockProvider, times(1)).connect();

        // Should cancel any previous updates
        verify(mockProvider, times(1)).cancelRequests(any(PendingIntent.class));

        // Should do the actual requests
        verify(mockProvider, times(1)).requestLocationUpdates(eq(options), any(PendingIntent.class));
    }

    /**
     * Test starting location updates again after location options change.
     */
    @Test
    public void testStartUpdatesDifferentRequestOptions() {
        LocationRequestOptions options = new LocationRequestOptions.Builder().setMinDistance(100).create();

        // Start updates with the default location options
        sendIntent(LocationService.ACTION_START_UPDATES);

        // Set new location options
        locationManager.setLocationRequestOptions(options);

        // Start the service again
        sendIntent(LocationService.ACTION_START_UPDATES);

        // Should of connected to the provider 2 times - 1 for each update request.
        verify(mockProvider, times(2)).connect();

        // Should of cancel previous requests 2 times - 1 for each update request.
        verify(mockProvider, times(2)).cancelRequests(any(PendingIntent.class));

        // Should do the actual requests
        verify(mockProvider, times(1)).requestLocationUpdates(eq(options), any(PendingIntent.class));
    }

    /**
     * Test stopping location updates.
     */
    @Test
    public void testStopUpdates() {
        sendIntent(LocationService.ACTION_STOP_UPDATES);

        // Should of cancel previous requests
        verify(mockProvider, times(1)).cancelRequests(any(PendingIntent.class));

        // Send stop again to verify it doesn't bother canceling requests
        sendIntent(LocationService.ACTION_STOP_UPDATES);

        // Should skip stopping if it knows it previously stopped.
        verify(mockProvider, times(1)).cancelRequests(any(PendingIntent.class));
    }

    /**
     * Test location updates records location in analytics.
     */
    @Test
    public void testLocationUpdate() {
        Location location = new Location("location");
        Bundle bundle = new Bundle();
        bundle.putParcelable(LocationManager.KEY_LOCATION_CHANGED, location);

        sendIntent(LocationService.ACTION_LOCATION_UPDATE, bundle);
        verify(mockAnalytics).recordLocation(location, null, LocationEvent.UpdateType.CONTINUOUS);
    }

    /**
     * Test location updates with a null location does nothing.
     */
    @Test
    public void testLocationUpdateNullLocation() {
        sendIntent(LocationService.ACTION_LOCATION_UPDATE);

        // Should not call record location
        verify(mockAnalytics, times(0)).recordLocation(any(Location.class));
    }

    /**
     * Test location updates are ignored if location is disabled.
     */
    @Test
    public void testLocationUpdateLocationDisabled() {
        locationManager.setBackgroundLocationAllowed(false);
        locationManager.setLocationUpdatesEnabled(false);

        Location location = new Location("location");
        Bundle bundle = new Bundle();
        bundle.putParcelable(LocationManager.KEY_LOCATION_CHANGED, location);

        sendIntent(LocationService.ACTION_LOCATION_UPDATE, bundle);

        // Should not call record location
        verify(mockAnalytics, times(0)).recordLocation(any(Location.class));
    }

    /**
     * Test location updates are canceled and requested again when
     * a provider is enabled/disabled.
     */
    @Test
    public void testLocationUpdateProviderChange() {
        Bundle bundle = new Bundle();
        bundle.putBoolean(LocationManager.KEY_PROVIDER_ENABLED, true);

        sendIntent(LocationService.ACTION_LOCATION_UPDATE, bundle);

        // Should of connected to the provider
        verify(mockProvider, times(1)).connect();

        // Should cancel any previous updates
        verify(mockProvider, times(1)).cancelRequests(any(PendingIntent.class));

        // Should do the actual requests
        verify(mockProvider, times(1)).requestLocationUpdates(any(LocationRequestOptions.class), any(PendingIntent.class));
    }

    /**
     * Test location updates parse the last request options from
     * the update and use them to avoid restarting the location
     * when a start is requested.
     */
    @Test
    public void testStartingLocationAfterUpdate() {
        LocationRequestOptions options = locationManager.getLocationRequestOptions();
        Bundle extras = LocationService.createRequestOptionsBundle(options);

        sendIntent(LocationService.ACTION_LOCATION_UPDATE, extras);

        sendIntent(LocationService.ACTION_START_UPDATES);

        // Verify no request was made
        verify(mockProvider, times(0)).connect();
        verify(mockProvider, times(0)).cancelRequests(any(PendingIntent.class));
        verify(mockProvider, times(0)).requestLocationUpdates(eq(options), any(PendingIntent.class));
    }

    /**
     * Test requesting a single location update.
     */
    @Test
    public void testRequestSingleUpdate() throws RemoteException {
        LocationRequestOptions options = LocationRequestOptions.createDefaultOptions();

        Bundle extras = LocationService.createRequestOptionsBundle(options);
        final Location location = new Location("Location");

        PendingLocationResult request = new PendingLocationResult() {
            @Override
            protected void onCancel() {

            }
        };

        when(mockProvider.requestSingleLocation(eq(options))).thenReturn(request);

        Message message = Message.obtain(null, LocationService.MSG_REQUEST_SINGLE_LOCATION);
        message.arg1 = 101;
        message.replyTo = mockMessenger;
        message.setData(extras);

        locationHandler.handleMessage(message);

        // Verify the request was made
        verify(mockProvider, times(1)).connect();
        verify(mockProvider, times(1)).requestSingleLocation(eq(options));

        // Set the result
        request.setResult(location);

        // Verify the messenger was notified of the result
        verify(mockMessenger).send(argThat(new ArgumentMatcher<Message>() {
            @Override
            public boolean matches(Object argument) {
                Message message = (Message) argument;
                return message.what == LocationService.MSG_SINGLE_REQUEST_RESULT &&
                        message.arg1 == 101 &&
                        message.obj.equals(location);
            }
        }));

        // Verify the location was recorded
        verify(mockAnalytics).recordLocation(location, options, LocationEvent.UpdateType.SINGLE);
    }

    /**
     * Test requesting a single location update with invalid options.
     */
    @Test
    public void testRequestSingleUpdateFailed() throws RemoteException {
        LocationRequestOptions options = LocationRequestOptions.createDefaultOptions();
        Bundle extras = LocationService.createRequestOptionsBundle(options);

        Message message = Message.obtain(null, LocationService.MSG_REQUEST_SINGLE_LOCATION);
        message.arg1 = 101;
        message.replyTo = mockMessenger;
        message.setData(extras);
        locationHandler.handleMessage(message);

        // Verify the messenger was notified of the result
        verify(mockMessenger).send(argThat(new ArgumentMatcher<Message>() {
            @Override
            public boolean matches(Object argument) {
                Message message = (Message) argument;
                return message.what == LocationService.MSG_SINGLE_REQUEST_RESULT &&
                        message.arg1 == 101 &&
                        message.obj == null;
            }
        }));
    }

    /**
     * Test requesting a single location update when provider fails to create a
     * request.
     */
    @Test
    public void testRequestSingleUpdateInvalidOptions() throws RemoteException {
        Message message = Message.obtain(null, LocationService.MSG_REQUEST_SINGLE_LOCATION);
        message.arg1 = 101;
        message.replyTo = mockMessenger;
        locationHandler.handleMessage(message);

        // Verify no request was made
        verify(mockProvider, times(0)).connect();
        verify(mockProvider, times(0)).requestSingleLocation(any(LocationRequestOptions.class));

        // Verify the messenger was notified of the result
        verify(mockMessenger).send(argThat(new ArgumentMatcher<Message>() {
            @Override
            public boolean matches(Object argument) {
                Message message = (Message) argument;
                return message.what == LocationService.MSG_SINGLE_REQUEST_RESULT &&
                        message.arg1 == 101 &&
                        message.obj == null;
            }
        }));
    }

    /**
     * Test canceling a single location request.
     */
    @Test
    public void testCancelSingleUpdate() throws RemoteException {
        LocationRequestOptions options = LocationRequestOptions.createDefaultOptions();
        Bundle extras = LocationService.createRequestOptionsBundle(options);
        final Location location = new Location("Location");

        PendingLocationResult request = new PendingLocationResult() {
            @Override
            protected void onCancel() {

            }
        };

        when(mockProvider.requestSingleLocation(eq(options))).thenReturn(request);

        // Request the update
        Message message = Message.obtain(null, LocationService.MSG_REQUEST_SINGLE_LOCATION);
        message.arg1 = 101;
        message.replyTo = mockMessenger;
        message.setData(extras);
        locationHandler.handleMessage(message);

        // Cancel the update
        message = Message.obtain(null, LocationService.MSG_CANCEL_SINGLE_LOCATION_REQUEST);
        message.arg1 = 101;
        message.replyTo = mockMessenger;
        locationHandler.handleMessage(message);

        // Verify the request was canceled
        assertTrue("Request should be canceled", request.isCanceled());

        // Set the result
        request.setResult(location);

        // Verify the messenger was not notified of the result
        verify(mockMessenger, times(0)).send(any(Message.class));
    }

    /**
     * Test subscribing to location updates.
     */
    @Test
    public void testSubscribingLocationUpdates() throws RemoteException {
        // Subscribe to updates
        Message message = Message.obtain(null, LocationService.MSG_SUBSCRIBE_UPDATES);
        message.replyTo = mockMessenger;
        locationHandler.handleMessage(message);

        // Send a location update
        final Location location = new Location("location");
        Bundle bundle = new Bundle();
        bundle.putParcelable(LocationManager.KEY_LOCATION_CHANGED, location);
        sendIntent(LocationService.ACTION_LOCATION_UPDATE, bundle);

        // Verify the messenger was notified of the result
        verify(mockMessenger).send(argThat(new ArgumentMatcher<Message>() {
            @Override
            public boolean matches(Object argument) {
                Message message = (Message) argument;
                return message.what == LocationService.MSG_NEW_LOCATION_UPDATE &&
                        message.obj.equals(location);
            }
        }));

        // Unsubscribe from updates
        message = Message.obtain(null, LocationService.MSG_UNSUBSCRIBE_UPDATES);
        message.replyTo = mockMessenger;
        locationHandler.handleMessage(message);

        // Send another location
        sendIntent(LocationService.ACTION_LOCATION_UPDATE, bundle);

        // Verify the messenger was notified only from the previous location
        verify(mockMessenger, times(1)).send(argThat(new ArgumentMatcher<Message>() {
            @Override
            public boolean matches(Object argument) {
                Message message = (Message) argument;
                return message.what == LocationService.MSG_NEW_LOCATION_UPDATE &&
                        message.obj.equals(location);
            }
        }));
    }

    /**
     * Helper method to simulate sending an intent to the location service.
     *
     * @param action The action to send.
     */
    private void sendIntent(String action) {
        sendIntent(action, null);
    }

    /**
     * Helper method to simulate sending an intent to the location service.
     *
     * @param action The action to send.
     * @param extras Optional extras.
     */
    private void sendIntent(String action, Bundle extras) {
        Intent intent = new Intent();
        intent.setAction(action);
        if (extras != null) {
            intent.putExtras(extras);
        }
        locationService.onStartCommand(intent, 0, 1);
        shadowLooper.runToEndOfTasks();
    }

}
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

package com.urbanairship.analytics;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Looper;

import com.urbanairship.AirshipConfigOptions;
import com.urbanairship.RobolectricGradleTestRunner;
import com.urbanairship.TestApplication;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricGradleTestRunner.class)
public class AnalyticsTest {

    Analytics analytics;
    ActivityMonitor.Listener activityMonitorListener;
    ActivityMonitor mockActivityMonitor;
    ShadowApplication shadowApplication;

    @Before
    public void setup() {
        mockActivityMonitor = Mockito.mock(ActivityMonitor.class);
        ArgumentCaptor<ActivityMonitor.Listener> listenerCapture = ArgumentCaptor.forClass(ActivityMonitor.Listener.class);

        Mockito.doNothing().when(mockActivityMonitor).setListener(listenerCapture.capture());
        this.analytics = new Analytics(TestApplication.getApplication(), TestApplication.getApplication().preferenceDataStore, new AirshipConfigOptions(), mockActivityMonitor);

        activityMonitorListener = listenerCapture.getValue();
        assertNotNull("Should set the listener on create", activityMonitorListener);

        // Replace the executor so it adds events on the same thread

        shadowApplication = Robolectric.shadowOf(Robolectric.application);
        shadowApplication.clearStartedServices();

        TestApplication.getApplication().setAnalytics(analytics);
    }

    /**
     * Test that a session id is created when analytics is created
     */
    @Test
    public void testOnCreate() {
        assertNotNull("Session id should generate on create", analytics.getSessionId());
    }

    /**
     * Test that when the app goes into the foreground, a new
     * session id is created, a broadcast is sent for foreground, isForegorund
     * is set to true, and a foreground event is added to the event service.
     */
    @Test
    public void testOnForeground() {
        // Start analytics in the background
        activityMonitorListener.onBackground(0);

        shadowApplication.clearStartedServices();
        assertFalse(analytics.isAppInForeground());

        // Grab the session id to compare it to a new session id
        String sessionId = analytics.getSessionId();

        activityMonitorListener.onForeground(0);

        // Verify that we generate a new session id
        assertNotNull(analytics.getSessionId());
        assertNotSame("A new session id should be generated on foreground", analytics.getSessionId(), sessionId);


        // Verify isAppInForeground is true
        assertTrue(analytics.isAppInForeground());

        // Verify we sent a broadcast intent for app foreground
        List<Intent> broadcasts = shadowApplication.getBroadcastIntents();
        assertEquals("Should of sent a foreground broadcast",
                broadcasts.get(broadcasts.size() - 1).getAction(), Analytics.ACTION_APP_FOREGROUND);
    }

    /**
     * Test that when the app goes into the background, the conversion send id is
     * cleared, a background event is sent to be added, and a broadcast for background
     * is sent.
     */
    @Test
    public void testOnBackground() {
        // Start analytics in the foreground
        activityMonitorListener.onForeground(0);
        shadowApplication.clearStartedServices();
        assertTrue(analytics.isAppInForeground());

        analytics.setConversionSendId("some-id");

        activityMonitorListener.onBackground(0);

        // Verify that we clear the conversion send id
        assertNull("App background should clear the conversion send id", analytics.getConversionSendId());

        // Verify that a app background event is sent to the service to be added
        Intent addEventIntent = shadowApplication.getNextStartedService();
        assertNotNull("Going into background should start the event service", addEventIntent);
        assertEquals("Should add an intent with action ADD", addEventIntent.getAction(), EventService.ACTION_ADD);
        assertEquals("Should add an app background event", AppBackgroundEvent.TYPE,
                addEventIntent.getStringExtra(EventService.EXTRA_EVENT_TYPE));

        // Verify isAppInForeground is false
        assertFalse(analytics.isAppInForeground());

        // Verify we sent a broadcast intent for app background
        List<Intent> broadcasts = shadowApplication.getBroadcastIntents();
        assertEquals("Should of sent a background broadcast",
                broadcasts.get(broadcasts.size() - 1).getAction(), Analytics.ACTION_APP_BACKGROUND);
    }

    /**
     * Test setting the conversion conversion send id
     */
    @Test
    public void testSetConversionPushId() {
        analytics.setConversionSendId("some-id");
        assertEquals("Conversion send Id is unable to be set", analytics.getConversionSendId(), "some-id");

        analytics.setConversionSendId(null);
        assertNull("Conversion send Id is unable to be cleared", analytics.getConversionSendId());
    }

    /**
     * Test activity started when life cycle calls enabled (API >= 14)
     */
    @Test
    @Config(reportSdk = 14)
    public void testActivityStartedLifeCyclesEnabled() {
        Activity activity = new Activity();
        Analytics.activityStarted(activity);

        // Activity started is posted on the main looper
        Robolectric.shadowOf(Looper.myLooper()).runToEndOfTasks();

        // Verify that the activity monitor was called with manual instrumentation
        Mockito.verify(mockActivityMonitor).activityStarted(Mockito.eq(activity),  Mockito.eq(ActivityMonitor.Source.MANUAL_INSTRUMENTATION), Mockito.anyLong());

        // Verify it did not start the event service to add an event.  Should be
        // done with life cycle calls
        Intent addEventIntent = shadowApplication.getNextStartedService();
        assertNull("Life cycle events should add the activity events", addEventIntent);
    }

    /**
     * Test activity started when life cycle calls disabled
     */
    @Test
    @Config(reportSdk = 10)
    public void testActivityStartedLifeCyclesDisabled() {
        Activity activity = new Activity();
        Analytics.activityStarted(activity);

        // Activity started is posted on the main looper
        Robolectric.shadowOf(Looper.getMainLooper()).runToEndOfTasks();

        // Verify that the activity monitor was called with manual instrumentation
        Mockito.verify(mockActivityMonitor).activityStarted(Mockito.eq(activity), Mockito.eq(ActivityMonitor.Source.MANUAL_INSTRUMENTATION), Mockito.anyLong());
    }

    /**
     * Test activity stopped when life cycle calls enabled (API >= 14)
     */
    @Test
    @Config(reportSdk = 14)
    public void testActivityStoppedLifeCyclesEnabled() {
        Activity activity = new Activity();
        Analytics.activityStopped(activity);

        // Activity stopped is posted on the main looper
        Robolectric.shadowOf(Looper.getMainLooper()).runToEndOfTasks();


        // Verify that the activity monitor was called with manual instrumentation
        Mockito.verify(mockActivityMonitor).activityStopped(Mockito.eq(activity), Mockito.eq(ActivityMonitor.Source.MANUAL_INSTRUMENTATION), Mockito.anyLong());

        // Verify it did not start the event service to add an event.  Should be
        // done with life cycle calls
        Intent addEventIntent = shadowApplication.getNextStartedService();
        assertNull("Life cycle events should add the activity events", addEventIntent);
    }

    /**
     * Test activity stopped when life cycle calls disabled
     */
    @Test
    @Config(reportSdk = 10)
    public void testActivityStoppedLifeCyclesDisabled() {
        Activity activity = new Activity();
        Analytics.activityStopped(activity);

        // Activity stopped is posted on the main looper
        Robolectric.shadowOf(Looper.getMainLooper()).runToEndOfTasks();


        // Verify that the activity monitor was called with manual instrumentation
        Mockito.verify(mockActivityMonitor).activityStopped(Mockito.eq(activity), Mockito.eq(ActivityMonitor.Source.MANUAL_INSTRUMENTATION), Mockito.anyLong());
    }

    /**
     * Test adding an event
     */
    @Test
    public void testAddEvent() {
        Event event = Mockito.mock(Event.class);
        Mockito.when(event.getEventId()).thenReturn("event-id");
        Mockito.when(event.getType()).thenReturn("event-type");
        Mockito.when(event.createEventPayload(Mockito.anyString())).thenReturn("event-data");
        Mockito.when(event.getEventId()).thenReturn("event-id");
        Mockito.when(event.getTime()).thenReturn("1000");

        analytics.addEvent(event);

        // Verify the intent contains the content values of the event
        Intent addEventIntent = shadowApplication.getNextStartedService();
        assertNotNull(addEventIntent);
        assertEquals("Should add an intent with action ADD", addEventIntent.getAction(), EventService.ACTION_ADD);
        assertEquals(addEventIntent.getStringExtra(EventService.EXTRA_EVENT_ID), "event-id");
        assertEquals(addEventIntent.getStringExtra(EventService.EXTRA_EVENT_DATA), "event-data");
        assertEquals(addEventIntent.getStringExtra(EventService.EXTRA_EVENT_TYPE), "event-type");
        assertEquals(addEventIntent.getStringExtra(EventService.EXTRA_EVENT_TIME_STAMP), "1000");
        assertEquals(addEventIntent.getStringExtra(EventService.EXTRA_EVENT_SESSION_ID), analytics.getSessionId());
    }

    /**
     * Test adding an event when analytics is disabled
     */
    @Test
    public void testAddEventDisabledAnalytics() {
        AirshipConfigOptions options = new AirshipConfigOptions();
        options.analyticsEnabled = false;

        analytics = new Analytics(TestApplication.getApplication(), TestApplication.getApplication().preferenceDataStore, options);

        analytics.addEvent(new AppForegroundEvent(100));
        Intent addEventIntent = shadowApplication.getNextStartedService();
        assertNull("Should not add events if analytics is disabled", addEventIntent);
    }

    /**
     * Test adding a null event
     */
    @Test
    public void testAddNullEvent() {
        analytics.addEvent(null);
        Intent addEventIntent = shadowApplication.getNextStartedService();
        assertNull("Should not start the event service to add a null event", addEventIntent);
    }

    /**
     * Test life cycle activity events when an activity is started
     */
    @SuppressLint("NewApi")
    @Config(reportSdk = 14)
    public void testActivityLifeCycleEventsActivityStarted() {
        Activity activity = new Activity();

        TestApplication.getApplication().callback.onActivityStarted(activity);

        // The activity started is posted on the looper
        Robolectric.shadowOf(Looper.myLooper()).runToEndOfTasks();


        // Verify that the activity monitor was called with auto instrumentation
        Mockito.verify(mockActivityMonitor).activityStarted(Mockito.eq(activity), Mockito.eq(ActivityMonitor.Source.AUTO_INSTRUMENTATION), Mockito.anyLong());
    }

    /**
     * Test life cycle activity events when an activity is stopped
     */
    @SuppressLint("NewApi")
    @Config(reportSdk = 14)
    public void testActivityLifeCycleEventsActivityStopped() {
        Activity activity = new Activity();

        // The activity stopped is posted on the looper
        TestApplication.getApplication().callback.onActivityStopped(activity);

        // Verify that the activity monitor was called with auto instrumentation
        Mockito.verify(mockActivityMonitor).activityStopped(Mockito.eq(activity), Mockito.eq(ActivityMonitor.Source.AUTO_INSTRUMENTATION), Mockito.anyLong());
    }

}
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

package com.urbanairship.actions;

import android.content.Intent;
import android.net.Uri;

import com.urbanairship.RobolectricGradleTestRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.shadows.ShadowApplication;

import java.util.HashMap;
import java.util.Map;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

@RunWith(RobolectricGradleTestRunner.class)
public class LandingPageActionTest {

    private LandingPageAction action;

    @Before
    public void setup() {
        action = new LandingPageAction();
    }

    /**
     * Test accepts arguments
     */
    @Test
    public void testAcceptsArguments() {
        // Basic URIs
        verifyAcceptsArgumentValue("www.urbanairship.com", true);
        verifyAcceptsArgumentValue(Uri.parse("www.urbanairship.com"), true);

        // Content URIs
        verifyAcceptsArgumentValue("u:<~@rH7,ASuTABk.~>", true);
        verifyAcceptsArgumentValue(Uri.parse("u:<~@rH7,ASuTABk.~>"), true);

        // Payload
        Map<String, Object> payload = new HashMap<String, Object>();
        payload.put("url", "http://example.com");
        payload.put("cache_on_receive", true);
        verifyAcceptsArgumentValue(payload, true);
    }

    /**
     * Test accepts arguments rejects payloads that do not
     * define a url
     */
    @Test
    public void testRejectsArguments() {
        verifyAcceptsArgumentValue(null, false);
        verifyAcceptsArgumentValue("", false);
        verifyAcceptsArgumentValue("u:", true);
        verifyAcceptsArgumentValue(Uri.parse("u:"), true);


        // Empty payload
        Map<String, Object> payload = new HashMap<String, Object>();
        verifyAcceptsArgumentValue(payload, false);
    }

    /**
     * Test perform for every situation the action accepts
     */
    @Test
    public void testPerform() {
        // Verify scheme less URIs turn into https
        verifyPerform("www.urbanairship.com", "https://www.urbanairship.com");
        verifyPerform(Uri.parse("www.urbanairship.com"), "https://www.urbanairship.com");

        // Verify common file URIs
        verifyPerform("file://urbanairship.com", "file://urbanairship.com");
        verifyPerform("https://www.urbanairship.com", "https://www.urbanairship.com");
        verifyPerform("http://www.urbanairship.com", "http://www.urbanairship.com");

        // Verify content URIs
        verifyPerform("u:<~@rH7,ASuTABk.~>", "https://dl.urbanairship.com/aaa/app_key/%3C%7E%40rH7%2CASuTABk.%7E%3E");
        verifyPerform(Uri.parse("u:<~@rH7,ASuTABk.~>"), "https://dl.urbanairship.com/aaa/app_key/%3C%7E%40rH7%2CASuTABk.%7E%3E");

        // Verify basic payload
        Map<String, Object> payload = new HashMap<String, Object>();
        payload.put("url", "http://example.com");
        verifyPerform(payload, "http://example.com");

        // Verify payload without a scheme
        payload.put("url", "www.example.com");
        verifyPerform(payload, "https://www.example.com");
    }

    private void verifyPerform(Object value, String expectedIntentData) {
        ShadowApplication application = Robolectric.getShadowApplication();

        Situation[] situations = new Situation[] {
                Situation.PUSH_OPENED,
                Situation.MANUAL_INVOCATION,
                Situation.WEB_VIEW_INVOCATION
        };

        for (Situation situation : situations) {
            ActionArguments args = new ActionArguments(situation, value);

            ActionResult result = action.perform("name", args);
            assertNull("Should return null for situation " + situation, result.getValue());

            Intent intent = application.getNextStartedActivity();
            assertEquals("Invalid intent action for situation " + situation,
                    LandingPageAction.SHOW_LANDING_PAGE_INTENT_ACTION, intent.getAction());

            assertEquals("Invalid intent flags for situation " + situation,
                    Intent.FLAG_ACTIVITY_NEW_TASK | intent.FLAG_ACTIVITY_SINGLE_TOP, intent.getFlags());

            assertEquals("Wrong intent data for situation " + situation,
                    expectedIntentData, intent.getDataString());
        }

    }

    private void verifyAcceptsArgumentValue(Object value, boolean shouldAccept) {
        Situation[] situations = new Situation[] {
                Situation.PUSH_OPENED,
                Situation.MANUAL_INVOCATION,
                Situation.WEB_VIEW_INVOCATION,
                Situation.PUSH_RECEIVED,
                Situation.FOREGROUND_NOTIFICATION_ACTION_BUTTON
        };

        for (Situation situation : situations) {
            ActionArguments args = new ActionArguments(situation, value);
            if (shouldAccept) {
                assertTrue("Should accept arguments in situation " + situation,
                        action.acceptsArguments(args));
            } else {
                assertFalse("Should reject arguments in situation " + situation,
                        action.acceptsArguments(args));
            }

        }
    }
}
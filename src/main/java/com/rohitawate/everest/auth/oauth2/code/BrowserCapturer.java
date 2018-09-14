package com.rohitawate.everest.auth.oauth2.code;

import com.rohitawate.everest.server.CaptureServer;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Opens the OAuth 2.0 authorization window in the user's browser
 * and captures the authorization grant by forcing redirects to a
 * local server.
 */
public class BrowserCapturer implements AuthorizationGrantCapturer {
    private String authURL;

    static final String LOCAL_SERVER_URL = "http://localhost:52849/granted";

    BrowserCapturer(String authURL) {
        this.authURL = authURL;
    }

    @Override
    public String getAuthorizationGrant() throws ExecutionException, InterruptedException {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CaptureServer server = new CaptureServer(52849, authURL);
        executor.submit(server);
        return server.get();
    }
}

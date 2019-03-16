/*
 * Copyright 2019 Rohit Awate.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.rohitawate.everest.auth.oauth2.code;

import com.rohitawate.everest.auth.captors.AuthorizationGrantCaptor;
import com.rohitawate.everest.auth.captors.BrowserCaptor;
import com.rohitawate.everest.auth.captors.CaptureMethod;
import com.rohitawate.everest.auth.captors.WebViewCaptor;
import com.rohitawate.everest.auth.oauth2.Flow;
import com.rohitawate.everest.auth.oauth2.OAuth2Provider;
import com.rohitawate.everest.auth.oauth2.code.exceptions.AccessTokenDeniedException;
import com.rohitawate.everest.auth.oauth2.code.exceptions.AuthWindowClosedException;
import com.rohitawate.everest.auth.oauth2.code.exceptions.NoAuthorizationGrantException;
import com.rohitawate.everest.auth.oauth2.code.exceptions.UnknownAccessTokenTypeException;
import com.rohitawate.everest.auth.oauth2.tokens.AuthCodeToken;
import com.rohitawate.everest.controllers.auth.oauth2.AuthorizationCodeController;
import com.rohitawate.everest.misc.EverestUtilities;
import com.rohitawate.everest.models.requests.HTTPConstants;
import com.rohitawate.everest.state.auth.AuthorizationCodeState;
import com.rohitawate.everest.state.auth.OAuth2FlowState;

import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Scanner;

/**
 * Authorization provider for OAuth 2.0's Authorization Code flow.
 * Makes requests to authorization and access token endpoints and returns
 * either the final 'Authorization' header or an AuthCodeToken object.
 */
public class AuthorizationCodeProvider implements OAuth2Provider {
    private AuthorizationCodeController controller;
    private AuthorizationCodeState state;

    public AuthorizationCodeProvider(AuthorizationCodeController controller) {
        this.controller = controller;
    }

    private void fetchAuthorizationGrant() throws Exception {
        if (this.state == null) {
            setState(controller.getState());
        }

        if (state.authGrant == null || state.authGrantUsed) {
            StringBuilder grantURLBuilder = new StringBuilder(state.authURL);
            grantURLBuilder.append("?response_type=code");
            grantURLBuilder.append("&client_id=");
            grantURLBuilder.append(state.clientID);
            grantURLBuilder.append("&redirect_uri=");
            grantURLBuilder.append(EverestUtilities.encodeURL(state.redirectURL));

            if (state.scope != null && !state.scope.isEmpty()) {
                grantURLBuilder.append("&scope=");
                grantURLBuilder.append(EverestUtilities.encodeURL(state.scope));
            }

            AuthorizationGrantCaptor captor;
            String captureKey = "code";
            switch (state.captureMethod) {
                // TODO: Re-use captors
                case CaptureMethod.WEB_VIEW:
                    captor = new WebViewCaptor(grantURLBuilder.toString(), captureKey);
                    break;
                default:
                    captor = new BrowserCaptor(grantURLBuilder.toString(), captureKey, Flow.AUTH_CODE);
            }

            state.authGrant = captor.getAuthorizationGrant();
            state.authGrantUsed = false;
        }
    }

    private void refreshAccessToken()
            throws AccessTokenDeniedException, UnknownAccessTokenTypeException, IOException {
        if (this.state == null) {
            setState(controller.getState());
        }

        URL tokenURL = new URL(state.accessTokenURL);
        StringBuilder tokenURLBuilder = new StringBuilder();
        tokenURLBuilder.append("client_id=");
        tokenURLBuilder.append(state.clientID);
        tokenURLBuilder.append("&client_secret=");
        tokenURLBuilder.append(state.clientSecret);
        tokenURLBuilder.append("&grant_type=refresh_token");
        tokenURLBuilder.append("&refresh_token=");
        tokenURLBuilder.append(state.accessToken.getRefreshToken());

        if (state.scope != null && !state.scope.isEmpty()) {
            tokenURLBuilder.append("&scope=");
            tokenURLBuilder.append(state.scope);
        }

        byte[] body = tokenURLBuilder.toString().getBytes(StandardCharsets.UTF_8);
        AccessTokenRequest tokenRequest = new AccessTokenRequest(tokenURL, body);

        // Hold on to refresh token
        String refreshToken = state.accessToken.getRefreshToken();
        state.accessToken = tokenRequest.authCodeToken;
        state.accessToken.setRefreshToken(refreshToken);
    }

    private void fetchNewAccessToken()
            throws NoAuthorizationGrantException, IOException, UnknownAccessTokenTypeException, AccessTokenDeniedException {
        if (this.state == null) {
            setState(controller.getState());
        }

        if (state.authGrant == null) {
            throw new NoAuthorizationGrantException(
                    "OAuth 2.0 Authorization Code: Authorization grant not found. Aborting access token fetch."
            );
        }

        URL tokenURL = new URL(state.accessTokenURL);
        StringBuilder tokenURLBuilder = new StringBuilder();
        tokenURLBuilder.append("client_id=");
        tokenURLBuilder.append(state.clientID);
        tokenURLBuilder.append("&client_secret=");
        tokenURLBuilder.append(state.clientSecret);
        tokenURLBuilder.append("&grant_type=authorization_code");
        tokenURLBuilder.append("&code=");
        tokenURLBuilder.append(state.authGrant);
        tokenURLBuilder.append("&redirect_uri=");
        tokenURLBuilder.append(state.redirectURL);
        if (state.scope != null && !state.scope.isEmpty()) {
            tokenURLBuilder.append("&scope=");
            tokenURLBuilder.append(state.scope);
        }

        byte[] body = tokenURLBuilder.toString().getBytes(StandardCharsets.UTF_8);
        AccessTokenRequest tokenRequest = new AccessTokenRequest(tokenURL, body);
        state.accessToken = tokenRequest.authCodeToken;
        state.authGrantUsed = true;
    }

    @Override
    public AuthCodeToken getAccessToken() throws Exception {
        setState(controller.getState());

        if (state.accessToken.getRefreshToken().isEmpty()) {
            fetchAuthorizationGrant();
            fetchNewAccessToken();
        } else {
            refreshAccessToken();
        }

        // This will display the new AuthCodeToken in the UI
        controller.setAccessToken(state.accessToken);

        return state.accessToken;
    }

    @Override
    public String getAuthHeader() throws Exception {
        if (this.state == null) {
            setState(controller.getState());
        }

        /*
            Integrated WebView calls will already have been resolved in AuthorizationCodeController,
            hence, they are skipped here.
         */
        if (state.accessToken.getAccessToken().isBlank()) {
            /*
                Checking if refreshToken is available. If it is, we can still fetch a new AuthCodeToken and complete
                this request without re-authorizing. (which would require a WebView which cannot be invoked here)
             */
            if (state.captureMethod.equals(CaptureMethod.WEB_VIEW) && state.accessToken.getRefreshToken().isEmpty()) {
                throw new AuthWindowClosedException();
            }

            // Resolving System Browser calls
            getAccessToken();
        }

        return String.format("%s %s", state.headerPrefix, state.accessToken.getAccessToken());
    }

    @Override
    public boolean isEnabled() {
        // Checking if there has been a change in the state of the enabled checkbox
        setState(controller.getState());
        return state.enabled;
    }

    @Override
    public void setState(OAuth2FlowState state) {
        if (state == null) {
            this.state = null;
            return;
        }

        this.state = (AuthorizationCodeState) state;

        if (this.state.redirectURL.isEmpty() || this.state.captureMethod.equals(CaptureMethod.BROWSER)) {
            this.state.redirectURL = BrowserCaptor.LOCAL_SERVER_URL;
        }

        if (state.headerPrefix == null || state.headerPrefix.isEmpty()) {
            state.headerPrefix = "Bearer";
        }
    }

    /**
     * Makes a request to the access token endpoint, parses the response into an AuthCodeToken object.
     */
    private static class AccessTokenRequest {
        private AuthCodeToken authCodeToken;
        private URL tokenURL;
        private byte[] body;
        private HttpURLConnection connection;

        /**
         * @param tokenURL The access token endpoint
         * @param body     The application/x-www-form-urlencoded request body
         */
        AccessTokenRequest(URL tokenURL, byte[] body)
                throws IOException, UnknownAccessTokenTypeException, AccessTokenDeniedException {
            this.tokenURL = tokenURL;
            this.body = body;
            openConnection();
            parseTokenResponse();
        }

        private void openConnection() throws IOException {
            connection = (HttpURLConnection) tokenURL.openConnection();
            connection.setRequestMethod(HTTPConstants.POST);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Length", String.valueOf(body.length));
            connection.setRequestProperty("charset", "utf-8");
            connection.setRequestProperty("Content-Type", MediaType.APPLICATION_FORM_URLENCODED);
            connection.getOutputStream().write(body);
        }

        private void parseTokenResponse()
                throws UnknownAccessTokenTypeException, AccessTokenDeniedException, IOException {
            StringBuilder tokenResponseBuilder = new StringBuilder();
            if (connection.getResponseCode() == 200) {
                Scanner scanner = new Scanner(connection.getInputStream());
                while (scanner.hasNext())
                    tokenResponseBuilder.append(scanner.nextLine());
                // Removes the "charset" part
                String contentType = connection.getContentType().split(";")[0];

                switch (contentType) {
                    case MediaType.APPLICATION_JSON:
                        authCodeToken = EverestUtilities.jsonMapper.readValue(tokenResponseBuilder.toString(), AuthCodeToken.class);
                        break;
                    case MediaType.APPLICATION_FORM_URLENCODED:
                        authCodeToken = new AuthCodeToken();
                        HashMap<String, String> params =
                                EverestUtilities.parseParameters(new URL(tokenURL + "?" + tokenResponseBuilder.toString()), "\\?");
                        if (params != null) {
                            params.forEach((key, value) -> {
                                switch (key) {
                                    case "access_token":
                                        authCodeToken.setAccessToken(value);
                                        break;
                                    case "token_type":
                                        authCodeToken.setTokenType(value);
                                        break;
                                    case "expires_in":
                                        authCodeToken.setExpiresIn(Integer.parseInt(value));
                                        break;
                                    case "refresh_token":
                                        authCodeToken.setRefreshToken(value);
                                        break;
                                    case "scope":
                                        authCodeToken.setScope(value);
                                        break;
                                }
                            });
                        }
                        break;
                    default:
                        throw new UnknownAccessTokenTypeException("Unknown access token type: " + contentType + "\nBody: " + tokenResponseBuilder.toString());
                }
            } else {
                Scanner scanner = new Scanner(connection.getErrorStream());
                while (scanner.hasNext())
                    tokenResponseBuilder.append(scanner.nextLine());
                throw new AccessTokenDeniedException(tokenResponseBuilder.toString());
            }
        }
    }
}

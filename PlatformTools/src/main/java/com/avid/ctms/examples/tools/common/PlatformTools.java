package com.avid.ctms.examples.tools.common;

import com.avid.ctms.examples.tools.common.data.Links;
import com.avid.ctms.examples.tools.common.data.token.Token;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Copyright 2013-2017 by Avid Technology, Inc.
 * User: nludwig
 * Date: 2016-06-13
 * Time: 07:36
 * Project: CTMS
 */

/**
 * A set of tooling methods.
 */
public class PlatformTools {

    private static final Logger LOG = Logger.getLogger(PlatformTools.class.getName());
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static ScheduledExecutorService scheduler;
    private static ScheduledFuture<?> sessionRefresher;

    /**
     * Retrieves the default connection timeout in ms.
     *
     * @return the default connection timeout in ms
     */
    public static int getDefaultConnectionTimeoutms() {
        return 60_000;
    }

    /**
     * Retrieves the default request timeout in ms.
     *
     * @return the default request timeout in ms
     */
    public static int getDefaultReadTimeoutms() {
        return 60_000;
    }

    private PlatformTools() {
    }

    /**
     * MCUX-identity-provider based authorization via credentials and cookies.
     * The used server-certificate validation is tolerant. As a side effect the global cookie handler is configured in a
     * way to refer a set of cookies, which are required for the communication with the platform.
     *
     * @param apiDomain         address to get "auth"
     * @param defaultOAuthToken default oauth2 api token
     * @param username          MCUX login
     * @param password          MCUX password
     * @return authorization token as string or null if authorization process failed.
     */
    public static String authorize(String apiDomain, String defaultOAuthToken, String username, String password)
            throws UnirestException, NoSuchAlgorithmException, KeyStoreException, KeyManagementException, IOException {
        initializeUniRest();
        String urlAuthorization = getIdentityProvider(apiDomain);
        return login(apiDomain, urlAuthorization, defaultOAuthToken, username, password);
    }

    private static void initializeUniRest() throws KeyStoreException, NoSuchAlgorithmException, KeyManagementException {
        final SSLContext sslContext =
                org.apache.http.ssl.SSLContexts
                        .custom()
                        .loadTrustMaterial(null, new TrustSelfSignedStrategy())
                        .build();

        HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());

        CloseableHttpClient httpClient = HttpClients.custom()
                .setSSLHostnameVerifier(new NoopHostnameVerifier())
                .setSSLContext(sslContext)
                .setConnectionTimeToLive(getDefaultConnectionTimeoutms(), TimeUnit.MILLISECONDS)
                .build();

        Unirest.setHttpClient(httpClient);
        Unirest.setDefaultHeader("Accept", "application/json");
    }

    private static String getIdentityProvider(String apiDomain) throws UnirestException, IOException {
        HttpResponse<JsonNode> jsonNodeHttpResponse = Unirest.get(String.format("https://%s/auth/", apiDomain))
                .asJson();
        JsonNode body = jsonNodeHttpResponse.getBody();
        String identityProviderHref = objectMapper.readValue(body.toString(), Links.class).getLinks().getIdentityProviders().get(0).getHref();

        String identityProviders = Unirest.get(identityProviderHref).asJson().getBody().toString();
        Links links = objectMapper.readValue(identityProviders, Links.class);
        return links.getEmbedded().getProviders().get(0).getEmbeddedProvider().getRopcLdapProvider().get(0).getHref();
    }

    private static String login(String apiDomain, String urlAuthorization, String defaultOAuthToken, String username, String password) throws UnirestException, IOException {
        final String loginContent = String.format("grant_type=password&username=%s&password=%s", username, password);
        final String authorizationDefaultToken = String.format("Basic %s", defaultOAuthToken);
        final HttpResponse<JsonNode> loginResponse =
                Unirest.post(urlAuthorization)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("Authorization", authorizationDefaultToken)
                        .body(loginContent)
                        .asJson();

        final int loginStatusCode = loginResponse.getStatus();
        if (HttpURLConnection.HTTP_SEE_OTHER == loginStatusCode || HttpURLConnection.HTTP_OK == loginStatusCode) {
            Token token = objectMapper.readValue(loginResponse.getBody().toString(), Token.class);
            String wellFormattedAvidAccessToken = String.format("Bearer %s", token.getAccessToken());
            Unirest.setDefaultHeader("Authorization", wellFormattedAvidAccessToken);
            keepAliveSession(apiDomain);
            return wellFormattedAvidAccessToken;
        }
        return null;
    }

    private static void keepAliveSession(String apiDomain) {
        scheduler = Executors.newScheduledThreadPool(1);
        final Runnable sessionRefresher = () -> {
            try {
                sendKeepAliveRequest(apiDomain);
            } catch (UnirestException e) {
                LOG.log(Level.SEVERE, "Session refresher error", e);
            }
        };
        final long refreshPeriodSeconds = 12;
        PlatformTools.sessionRefresher = scheduler.scheduleAtFixedRate(sessionRefresher, refreshPeriodSeconds, refreshPeriodSeconds, TimeUnit.SECONDS);
    }

    /**
     * Signals the platform, that our session is still in use.
     *
     * @param apiDomain address against to which we want send a keep alive signal
     * @throws UnirestException
     */
    private static void sendKeepAliveRequest(String apiDomain) throws UnirestException {
        Unirest.post(String.format("https://%s/auth/tokens/current/extension", apiDomain))
                .asJson();
    }

    /**
     * Performs a logout against the platform.
     *
     * @param apiDomain address against to which we want to logout
     * @throws IOException
     * @throws UnirestException
     */
    public static void logout(String apiDomain) throws UnirestException, IOException {
        callRemoveTokenRequest(apiDomain);
        removeSessionKeepAlive();
    }

    private static void callRemoveTokenRequest(String apiDomain) throws UnirestException, IOException {
        HttpResponse<JsonNode> jsonNodeHttpResponse = Unirest.get(String.format("https://%s/auth/", apiDomain))
                .asJson();
        Links links = objectMapper.readValue(jsonNodeHttpResponse.getBody().toString(), Links.class);
        String currentTokenRemovalUrl = links.getLinks().getToken().get(0).getHref();
        Unirest.delete(currentTokenRemovalUrl)
                .header("Accept", "application/json")
                .asJson();
    }

    private static void removeSessionKeepAlive() {
        if (null != scheduler) {
            sessionRefresher.cancel(true);
            scheduler.shutdown();
        }
    }

    /**
     * Performs a CTMS Registry lookup or defaults to the specified URI for the resource in question.
     *
     * @param apiDomain              address against to which we want send a keep alive signal
     * @param serviceTypes           list of service types, of which the resource in question should be looked up in the CTMS
     *                               Registry
     * @param registryServiceVersion registryServiceVersion version of the CTMS Registry to query
     * @param resourceName           resourceName resource to look up in the CTMS Registry, such as "search:simple-search"
     * @param orDefaultUriTemplate   URI template which will be returned in the promise, if the CTMS Registry is
     *                               unreachable or the resource in question cannot be found
     * @return a List&lt;String> under which the queried resource can be found. If the CTMS Registry is unreachable or
     * the resource in question cannot be found, the list of URI templates will contain the
     * orDefaultUriTemplate as single entry.
     */
    public static List<String> findInRegistry(String apiDomain, List<String> serviceTypes, String registryServiceVersion, String resourceName, String orDefaultUriTemplate) throws IOException {
        try {
            /// Check, whether the service registry is available:
            final URL serviceRootsResourceURL = new URL(String.format("https://%s/apis/avid.ctms.registry;version=%s/serviceroots", apiDomain, registryServiceVersion));
            final HttpURLConnection serviceRootsResourceConnection = (HttpURLConnection) serviceRootsResourceURL.openConnection();
            serviceRootsResourceConnection.setConnectTimeout(PlatformTools.getDefaultConnectionTimeoutms());
            serviceRootsResourceConnection.setReadTimeout(PlatformTools.getDefaultReadTimeoutms());
            serviceRootsResourceConnection.setRequestProperty("Accept", "application/hal+json");

            final int serviceRootsStatus = serviceRootsResourceConnection.getResponseCode();
            if (HttpURLConnection.HTTP_OK == serviceRootsStatus) {
                /// Doing the registry lookup and write the results to stdout:
                final String rawServiceRootsResult = PlatformTools.getContent(serviceRootsResourceConnection);
                final JSONObject serviceRootsResult = JSONObject.fromObject(rawServiceRootsResult);

                final JSONObject resources = serviceRootsResult.getJSONObject("resources");
                if (null != resources) {
                    if (resources.has(resourceName)) {
                        final Object resourcesObject = resources.get(resourceName);
                        if (resourcesObject instanceof JSONArray) {
                            final JSONArray asArray = (JSONArray) resourcesObject;
                            final List<String> foundUriTemplates = new ArrayList<>(asArray.size());
                            for (final Object singleLinkObject : asArray) {
                                final String href = ((JSONObject) singleLinkObject).getString("href");
                                if (serviceTypes.stream().anyMatch(href::contains)) {
                                    foundUriTemplates.add(href);
                                }
                            }

                            if (foundUriTemplates.isEmpty()) {
                                LOG.log(Level.INFO, "{0} not registered, defaulting to the specified URI template", resourceName);
                                return Collections.singletonList(orDefaultUriTemplate);
                            }

                            return foundUriTemplates;
                        } else {
                            final String href = ((JSONObject) resourcesObject).getString("href");

                            if (serviceTypes.stream().anyMatch(href::contains)) {
                                return Collections.singletonList(href);
                            } else {
                                LOG.log(Level.INFO, "{0} not registered, defaulting to the specified URI template", resourceName);
                                return Collections.singletonList(orDefaultUriTemplate);
                            }
                        }
                    } else {
                        LOG.log(Level.INFO, "{0} not registered, defaulting to the specified URI template", resourceName);
                        return Collections.singletonList(orDefaultUriTemplate);
                    }
                } else {
                    LOG.log(Level.INFO, "no registered resources found, defaulting to the specified default URI template");
                    return Collections.singletonList(orDefaultUriTemplate);
                }
            } else {
                LOG.log(Level.INFO, "CTMS Registry not reachable (request failed), defaulting to the specified URI template");
                return Collections.singletonList(orDefaultUriTemplate);
            }
        } catch (final Throwable throwable) {
            LOG.log(Level.SEVERE, "failure", throwable);
        }

        LOG.log(Level.INFO, "unknown error requesting the CTMS Registry, defaulting to the specified URI template");
        return Collections.singletonList(orDefaultUriTemplate);
    }

    /**
     * Reads the content of the buffer of the input stream or error stream read from the passed HttpURLConnection, puts
     * it into a String and returns that String.
     *
     * @param urlConnection the URLConnection to retrieve the content from.
     * @return the content of the buffer of the InputStream read from the passed URLConnection
     * @throws IOException
     */
    public static String getContent(HttpURLConnection urlConnection) throws IOException {
        final InputStream contentStream =
                5 != urlConnection.getResponseCode() / 100 && 4 != urlConnection.getResponseCode() / 100
                        ? urlConnection.getInputStream()
                        : urlConnection.getErrorStream();

        return getContent(contentStream, urlConnection.getContentLength());
    }

    private static String getContent(InputStream contentStream, int length) throws IOException {
        final StringBuilder content = new StringBuilder(length);

        try (final BufferedReader in = new BufferedReader(new InputStreamReader(contentStream, StandardCharsets.UTF_8))) {
            String line;
            while (null != (line = in.readLine())) {
                content.append(line);
            }
        }
        return content.toString();
    }


    /**
     * yyyy-mm-ddTHH:mm:ss.SSSZ
     */
    public static String nowFormatted() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }
}

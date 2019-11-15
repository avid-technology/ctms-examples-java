package com.avid.ctms.examples.tools.common;

import com.avid.ctms.examples.tools.common.data.LinkProperty;
import com.avid.ctms.examples.tools.common.data.Links;
import com.avid.ctms.examples.tools.common.data.token.Token;
import com.fasterxml.jackson.databind.ObjectMapper;
import kong.unirest.*;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.http.HttpHost;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;

import javax.net.ssl.SSLContext;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Copyright 2013-2019 by Avid Technology, Inc.
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
     * OAuth2-identity-provider based authorization via an HTTP Basic Auth String.
     * The used server-certificate validation is tolerant. As a side effect the global cookie handler is configured in a
     * way to refer a set of cookies, which are required for the communication with the platform.
     *
     * @param apiDomain             address to get "auth"
     * @param httpBasicAuthString   HTTP basic Auth String
     * @return authorization token as string or null if authorization process failed.
     */
    public static AuthorizationResponse authorize(String apiDomain, String httpBasicAuthString)
            throws Exception {
        initializeUnirest();

        return login(apiDomain, httpBasicAuthString);
    }

    private static void initializeUnirest() throws KeyStoreException, NoSuchAlgorithmException, KeyManagementException {
        final SSLContext sslContext
                = org.apache.http.ssl.SSLContexts
                .custom()
                .loadTrustMaterial(null, TrustSelfSignedStrategy.INSTANCE)
                .build();

        final String proxyHost = System.getProperty("https.proxyHost");
        final String proxyPort = System.getProperty("https.proxyPort");

        if (null != proxyHost) {
            LOG.log(Level.INFO, "using proxy: {0}, port: {1}", new Object [] {proxyHost, proxyPort});
        }

        Unirest.config()
                .reset()
                .verifySsl(false)
                .proxy((null != proxyHost) ? new kong.unirest.Proxy(proxyHost, Integer.parseInt(proxyPort)) : null);

        final RequestConfig requestConfig
                = RequestConfig.custom()
                .setCookieSpec(CookieSpecs.STANDARD)
                .setProxy((null != proxyHost) ? new HttpHost(proxyHost, Integer.parseInt(proxyPort)) : null)
                .build();

        final CloseableHttpAsyncClient httpAsyncClient
                = HttpAsyncClients
                .custom()
                .disableCookieManagement()
                .setDefaultRequestConfig(requestConfig)
                .setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                .setSSLContext(sslContext)
                .build();
        Unirest.config().asyncClient(httpAsyncClient);

        final CloseableHttpClient httpClient
                = HttpClients
                .custom()
                .disableCookieManagement()
                .disableRedirectHandling()
                .setDefaultRequestConfig(requestConfig)
                .setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                .setSSLContext(sslContext)
                .build();
        Unirest.config().httpClient(httpClient);
    }

    private static String getIdentityProvider(String authEndpoint) throws Exception {
        final HttpResponse<JsonNode> jsonNodeHttpResponse
                = Unirest
                .get(String.format("https://%s/auth", authEndpoint))
                .asJson();
        final JsonNode body = jsonNodeHttpResponse.getBody();
        final String identityProviderHref = objectMapper.readValue(body.toString(), Links.class).getLinks().getIdentityProviders().get(0).getHref();

        final String identityProviders
                = Unirest
                .get(identityProviderHref)
                .asJson()
                .getBody()
                .toString();
        final Links links = objectMapper.readValue(identityProviders, Links.class);

        final Optional<String> ropcDefaultIdentityProviderURL
                = links.getEmbedded().getProviders()
                .stream()
                .filter(Objects::nonNull)
                .map(it -> it.getEmbeddedProvider().getRopcDefaultProvider())
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .map(LinkProperty::getHref)
                .findFirst();

        if (!ropcDefaultIdentityProviderURL.isPresent()) {
            throw new Exception("No ropc-default-identity-provider found.");
        }

        return ropcDefaultIdentityProviderURL.get();
    }

    private static AuthorizationResponse login(String apiDomain, String httpBasicAuthString) throws Exception {
        Unirest.config().clearDefaultHeaders();
        Unirest.config().setDefaultHeader("Accept", "application/json");

        final String loginContent = "grant_type=client_credentials&scope=openid";
        final String urlAuthorization = getIdentityProvider(apiDomain);
        final String authorizationDefaultToken = String.format("Basic %s", httpBasicAuthString);
        final HttpResponse<JsonNode> loginResponse
                = Unirest
                .post(urlAuthorization)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", authorizationDefaultToken)
                .body(loginContent)
                .asJson();

        final int loginStatusCode = loginResponse.getStatus();
        if (HttpURLConnection.HTTP_OK == loginStatusCode || HttpURLConnection.HTTP_SEE_OTHER == loginStatusCode) {
            final Token token = objectMapper.readValue(loginResponse.getBody().toString(), Token.class);
            final String idToken = token.isOpenIdConnectEnabled() ? token.getIdToken() : token.getAccessToken();
            final String accessTokenHeaderFieldValue = String.format("Bearer %s", idToken);

            Unirest.config().setDefaultHeader("Authorization", accessTokenHeaderFieldValue);
            initializeSessionRefresher(apiDomain);
            return new AuthorizationResponse(accessTokenHeaderFieldValue, loginResponse);
        }
        return new AuthorizationResponse(null, loginResponse);
    }

    private static void initializeSessionRefresher(String apiDomain) {
        scheduler = Executors.newScheduledThreadPool(1);
        final Runnable sessionRefresher = () -> {
            try {
                sessionKeepAlive(apiDomain);
            } catch (final UnirestException | IOException e) {
                LOG.log(Level.SEVERE, "Session refresher error", e);
            }
        };
        final long refreshPeriodSeconds = 5;//120;
        PlatformTools.sessionRefresher = scheduler.scheduleAtFixedRate(sessionRefresher, refreshPeriodSeconds, refreshPeriodSeconds, TimeUnit.SECONDS);
    }

    /**
     * Signals the platform, that our session is still in use.
     *
     * @param apiDomain address against to which we want send a keep alive signal
     */
    private static void sessionKeepAlive(String apiDomain) throws IOException {
        final HttpResponse<JsonNode> jsonNodeHttpResponse
                = Unirest
                .get(String.format("https://%s/auth/", apiDomain))
                .asJson();
        final Links links = objectMapper.readValue(jsonNodeHttpResponse.getBody().toString(), Links.class);

        final String urlCurrentToken = links.getLinks().getToken().get(0).getHref();
        Unirest.get(urlCurrentToken)
                .asStringAsync()
                .thenAccept(it -> {
                    final JSONObject currentTokenResult = JSONObject.fromObject(it.getBody());
                    final String urlExtend = currentTokenResult
                            .getJSONObject("_links")
                            .getJSONArray("auth-token:extend")
                            .getJSONObject(0)
                            .get("href")
                            .toString();
                    final String accessToken = currentTokenResult.getString("accessToken");
                    Unirest.config().setDefaultHeader("Cookie", "avidAccessToken="+accessToken);
                    Unirest.post(urlExtend).asEmpty();
                });
    }

    /**
     * Performs a logout against the platform.
     *
     * @param apiDomain address against to which we want to logout
     * @throws IOException
     */
    public static void logout(String apiDomain) throws IOException {
        callRemoveTokenRequest(apiDomain);
        removeSessionKeepAlive();
    }

    private static void callRemoveTokenRequest(String apiDomain) throws IOException {
        final HttpResponse<JsonNode> jsonNodeHttpResponse = Unirest.get(String.format("https://%s/auth/", apiDomain))
                .asJson();
        final Links links = objectMapper.readValue(jsonNodeHttpResponse.getBody().toString(), Links.class);
        final String currentTokenRemovalUrl = links.getLinks().getToken().get(0).getHref();
        Unirest.delete(currentTokenRemovalUrl)
                .header("Accept", "application/json")
                .asEmpty();
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
    public static List<String> findInRegistry(String apiDomain, List<String> serviceTypes, String registryServiceVersion, String resourceName, String orDefaultUriTemplate) {
        try {
            /// Check, whether the service registry is available:
            final HttpResponse<String> response
                    = Unirest
                    .get(String.format("https://%s/apis/avid.ctms.registry;version=%s/serviceroots", apiDomain, registryServiceVersion))
                    .asString();

            final int serviceRootsStatus = response.getStatus();
            if (HttpURLConnection.HTTP_OK == serviceRootsStatus) {
                /// Doing the registry lookup and write the results to stdout:
                final String rawServiceRootsResult = response.getBody();
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

    public static String URLencode(String in) {
        try {
            return URLEncoder.encode(Objects.toString(in, ""), StandardCharsets.UTF_8.toString());
        } catch (final Exception exc) {
            return "";
        }
    }

    public static String URLdecode(String in) {
        try {
            return URLDecoder.decode(Objects.toString(in, ""), StandardCharsets.UTF_8.toString());
        } catch (final Exception exc) {
            return "";
        }
    }

    /**
     * Reads the content of the buffer of the input stream or error stream read from the passed HttpURLConnection, puts
     * it into a String and returns that String.
     *
     * @param urlConnection the HttpURLConnection to retrieve the content from.
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

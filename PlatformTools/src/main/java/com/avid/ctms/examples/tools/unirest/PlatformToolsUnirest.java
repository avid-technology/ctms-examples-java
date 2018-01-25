package com.avid.ctms.examples.tools.unirest;

import com.mashape.unirest.http.*;
import com.mashape.unirest.http.exceptions.*;
import org.apache.http.HttpHost;
import org.apache.http.conn.ssl.*;
import org.apache.http.impl.client.*;
import org.apache.http.impl.nio.client.*;
import org.json.*;


import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

/**
 * Copyright 2013-2017 by Avid Technology, Inc.
 * User: nludwig
 * Date: 2017-1-31
 * Time: 10:29
 * Project: CTMS
 */

/**
 * A set of tooling methods, partially implemented with Unirest.
 */
public class PlatformToolsUnirest {
    private static final Logger LOG = Logger.getLogger(PlatformToolsUnirest.class.getName());

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

    private static ScheduledExecutorService scheduler;
    private static ScheduledFuture<?> sessionRefresher;


//    private static CloseableHttpClient createSSLClient() throws KeyManagementException, NoSuchAlgorithmException, KeyStoreException {
//        final SSLContext sslcontext = SSLContexts.custom()
//                .loadTrustMaterial(null, new TrustSelfSignedStrategy())
//                .build();
//
//        return HttpClients.custom()
//                .setSSLSocketFactory(new SSLConnectionSocketFactory(sslcontext, SSLConnectionSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER))
//                //.setProxy(new HttpHost("127.0.0.1", 8888))
//                .build();
//    }

    private static CloseableHttpClient createSSLClient() throws KeyStoreException, NoSuchAlgorithmException, KeyManagementException {
        final SSLContext sslContext =
                org.apache.http.ssl.SSLContexts
                        .custom()
                        .loadTrustMaterial(null, new TrustSelfSignedStrategy())
                        .build();

        return HttpClients.custom()
                .setSSLHostnameVerifier(new NoopHostnameVerifier())
                .setSSLContext(sslContext)
                        //.setProxy(new HttpHost("127.0.0.1", 8888))
                .build();
    }


    private PlatformToolsUnirest() {
    }

    /**
     * MCUX-identity-provider based authorization via credentials and cookies.
     * The used server-certificate validation is tolerant. As a side effect the global cookie handler is configured in a
     * way to refer a set of cookies, which are required for the communication with the platform.
     *
     * @param apiDomain address to get "auth"
     * @param username  MCUX login
     * @param password  MCUX password
     * @return true if authorization was successful, otherwise false
     */
    public static boolean authorize(String apiDomain, String username, String password) throws Exception {
        Unirest.setTimeouts(getDefaultConnectionTimeoutms(), getDefaultReadTimeoutms());
        Unirest.setHttpClient(createSSLClient());

        final HttpResponse<JsonNode> authResponse =
                Unirest.get(String.format("https://%s/auth", apiDomain))
                        .header("Accept", "application/json")
                        .asJson();

        final int authStatusCode = authResponse.getStatus();
        final JsonNode authResult = authResponse.getBody();
        final String urlIdentityProviders = authResult.getObject().getJSONObject("_links").getJSONArray("auth:identity-providers").getJSONObject(0).getString("href");

        final HttpResponse<JsonNode> identityProvidersResponse =
                Unirest.get(urlIdentityProviders)
                        .header("Accept", "application/json")
                        .asJson();

        // Select MC|UX identity provider and retrieve login URL:
        String urlAuthorization = null;
        final JsonNode identityProvidersResult = identityProvidersResponse.getBody();

        final JSONArray identityProviders = identityProvidersResult.getObject().getJSONObject("_embedded").getJSONArray("auth:identity-provider");
        for (int i = 0; i < identityProviders.length(); ++i) {
            final JSONObject identityProvider = identityProviders.getJSONObject(i);
            if (Objects.equals(identityProvider.get("kind"), "mcux")) {
                final JSONArray logins = identityProvider.getJSONObject("_links").getJSONArray("auth-mcux:login");
                if (0 < logins.length()) {
                    urlAuthorization = logins.getJSONObject(0).getString("href");
                }
                break;
            }
        }

        // Do the login:
        if (null != urlAuthorization) {
            final String loginContent = String.format("{ \"username\" : \"%s\", \"password\" : \"%s\"}", username, password);

            final HttpResponse<JsonNode> loginResponse =
                    Unirest.post(urlAuthorization)
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json")
                            .body(loginContent)
                            .asJson();

            // Check success:
            final int loginStatusCode = loginResponse.getStatus();
            if (HttpURLConnection.HTTP_SEE_OTHER == loginStatusCode || HttpURLConnection.HTTP_OK == loginStatusCode) {
                scheduler = Executors.newScheduledThreadPool(1);

                final Runnable sessionRefresherCode = () -> {
                    try {
                        sessionKeepAlive(apiDomain);
                    } catch (IOException | UnirestException e) {
                        e.printStackTrace();
                    }
                };
                final long refreshPeriodSeconds = 120;
                sessionRefresher = scheduler.scheduleAtFixedRate(sessionRefresherCode, refreshPeriodSeconds, refreshPeriodSeconds, TimeUnit.SECONDS);
                return true;
            }

        }
        /// END - Authorization procedure

        return false;
    }

    /**
     * Performs a CTMS Registry lookup or defaults to the specified URI for the resource in question.
     *
     * @param apiDomain address against to which we want send a keep alive signal
     * @param serviceTypes list of service types, of which the resource in question should be looked up in the CTMS
     *          Registry
     * @param registryServiceVersion registryServiceVersion version of the CTMS Registry to query
     * @param resourceName resourceName resource to look up in the CTMS Registry, such as "search:simple-search"
     * @param orDefaultUriTemplate URI template which will be returned in the promise, if the CTMS Registry is
     *          unreachable or the resource in question cannot be found
     * @return  a List&lt;String> under which the queried resource can be found. If the CTMS Registry is unreachable or
     *          the resource in question cannot be found, the list of URI templates will contain the
     *          orDefaultUriTemplate as single entry.
     */
    public static List<String> findInRegistry(String apiDomain, List<String> serviceTypes, String registryServiceVersion, String resourceName, String orDefaultUriTemplate) throws UnirestException, IOException {
        final HttpResponse<JsonNode> serviceRootsResponse =
                Unirest.get(String.format("https://%s/apis/avid.ctms.registry;version=%s/serviceroots", apiDomain, registryServiceVersion))
                        .header("Accept", "application/json")
                        .asJson();

        final int serviceRootsStatusCode = serviceRootsResponse.getStatus();
        if (200 == serviceRootsStatusCode || 303 == serviceRootsStatusCode) {
            final JsonNode serviceRootsNode = serviceRootsResponse.getBody();

            final JSONObject resources =
                    serviceRootsNode.getObject().has("resources")
                        ? serviceRootsNode.getObject().getJSONObject("resources")
                        : null;
            if (null != resources) {
                if (resources.has(resourceName)) {
                    final Object resourcesObject = resources.get(resourceName);
                    if (resourcesObject instanceof JSONArray) {
                        final JSONArray asArray = (JSONArray) resourcesObject;
                        final List<String> result = new ArrayList<>(asArray.length());
                        for (final Object singleLinkObject : asArray) {
                            final String href = ((JSONObject) singleLinkObject).getString("href");
                            if (serviceTypes.stream().anyMatch(href::contains)) {
                                result.add(href);
                            }
                        }

                        if (result.isEmpty()) {
                            LOG.log(Level.INFO, "{0} not registered, defaulting to the specified URI template", resourceName);
                            return Collections.singletonList(orDefaultUriTemplate);
                        }

                        return result;
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
    }

    /**
     * Performs a logout against the platform.
     *
     * @param apiDomain address against to which we want to logout
     * @throws IOException
     */
    public static void logout(String apiDomain) throws IOException, UnirestException, JSONException {
        /// Logout from platform:
        final HttpResponse<JsonNode> authResponse =
                Unirest.get(String.format("https://%s/auth", apiDomain))
                        .header("Accept", "application/json")
                        .asJson();


        final int authStatusCode = authResponse.getStatus();
        final JsonNode authResult = authResponse.getBody();
        final JSONArray authTokens = authResult.getObject().getJSONObject("_links").getJSONArray("auth:token");

        String urlCurrentToken = null;
        for (int i = 0; i < authTokens.length(); ++i) {
            if (Objects.equals(authTokens.getJSONObject(i).getString("name"), "current")) {
                urlCurrentToken = authTokens.getJSONObject(i).getString("href");
                break;
            }
        }

        if (null != urlCurrentToken) {
            final HttpResponse<JsonNode> currentTokenResponse =
                    Unirest.get(urlCurrentToken)
                            .header("Accept", "application/json")
                            .asJson();
            final int currentTokenStatusCode = currentTokenResponse.getStatus();

            final String urlCurrentTokenRemoval = currentTokenResponse.getBody().getObject().getJSONObject("_links").getJSONArray("auth-token:removal").getJSONObject(0).getString("href");

            final HttpResponse<JsonNode> currentTokenRemovalResponse =
                    Unirest.delete(urlCurrentTokenRemoval)
                            .asJson();

            // Should result in 204:
            final int currentTokenRemovalStatusCode = currentTokenRemovalResponse.getStatus();
        }


        /// Unregister the keep alive task:
        if (null != scheduler) {
            sessionRefresher.cancel(true);
            scheduler.shutdown();
        }
    }

    /**
     * Signals the platform, that our session is still in use.
     *
     * @param apiDomain address against to which we want send a keep alive signal
     * @throws IOException
     */
    public static void sessionKeepAlive(String apiDomain) throws IOException, UnirestException {
        // TODO: this is a workaround, see {CORE-7359}. In future the access token prolongation API should be used.
        final HttpResponse<JsonNode> pingResponse =
                Unirest.get(String.format("https://%s/api/middleware/service/ping", apiDomain))
                        .header("Accept", "application/json")
                        .asJson();

        final int ignoredPingStatusCode = pingResponse.getStatus();
    }
}

package com.avid.ctms.examples.tools.asyncunirest;
/**
 * Copyright 2017-2021 by Avid Technology, Inc.
 * User: nludwig
 * Date: 2017-01-09
 * Time: 07:36
 * Project: CTMS
 */

import com.avid.ctms.examples.tools.common.*;

import kong.unirest.*;
import kong.unirest.apache.ApacheAsyncClient;
import kong.unirest.json.*;
import org.apache.http.HttpHost;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.conn.ssl.*;
import org.apache.http.impl.nio.client.*;

import javax.net.ssl.SSLContext;
import javax.ws.rs.core.HttpHeaders;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.*;

/**
 * A set of asynchronous tooling methods with Unirest.
 */
public class PlatformToolsAsyncUnirest {
    private static final Logger LOG = Logger.getLogger(PlatformToolsAsyncUnirest.class.getName());

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

    private static AsyncClient createSSLClient() throws KeyStoreException, NoSuchAlgorithmException, KeyManagementException {
        final SSLContext sslContext =
                org.apache.http.ssl.SSLContexts
                        .custom()
                        .loadTrustMaterial(null, TrustSelfSignedStrategy.INSTANCE)
                        .build();

        final String proxyHost = System.getProperty("https.proxyHost");
        final String proxyPort = System.getProperty("https.proxyPort");

        if (null != proxyHost) {
            LOG.log(Level.INFO, "using proxy: {0}, port: {1}", new Object[]{proxyHost, proxyPort});
        }

        final Config requestConfig
                = new Config()
                .cookieSpec(CookieSpecs.STANDARD)
                .proxy((null != proxyHost) ? new kong.unirest.Proxy(proxyHost, Integer.parseInt(proxyPort)) : null);

        final CloseableHttpAsyncClient httpAsyncClient
                = HttpAsyncClients
                .custom()
                .disableCookieManagement()
                .setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                .setSSLContext(sslContext)
                .build();
        return ApacheAsyncClient.builder(httpAsyncClient).apply(requestConfig);

//        return HttpAsyncClients.custom()
//                .setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE)
//                .setSSLContext(sslContext)
//                .setProxy((null != proxyHost) ? new HttpHost(proxyHost, Integer.parseInt(proxyPort)) : null)
//                .build();
    }

    private PlatformToolsAsyncUnirest() {
    }

    /**
     * Prepares the Unirest environment.
     */
    public static void prepare() {
        //Unirest.setTimeouts(getDefaultConnectionTimeoutms(), getDefaultReadTimeoutms());
        try {
            Unirest.config().verifySsl(false);
            Unirest.config().asyncClient(createSSLClient());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * OAuth2-identity-provider based authorization via an HTTP Basic Auth String.
     *
     * @param apiDomain address to get "auth"
     * @param httpBasicAuthString   HTTP basic Auth String
     * @param done      a "continuation" callback, which is called, if the authorization procedure ended successfully. The
     *                  callback should execute the code, which continues working with the session resulting from the
     *                  authorization.
     * @param failed    a "continuation" callback, which is called, if the authorization procedure failed.
     */
    public static void authorize(String apiDomain, String httpBasicAuthString, Consumer<Object> done, Terminator<String, Throwable> failed) {
        prepare();

        Unirest.get(String.format("https://%s/auth", apiDomain))
                .header( HttpHeaders.ACCEPT, "application/json")
                .asJsonAsync(new Callback<JsonNode>() {
                    @Override
                    public void completed(HttpResponse<JsonNode> authResponse) {
                        final JSONObject authResult = authResponse.getBody().getObject();
                        try {
                            final String urlIdentityProviders = authResult.getJSONObject("_links").getJSONArray("auth:identity-providers").getJSONObject(0).getString("href");
                            Unirest.get(urlIdentityProviders)
                                    .header( HttpHeaders.ACCEPT, "application/json")
                                    .asJsonAsync(new Callback<JsonNode>() {
                                        @Override
                                        public void completed(HttpResponse<JsonNode> response) {
                                            if (HttpURLConnection.HTTP_OK == response.getStatus() || HttpURLConnection.HTTP_SEE_OTHER == response.getStatus()) {
                                                // Select ropc-default identity provider and retrieve login URL:
                                                String urlAuthorization = null;
                                                final JSONArray identityProviders = response.getBody().getObject().getJSONObject("_embedded").getJSONArray("auth:identity-provider");
                                                for (int i = 0; i < identityProviders.length(); ++i) {
                                                    final JSONObject identityProvider = identityProviders.getJSONObject(i);

                                                    if (identityProvider.getJSONObject("_links").has("auth:ropc-default")) {
                                                        final JSONArray ropcIdentityProvider = identityProvider.getJSONObject("_links").getJSONArray("auth:ropc-default");
                                                        if (0 < ropcIdentityProvider.length()) {
                                                            urlAuthorization = ropcIdentityProvider.getJSONObject(0).getString("href");
                                                        }
                                                        break;
                                                    }
                                                }
                                                if (null != urlAuthorization) {
                                                    final String loginContent = "grant_type=client_credentials&scope=openid";
                                                    final String authorizationDefaultToken = String.format("Basic %s", httpBasicAuthString);
                                                    Unirest.post(urlAuthorization)
                                                            .header(HttpHeaders.ACCEPT, "application/json")
                                                            .header(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded")
                                                            .header(HttpHeaders.AUTHORIZATION, authorizationDefaultToken)
                                                            .body(loginContent)
                                                            .asJsonAsync(new Callback<JsonNode>() {
                                                                @Override
                                                                public void completed(HttpResponse<JsonNode> response) {
                                                                    if (HttpURLConnection.HTTP_OK == response.getStatus() || HttpURLConnection.HTTP_SEE_OTHER == response.getStatus()) {
                                                                        final String id_token = response.getBody().getObject().getString("id_token");
                                                                        final String accessTokenHeaderFieldValue = String.format("Bearer %s", id_token);
                                                                        Unirest.config().setDefaultHeader(HttpHeaders.AUTHORIZATION, accessTokenHeaderFieldValue);

                                                                        scheduler = Executors.newScheduledThreadPool(1);
                                                                        final Runnable sessionRefresherCode = () -> {
                                                                            try {
                                                                                sessionKeepAlive(apiDomain);
                                                                            } catch (final Exception exception ) {
                                                                                LOG.log(Level.SEVERE, "failure", exception);
                                                                            }
                                                                        };

                                                                        final long refreshPeriodSeconds = 120;
                                                                        sessionRefresher = scheduler.scheduleAtFixedRate(sessionRefresherCode, refreshPeriodSeconds, refreshPeriodSeconds, TimeUnit.SECONDS);
                                                                        done.accept(null);
                                                                    } else {
                                                                        failed.accept(response.getStatusText(), null);
                                                                        unregister();
                                                                    }
                                                                }

                                                                @Override
                                                                public void cancelled() {
                                                                    LOG.log(Level.INFO, "The authorization request has been cancelled");
                                                                    failed.accept("The authorization request has been cancelled", null);
                                                                    unregister();
                                                                }

                                                                @Override
                                                                public void failed(UnirestException e) {
                                                                    LOG.log(Level.SEVERE, "The authorization request has failed", e);
                                                                    failed.accept("The authorization request has failed", e);
                                                                    unregister();
                                                                }
                                                            });
                                                } else {
                                                    failed.accept("Authorization failed", null);
                                                    unregister();
                                                }
                                            } else {
                                                failed.accept(response.getStatusText(), null);
                                                unregister();
                                            }
                                        }

                                        @Override
                                        public void cancelled() {
                                            LOG.log(Level.INFO, "The request has been cancelled");
                                            failed.accept("The request has been cancelled", null);
                                            unregister();
                                        }

                                        @Override
                                        public void failed(UnirestException e) {
                                            LOG.log(Level.SEVERE, "The request has failed", e);
                                            failed.accept("The request has failed", e);
                                            unregister();
                                        }
                                    });
                        } catch (Exception e) {
                            failed.accept("Requesting auth has failed", e);
                            unregister();
                        }
                    }

                    @Override
                    public void cancelled() {
                        LOG.log(Level.INFO, "The request has been cancelled");
                        failed.accept("The request has been cancelled", null);
                        unregister();
                    }

                    @Override
                    public void failed(UnirestException e) {
                        LOG.log(Level.SEVERE, "The request has failed", e);
                        failed.accept("The request has failed", e);
                        unregister();
                    }
                });
    }

    /**
     * Queries the CTMS Registry via the passed apiDomain and registryServiceVersion. The results are passed to the
     * additionally passed "continuation" callback "done", which is executed after the query has completed.
     *
     * @param apiDomain address against to which we want send a keep alive signal
     * @param serviceTypes list of service types, of which the resource in question should be looked up in the CTMS
     *          Registry
     * @param registryServiceVersion registryServiceVersion version of the CTMS Registry to query
     * @param resourceName resourceName resource to look up in the CTMS Registry, such as "search:simple-search"
     * @param orDefaultUriTemplate URI template which will be returned in the promise, if the CTMS Registry is
     *          unreachable or the resource in question cannot be found
     * @param done a "continuation" callback, which is called, when the CTMS Registry query has ended. The callback
     *          gets a list of URI templates, under which the queried resource can be found. If the CTMS Registry is
     *          unreachable or the resource in question cannot be found, the list of URI templates will contain the
     *          orDefaultUriTemplate as single entry.
     */
    public static void findInRegistry(String apiDomain, List<String> serviceTypes, String registryServiceVersion, String resourceName, String orDefaultUriTemplate, Consumer<List<String>> done) {
        Unirest.get(String.format("https://%s/apis/avid.ctms.registry;version=%s/serviceroots", apiDomain, registryServiceVersion))
                .header( HttpHeaders.ACCEPT, "application/json")
                .asJsonAsync(new Callback<JsonNode>() {
                    @Override
                    public void completed(HttpResponse<JsonNode> serviceRootsResponse) {
                        if (HttpURLConnection.HTTP_OK == serviceRootsResponse.getStatus() || HttpURLConnection.HTTP_SEE_OTHER == serviceRootsResponse.getStatus()) {
                            final JSONObject serviceRootsResult = serviceRootsResponse.getBody().getObject();
                            try {
                                final JSONObject resources = serviceRootsResult.getJSONObject("resources");
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
                                                done.accept(Collections.singletonList(orDefaultUriTemplate));
                                            } else {
                                                done.accept(Collections.unmodifiableList(result));
                                            }
                                        } else {
                                            final String href = ((JSONObject) resourcesObject).getString("href");

                                            if (serviceTypes.stream().anyMatch(href::contains)) {
                                                done.accept(Collections.singletonList(href));
                                            } else {
                                                LOG.log(Level.INFO, "{0} not registered, defaulting to the specified URI template", resourceName);
                                                done.accept(Collections.singletonList(orDefaultUriTemplate));
                                            }
                                        }
                                    } else {
                                        LOG.log(Level.INFO, "{0} not registered, defaulting to the specified URI template", resourceName);
                                        done.accept(Collections.singletonList(orDefaultUriTemplate));
                                    }
                                } else {
                                    LOG.log(Level.INFO, "no registered resources found, defaulting to the specified default URI template");
                                    done.accept(Collections.singletonList(orDefaultUriTemplate));
                                }
                            } catch (final Exception e) {
                                LOG.log(Level.INFO, "unknown error requesting the CTMS Registry, defaulting to the specified URI template");
                                done.accept(Collections.singletonList(orDefaultUriTemplate));
                            }
                        } else {
                            LOG.log(Level.INFO, "CTMS Registry not reachable, defaulting to the specified URI template");
                            done.accept(Collections.singletonList(orDefaultUriTemplate));
                        }
                    }

                    @Override
                    public void cancelled() {
                        LOG.log(Level.INFO, "CTMS Registry not reachable (request cancelled), defaulting to the specified URI template");
                        done.accept(Collections.singletonList(orDefaultUriTemplate));
                    }

                    @Override
                    public void failed(UnirestException e) {
                        LOG.log(Level.INFO, "CTMS Registry not reachable (request failed), defaulting to the specified URI template");
                        done.accept(Collections.singletonList(orDefaultUriTemplate));
                    }
                });
    }

    /**
     * Pages through the HAL resources available via the passed resultPageURL. The results are collected and passed to
     * the additionally passed "continuation" callback "done", which is executed after all pages have been collected.
     * If paging failed, the "continuation" callback "failed" is called.
     * <p>
     * If the HAL resource available from resultPageURL has the property "_embedded", its content will be collected
     * And if this HAL resource has the property "pageResult._links.next", its href will be used to fetch and collect
     * the next page and call this method recursively.
     *
     * @param resultPageURL URL to a HAL resource, which supports paging
     * @param done          a "continuation" callback, which is called, if the paging procedure ended successfully, the pages
     *                      passed to this block.
     * @param failed        a "continuation" callback, which is called, if the paging procedure failed.
     */
    public static void pageThroughResultsAsync(String resultPageURL, Consumer<List<JSONObject>> done, Terminator<String, Throwable> failed) {
        final List<JSONObject> pages = new ArrayList<>();

        Unirest.get(resultPageURL)
                .header( HttpHeaders.ACCEPT, "application/json")
                .asJsonAsync(new Callback<JsonNode>() {
                    @Override
                    public void completed(HttpResponse<JsonNode> response) {
                        try {
                            if (HttpURLConnection.HTTP_OK == response.getStatus() || HttpURLConnection.HTTP_SEE_OTHER == response.getStatus()) {
                                if (response.getBody().getObject().has("_embedded")) { // Do we have (more) results?
                                    final JSONObject embeddedResults = response.getBody().getObject().getJSONObject("_embedded");
                                    pages.add(embeddedResults);

                                    // If we have more results, follow the next link and get the next page:
                                    final JSONObject links = response.getBody().getObject().getJSONObject("_links");
                                    final JSONObject nextPageLinkObject = links.optJSONObject("next");
                                    if (null != nextPageLinkObject) {
                                        pageThroughResultsAsync(
                                                nextPageLinkObject.getString("href")
                                                , objects -> {
                                                    pages.addAll(objects);
                                                    done.accept(pages);
                                                }
                                                , failed);
                                    } else {
                                        done.accept(pages);
                                    }
                                } else {
                                    done.accept(pages);
                                }
                            } else {
                                failed.accept(response.getStatusText(), null);
                            }
                        } catch (final Exception e) {
                            failed.terminate(String.format("Paging failed for <%s>", resultPageURL), e);
                        }
                    }

                    @Override
                    public void cancelled() {
                        failed.terminate(String.format("Paging cancelled for <%s>", resultPageURL), null);
                    }

                    @Override
                    public void failed(UnirestException e) {
                        failed.terminate(String.format("Paging failed for <%s>", resultPageURL), e);
                    }
                });
    }

    /**
     * Signals the platform, that our session is still in use.
     *
     * @param apiDomain address against to which we want send a keep alive signal
     * @throws IOException
     */
    private static void sessionKeepAlive(String apiDomain) {
        final HttpResponse<String> jsonNodeHttpResponse
                = Unirest
                .get(String.format("https://%s/auth/", apiDomain))
                .asString();
        final JSONObject authResponse = new JSONObject(jsonNodeHttpResponse.getBody());

        final String urlCurrentToken
                = authResponse
                .getJSONObject("_links")
                .getJSONArray("auth:token")
                .getJSONObject(0)
                .getString("href");

        Unirest.get(urlCurrentToken)
                .asStringAsync()
                .thenAccept(it -> {
                    final JSONObject currentTokenResult = new JSONObject(it.getBody());
                    final String urlExtend = currentTokenResult
                            .getJSONObject("_links")
                            .getJSONArray("auth-token:extend")
                            .getJSONObject(0)
                            .get("href")
                            .toString();
                    final String accessToken = currentTokenResult.getString("accessToken");
                    Unirest.config().setDefaultHeader(HttpHeaders.COOKIE, "avidAccessToken="+accessToken);
                    Unirest.post(urlExtend).asEmpty();
                });
    }

    /**
     * Performs a logout against the platform.
     *
     * @param apiDomain address against to which we want to logout
     * @param done      a "continuation" callback, which is called, if the logout procedure ended successfully. The
     *                  callback should execute the code, which continues working with the session resulting from the
     *                  login.
     * @param failed    a "continuation" callback, which is called, if the login procedure failed.
     */
    public static void logout(String apiDomain, Consumer<Object> done, Terminator<String, Throwable> failed) {
        Unirest.get(String.format("https://%s/auth", apiDomain))
                .header( HttpHeaders.ACCEPT, "application/json")
                .asJsonAsync(new Callback<JsonNode>() {
                    @Override
                    public void completed(HttpResponse<JsonNode> authResponse) {
                        if (HttpURLConnection.HTTP_OK == authResponse.getStatus() || HttpURLConnection.HTTP_SEE_OTHER == authResponse.getStatus()) {
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
                                Unirest.get(urlCurrentToken)
                                        .header( HttpHeaders.ACCEPT, "application/json")
                                        .asJsonAsync(new Callback<JsonNode>() {
                                            @Override
                                            public void completed(HttpResponse<JsonNode> currentTokenResponse) {
                                                if (HttpURLConnection.HTTP_OK == authResponse.getStatus() || HttpURLConnection.HTTP_SEE_OTHER == authResponse.getStatus()) {

                                                    final String urlCurrentTokenRemoval = currentTokenResponse.getBody().getObject().getJSONObject("_links").getJSONArray("auth-token:removal").getJSONObject(0).getString("href");

                                                    Unirest.delete(urlCurrentTokenRemoval)
                                                            .asEmptyAsync(new Callback<Empty>() {
                                                                @Override
                                                                public void completed(HttpResponse<Empty> deleteTokenResponse) {
                                                                    // Should result in 204:
                                                                    final int responseCode = deleteTokenResponse.getStatus();
                                                                    done.accept(null);
                                                                    unregister();
                                                                }

                                                                @Override
                                                                public void cancelled() {
                                                                    failed.accept("Deleting current token cancelled", null);
                                                                    unregister();
                                                                }

                                                                @Override
                                                                public void failed(UnirestException e) {
                                                                    failed.accept("Deleting current token failed", e);
                                                                    unregister();
                                                                }
                                                            });
                                                } else {
                                                    failed.accept("Getting current token failed", null);
                                                    unregister();
                                                }
                                            }

                                            @Override
                                            public void cancelled() {
                                                failed.accept("Getting current token cancelled", null);
                                                unregister();
                                            }

                                            @Override
                                            public void failed(UnirestException e) {
                                                failed.accept("Getting current token failed", e);
                                                unregister();
                                            }
                                        });
                            } else {
                                failed.accept("No token url got", null);
                                unregister();
                            }
                        } else {
                            failed.accept(authResponse.getStatusText(), null);
                            unregister();
                        }
                    }

                    @Override
                    public void cancelled() {
                        failed.accept("Getting 'auth' cancelled", null);
                        unregister();
                    }

                    @Override
                    public void failed(UnirestException e) {
                        failed.accept("Getting 'auth' failed", e);
                        unregister();
                    }
                });
    }

    public static void unregister() {
        /// Unregister the keep alive task:
        if (null != scheduler) {
            scheduler.shutdown();
            sessionRefresher.cancel(true);
        }
        try {
            Unirest.shutDown();
        } catch (final Exception exception ) {
            LOG.log(Level.SEVERE, "failure", exception);
        }
    }
}

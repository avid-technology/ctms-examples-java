package com.avid.ctms.examples.tools.asyncunirest;
/**
 * Copyright 2017-2021 by Avid Technology, Inc.
 * User: nludwig
 * Date: 2017-01-09
 * Time: 07:36
 * Project: CTMS
 */

import kong.unirest.*;
import kong.unirest.apache.ApacheAsyncClient;
import kong.unirest.json.*;
import kong.unirest.HttpResponse;
import org.apache.http.*;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.conn.ssl.*;
import org.apache.http.impl.nio.client.*;
import org.apache.http.ssl.SSLContexts;

import javax.net.ssl.*;
import javax.ws.rs.core.HttpHeaders;
import java.net.HttpURLConnection;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

/**
 * A set of reactive tooling methods with Unirest.
 */
public class PlatformToolsReactiveUnirest {
    private static final Logger LOG = Logger.getLogger(PlatformToolsReactiveUnirest.class.getName());

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

    private PlatformToolsReactiveUnirest() {
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
     * Promises delivery of a HAL resource representing the authorization endpoint.
     *
     * @param apiDomain address to get the authorization endpoint
     * @return  promise, which promises delivery of a HAL resource representing the authorization endpoint encapsulated
     *          in an HttpResponse&lt;JsonNode>
     */
    public static CompletionStage<HttpResponse<JsonNode>> getAuthEndpoint(String apiDomain) {
        final CompletableFuture<HttpResponse<JsonNode>> promise = new CompletableFuture<>();

        Unirest.get(String.format("https://%s/auth", apiDomain))
                .header( HttpHeaders.ACCEPT, "application/json")
                .asJsonAsync(new Callback<JsonNode>() {
                    @Override
                    public void completed(HttpResponse<JsonNode> authResponse) {
                        if (HttpURLConnection.HTTP_OK == authResponse.getStatus() || HttpURLConnection.HTTP_SEE_OTHER == authResponse.getStatus()) {
                            promise.complete(authResponse);
                        } else {
                            promise.completeExceptionally(new Exception(authResponse.getStatusText()));
                        }
                    }

                    @Override
                    public void cancelled() {
                        LOG.log(Level.INFO, "The request has been cancelled");
                        promise.cancel(true);
                    }

                    @Override
                    public void failed(UnirestException e) {
                        LOG.log(Level.SEVERE, "The request has failed", e);
                        promise.completeExceptionally(new Exception("The request has failed", e));
                    }
                });

        return promise;
    }

    /**
     * Promises delivery of a HAL resource representing the identity providers with the passed authorization endpoint
     * HAL resource.
     *
     * @param lastResponse a response object encapsulating the authorization endpoint HAL resource
     * @return  promise, which promises delivery of a HAL resource representing the identity providers encapsulated
     *          in an HttpResponse&lt;JsonNode>
     */
    public static CompletionStage<HttpResponse<JsonNode>> getIdentityProviders(HttpResponse<JsonNode> lastResponse) {
        final CompletableFuture<HttpResponse<JsonNode>> identityProviderPromise = new CompletableFuture<>();

        final JSONObject authResult = lastResponse.getBody().getObject();
        try {
            final String urlIdentityProviders = authResult.getJSONObject("_links").getJSONArray("auth:identity-providers").getJSONObject(0).getString("href");
            Unirest.get(urlIdentityProviders)
                    .header( HttpHeaders.ACCEPT, "application/json")
                    .asJsonAsync(new Callback<JsonNode>() {
                        @Override
                        public void completed(HttpResponse<JsonNode> response) {
                            if (HttpURLConnection.HTTP_OK == response.getStatus() || HttpURLConnection.HTTP_SEE_OTHER == response.getStatus()) {
                                identityProviderPromise.complete(response);
                            } else {
                                identityProviderPromise.completeExceptionally(new Exception(response.getStatusText()));
                            }
                        }

                        @Override
                        public void cancelled() {
                            LOG.log(Level.INFO, "The request has been cancelled");
                            identityProviderPromise.cancel(true);
                        }

                        @Override
                        public void failed(UnirestException e) {
                            LOG.log(Level.SEVERE, "The request has failed", e);
                            identityProviderPromise.completeExceptionally(new Exception("The request has failed", e));
                        }
                    });
        } catch (final Exception e) {
            identityProviderPromise.completeExceptionally(new Exception("Requesting auth has failed", e));
        }

        return identityProviderPromise;
    }

    /**
     * Promises OAuth2-identity-provider based authorization via an HTTP Basic Auth String with the passed identity providers HAL resource.
     *
     * @param apiDomain address to get "auth"
     * @param httpBasicAuthString HTTP Basic Auth String
     * @return  promise, which promises authorization
     */
    public static CompletionStage<Object> authorize(HttpResponse<JsonNode> lastResponse, String apiDomain, String httpBasicAuthString) {
        final CompletableFuture<Object> authorizationPromise = new CompletableFuture<>();

        // Select ropc-default identity provider and retrieve login URL:
        String urlAuthorization = null;
        final JSONArray identityProviders = lastResponse.getBody().getObject().getJSONObject("_embedded").getJSONArray("auth:identity-provider");
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
                    .header( HttpHeaders.ACCEPT, "application/json")
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
                                    } catch (final Exception exception) {
                                        LOG.log(Level.SEVERE, "failure", exception);
                                    }
                                };

                                final long refreshPeriodSeconds = 120;
                                sessionRefresher = scheduler.scheduleAtFixedRate(sessionRefresherCode, refreshPeriodSeconds, refreshPeriodSeconds, TimeUnit.SECONDS);
                                authorizationPromise.complete(null);
                            } else {
                                authorizationPromise.completeExceptionally(new Exception(response.getStatusText()));
                            }
                        }

                        @Override
                        public void cancelled() {
                            LOG.log(Level.INFO, "The authorization request has been cancelled");
                            authorizationPromise.cancel(true);
                        }

                        @Override
                        public void failed(UnirestException e) {
                            LOG.log(Level.SEVERE, "The authorization request has failed", e);
                            authorizationPromise.completeExceptionally(new Exception("The authorization request has failed", e));
                        }
                    });
        } else {
            authorizationPromise.completeExceptionally(new Exception("Authorization failed"));
        }
        return authorizationPromise;
    }

    /**
     * Promises delivery of a HAL resource representing the current session/identity token with the passed authorization
     * endpoint HAL resource.
     *
     * @param lastResponse a response object encapsulating the authorization endpoint HAL resource
     * @return  promise, which promises delivery of a HAL resource representing the current session/identity token
     *          encapsulated in an HttpResponse&lt;JsonNode>
     */
    public static CompletionStage<HttpResponse<JsonNode>> getCurrentToken(HttpResponse<JsonNode> lastResponse) {
        final CompletableFuture<HttpResponse<JsonNode>> promise = new CompletableFuture<>();

        final JsonNode authResult = lastResponse.getBody();
        final JSONArray authTokens = authResult.getObject().getJSONObject("_links").getJSONArray("auth:token");

        String urlCurrentToken = null;
        for (int i = 0; i < authTokens.length(); ++i) {
            if (Objects.equals(authTokens.getJSONObject(i).getString("name"), "current")) {
                urlCurrentToken = authTokens.getJSONObject(i).optString("href");
                break;
            }
        }

        if (null != urlCurrentToken) {
            Unirest.get(urlCurrentToken)
                    .header( HttpHeaders.ACCEPT, "application/json")
                    .asJsonAsync(new Callback<JsonNode>() {
                        @Override
                        public void completed(HttpResponse<JsonNode> currentTokenResponse) {
                            if (HttpURLConnection.HTTP_OK == currentTokenResponse.getStatus() || HttpURLConnection.HTTP_SEE_OTHER == currentTokenResponse.getStatus()) {
                                promise.complete(currentTokenResponse);
                            } else {
                                promise.completeExceptionally(new Exception(currentTokenResponse.getStatusText()));
                            }
                        }

                        @Override
                        public void cancelled() {
                            LOG.log(Level.INFO, "Getting current token cancelled");
                            promise.cancel(true);
                        }

                        @Override
                        public void failed(UnirestException e) {
                            LOG.log(Level.SEVERE, "Getting current token failed", e);
                            promise.completeExceptionally(new Exception("Getting current token failed", e));
                        }
                    });
        }

        return promise;
    }

    /**
     * Promises removal of session identified by the passed session/identity token.
     *
     * @param lastResponse a response object encapsulating the session/identity token HAL resource
     * @return  promise, which promises removal of the session
     */
    public static CompletionStage<HttpResponse<Empty>> removeToken(HttpResponse<JsonNode> lastResponse){
        final CompletableFuture<HttpResponse<Empty>> promise = new CompletableFuture<>();

        final int tokenStatusCode = lastResponse.getStatus();
        final String tokenRemoval = lastResponse.getBody().getObject().getJSONObject("_links").getJSONArray("auth-token:removal").getJSONObject(0).getString("href");

        Unirest.delete(tokenRemoval)
                .asEmptyAsync(new Callback<Empty>() {
                    @Override
                    public void completed(HttpResponse<Empty> deleteTokenResponse) {
                        // Should result in 204:
                        final int responseCode = deleteTokenResponse.getStatus();
                        promise.complete(deleteTokenResponse);
                    }

                    @Override
                    public void cancelled() {
                        LOG.log(Level.INFO, "Removing session cancelled");
                        promise.cancel(true);
                    }

                    @Override
                    public void failed(UnirestException e) {
                        LOG.log(Level.SEVERE, "Removing session cancelled", e);
                        promise.completeExceptionally(new Exception("Removing session failed", e));
                    }
                });
        return promise;
    }


    /**
     * Promises the results of the CTMS Registry lookup or promises the default URI for the resource in question.
     *
     * @param apiDomain address against to which we want send a keep alive signal
     * @param serviceTypes list of service types, of which the resource in question should be looked up in the CTMS
     *          Registry
     * @param registryServiceVersion registryServiceVersion version of the CTMS Registry to query
     * @param resourceName resourceName resource to look up in the CTMS Registry, such as "search:simple-search"
     * @param orDefaultUriTemplate URI template which will be returned in the promise, if the CTMS Registry is
     *          unreachable or the resource in question cannot be found
     * @return  promise, which promises delivery of a list of URI templates, under which the queried resource can be
     *          found. If the CTMS Registry is unreachable or the resource in question cannot be found, the list of URI
     *          templates will contain the orDefaultUriTemplate as single entry.
     */
    public static CompletionStage<List<String>> findInRegistry(String apiDomain, List<String> serviceTypes, String registryServiceVersion, String resourceName, String orDefaultUriTemplate) {
        final CompletableFuture<List<String>> promise = new CompletableFuture<>();

        Unirest.get(String.format("https://%s/apis/avid.ctms.registry;version=%s/serviceroots", apiDomain, registryServiceVersion))
                .header( HttpHeaders.ACCEPT, "application/json")
                .asJsonAsync(new Callback<JsonNode>() {
                    @Override
                    public void completed(HttpResponse<JsonNode> serviceRootsResponse) {
                        if (HttpURLConnection.HTTP_OK == serviceRootsResponse.getStatus() || HttpURLConnection.HTTP_SEE_OTHER == serviceRootsResponse.getStatus()) {
                            final JSONObject serviceRootsResult = serviceRootsResponse.getBody().getObject();
                            try {
                                final JSONObject resources = serviceRootsResult.optJSONObject("resources");
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
                                                promise.complete(Collections.singletonList(orDefaultUriTemplate));
                                            } else {
                                                promise.complete(Collections.unmodifiableList(result));
                                            }
                                        } else {
                                            final String href = ((JSONObject) resourcesObject).getString("href");

                                            if (serviceTypes.stream().anyMatch(href::contains)) {
                                                promise.complete(Collections.singletonList(href));
                                            } else {
                                                LOG.log(Level.INFO, "{0} not registered, defaulting to the specified URI template", resourceName);
                                                promise.complete(Collections.singletonList(orDefaultUriTemplate));
                                            }
                                        }
                                    } else {
                                        LOG.log(Level.INFO, "{0} not registered, defaulting to the specified URI template", resourceName);
                                        promise.complete(Collections.singletonList(orDefaultUriTemplate));
                                    }
                                } else {
                                    LOG.log(Level.INFO, "no registered resources found, defaulting to the specified default URI template");
                                    promise.complete(Collections.singletonList(orDefaultUriTemplate));
                                }
                            } catch (Exception e) {
                                LOG.log(Level.INFO, "unknown error requesting the CTMS Registry, defaulting to the specified URI template");
                                promise.complete(Collections.singletonList(orDefaultUriTemplate));
                            }
                        } else {
                            LOG.log(Level.INFO, "CTMS Registry not reachable, defaulting to the specified URI template");
                            promise.complete(Collections.singletonList(orDefaultUriTemplate));
                        }
                    }

                    @Override
                    public void cancelled() {
                        LOG.log(Level.INFO, "CTMS Registry not reachable (request cancelled), defaulting to the specified URI template");
                        promise.complete(Collections.singletonList(orDefaultUriTemplate));
                    }

                    @Override
                    public void failed(UnirestException e) {
                        LOG.log(Level.INFO, "CTMS Registry not reachable (request failed), defaulting to the specified URI template");
                        promise.complete(Collections.singletonList(orDefaultUriTemplate));
                    }
                });

        return promise;
    }



    /**
     * Promises delivery of all pages representing the HAL resources available via the passed resultPageURL.
     * <p>
     * If the HAL resource available from resultPageURL has the property "_embedded", its content will be collected
     * And if this HAL resource has the property "pageResult._links.next", its href will be used to fetch and collect
     * the next page and call this method recursively.
     *
     * @param resultPageURL URL to a HAL resource, which supports paging
     * @return  promise, which promises delivery of all pages representing the HAL resources available via the passed
     *          resultPageURL encapsulated in a List&lt;JSONObject>
     */
    public static CompletionStage<List<JSONObject>> pageThroughResultsAsync(String resultPageURL) {
        final CompletableFuture<List<JSONObject>> promise = new CompletableFuture<>();
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
                                        pageThroughResultsAsync(nextPageLinkObject.getString("href"))
                                                .thenAccept(o -> {
                                                    pages.addAll(o);
                                                    promise.complete(pages);
                                                });
                                    } else {
                                        promise.complete(pages);
                                    }
                                } else {
                                    promise.complete(pages);
                                }
                            } else {
                                promise.completeExceptionally(new Exception(response.getStatusText()));
                            }
                        } catch (final Exception e) {
                            promise.completeExceptionally(e);
                        }
                    }

                    @Override
                    public void cancelled() {
                        LOG.log(Level.INFO, "Paging cancelled for <{0}>", resultPageURL);
                        promise.cancel(true);
                    }

                    @Override
                    public void failed(UnirestException e) {
                        LOG.log(Level.SEVERE, e, () -> String.format("Paging failed for <%s>", resultPageURL));
                        promise.completeExceptionally(new Exception(String.format("Paging failed for <%s>", resultPageURL)));
                    }
                });

        return promise;
    }


    /**a
     * Signals to the platform, that our session is still in use.
     *
     * @param apiDomain address against to which we want send a keep alive signal
     */
    public static void sessionKeepAlive(String apiDomain) {
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

    public static void unregister() {
        /// Unregister the keep alive task:
        if (null != scheduler) {
            scheduler.shutdown();
            sessionRefresher.cancel(true);
        }
        try {
            Unirest.shutDown();
        } catch (final Exception exception) {
            LOG.log(Level.SEVERE, "failure", exception);
        }
    }
}

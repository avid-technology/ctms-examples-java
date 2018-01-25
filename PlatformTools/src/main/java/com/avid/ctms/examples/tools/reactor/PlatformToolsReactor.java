package com.avid.ctms.examples.tools.reactor;
/**
 * Copyright 2013-2017 by Avid Technology, Inc.
 * User: nludwig
 * Date: 2017-01-09
 * Time: 07:36
 * Project: CTMS
 */

import com.mashape.unirest.http.*;
import com.mashape.unirest.http.async.Callback;
import com.mashape.unirest.http.exceptions.UnirestException;
import org.apache.http.HttpHost;
import org.apache.http.conn.ssl.*;
import org.apache.http.impl.nio.client.*;
import org.json.*;
import reactor.core.publisher.*;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

/**
 * A set of reactive tooling methods with Unirest and Reactor.
 */
public class PlatformToolsReactor {
    private static final Logger LOG = Logger.getLogger(PlatformToolsReactor.class.getName());

    private static ScheduledExecutorService scheduler;
    private static ScheduledFuture<?> sessionRefresher;

    public static class Triple<F, S, T> {
        public F first;
        public S second;
        public T third;
        public Triple(F first, S second, T third) {
            this.first = first;
            this.second = second;
            this.third = third;
        }
    }

    public static class Pair<F, S> {
        public F first;
        public S second;
        public Pair(F first, S second) {
            this.first = first;
            this.second = second;
        }
    }

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

    private static CloseableHttpAsyncClient createSSLClient() throws KeyStoreException, NoSuchAlgorithmException, KeyManagementException {
        final SSLContext sslContext =
                org.apache.http.ssl.SSLContexts
                        .custom()
                        .loadTrustMaterial(null, new TrustSelfSignedStrategy())
                        .build();

        final String proxyHost = System.getProperty("https.proxyHost");
        final String proxyPort = System.getProperty("https.proxyPort");

        if (null != proxyHost) {
            LOG.log(Level.INFO, "using proxy: {0}, port: {1}", new Object[]{proxyHost, proxyPort});
        }

        return HttpAsyncClients.custom()
                .setSSLHostnameVerifier(new NoopHostnameVerifier())
                .setSSLContext(sslContext)
                .setProxy((null != proxyHost) ? new HttpHost(proxyHost, Integer.parseInt(proxyPort)) : null)
                .build();
    }

    private PlatformToolsReactor() {
    }

    /**
     * Prepares the Unirest environment.
     */
    public static void prepare() {
        Unirest.setTimeouts(getDefaultConnectionTimeoutms(), getDefaultReadTimeoutms());
        try {
            Unirest.setAsyncHttpClient(createSSLClient());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Promises delivery of a HAL resource representing the authorization endpoint.
     *
     * @param apiDomain address to get the authorization endpoint
     * @return  promise, which promises delivery of a HAL resource representing the authorization endpoint encapsulated
     *          in an HttpResponse<JsonNode>
     */
    public static Mono<HttpResponse<JsonNode>> getAuthEndpoint(String apiDomain) {
        return Mono.<HttpResponse<JsonNode>>create(sink -> {
            try {
                Unirest
                    .get(String.format("https://%s/auth", apiDomain))
                    .header("Accept", "application/json")
                    .asJsonAsync(new Callback<JsonNode>() {
                        @Override
                        public void completed(HttpResponse<JsonNode> authResponse) {
                            if (200 == authResponse.getStatus() || 303 == authResponse.getStatus()) {
                                sink.success(authResponse);
                            } else {
                                sink.error(new Exception(authResponse.getStatusText()));
                            }
                        }

                        @Override
                        public void cancelled() {
                            LOG.log(Level.INFO, "The request has been cancelled");
                            sink.error(new Exception("The request has been cancelled"));
                        }

                        @Override
                        public void failed(UnirestException e) {
                            LOG.log(Level.SEVERE, "The request has failed", e);
                            sink.error(new Exception("The request has failed", e));
                        }
                    });
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "{0}", e);
                sink.error(e);
            }
        });
    }

    /**
     * Promises delivery of a HAL resource representing the identity providers with the passed authorization endpoint
     * HAL resource.
     *
     * @param lastResponse a response object encapsulating the authorization endpoint HAL resource
     * @return  promise, which promises delivery of a HAL resource representing the identity providers encapsulated
     *          in an HttpResponse<JsonNode>
     */
    public static Mono<HttpResponse<JsonNode>> getIdentityProviders(HttpResponse<JsonNode> lastResponse) {
        return Mono.<HttpResponse<JsonNode>>create(sink -> {
            try {
                final JSONObject authResult = lastResponse.getBody().getObject();
                final String urlIdentityProviders = authResult.getJSONObject("_links").getJSONArray("auth:identity-providers").getJSONObject(0).getString("href");
                Unirest.get(urlIdentityProviders)
                        .header("Accept", "application/json")
                        .asJsonAsync(new Callback<JsonNode>() {
                            @Override
                            public void completed(HttpResponse<JsonNode> response) {
                                if (200 == response.getStatus() || 303 == response.getStatus()) {
                                    sink.success(response);
                                } else {
                                    sink.error(new Exception(response.getStatusText()));
                                }
                            }

                            @Override
                            public void cancelled() {
                                LOG.log(Level.INFO, "The request has been cancelled");
                                sink.error(new Exception("The request has been cancelled"));
                            }

                            @Override
                            public void failed(UnirestException e) {
                                LOG.log(Level.SEVERE, "The request has failed", e);
                                sink.error(new Exception("The request has failed", e));
                            }
                        });
            } catch (final Exception e) {
                sink.error(new Exception("Requesting auth has failed", e));
            }
        });
    }

    /**
     * Promises MC|UX-based authorization with the passed identity providers HAL resource.
     *
     * @param apiDomain address to get "auth"
     * @param username  MC|UX login
     * @param password  MC|UX password
     * @return  promise, which promises authorization
     */
    public static Mono<Object> authorize(HttpResponse<JsonNode> lastResponse, String apiDomain, String username, String password) {
        return Mono.create(sink -> {
            try {
                // Select MC|UX identity provider and retrieve login URL:
                String urlAuthorization = null;
                final JSONArray identityProviders = lastResponse.getBody().getObject().getJSONObject("_embedded").getJSONArray("auth:identity-provider");
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
                if (null != urlAuthorization) {
                    Unirest
                        .post(urlAuthorization)
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .body(String.format("{ \"username\" : \"%s\", \"password\" :  \"%s\"}", username, password))
                        .asJsonAsync(new Callback<JsonNode>() {
                            @Override
                            public void completed(HttpResponse<JsonNode> response) {
                                if (200 == response.getStatus() || 303 == response.getStatus()) {
                                    scheduler = Executors.newScheduledThreadPool(1);
                                    final Runnable sessionRefresherCode = () -> {
                                        try {
                                            sessionKeepAlive(apiDomain);
                                        } catch (final IOException exception) {
                                            LOG.log(Level.SEVERE, "failure", exception);
                                        }
                                    };

                                    final long refreshPeriodSeconds = 120;
                                    sessionRefresher = scheduler.scheduleAtFixedRate(sessionRefresherCode, refreshPeriodSeconds, refreshPeriodSeconds, TimeUnit.SECONDS);
                                    sink.success(new Object());
                                } else {
                                    sink.error(new Exception(response.getStatusText()));
                                }
                            }

                            @Override
                            public void cancelled() {
                                LOG.log(Level.INFO, "The authorization request has been cancelled");
                                sink.error(new Exception("The authorization request has been cancelled"));
                            }

                            @Override
                            public void failed(UnirestException e) {
                                LOG.log(Level.SEVERE, "The authorization request has failed", e);
                                sink.error(new Exception("The authorization request has failed", e));
                            }
                        });
                } else {
                    sink.error(new Exception("Authorization failed"));
                }
            } catch (final JSONException e) {
                sink.error(e);
            }
        });
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
    public static Mono<List<String>> findInRegistry(String apiDomain, List<String> serviceTypes, String registryServiceVersion, String resourceName, String orDefaultUriTemplate) {
        return Mono.<List<String>>create(sink -> {
            try {
                Unirest.get(String.format("https://%s/apis/avid.ctms.registry;version=%s/serviceroots", apiDomain, registryServiceVersion))
                    .header("Accept", "application/json")
                    .asJsonAsync(new Callback<JsonNode>() {
                        @Override
                        public void completed(HttpResponse<JsonNode> serviceRootsResponse) {
                            if (200 == serviceRootsResponse.getStatus() || 303 == serviceRootsResponse.getStatus()) {
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
                                                    sink.success(Collections.singletonList(orDefaultUriTemplate));
                                                } else {
                                                    sink.success(Collections.unmodifiableList(result));
                                                }
                                            } else {
                                                final String href = ((JSONObject) resourcesObject).getString("href");

                                                if (serviceTypes.stream().anyMatch(href::contains)) {
                                                    sink.success(Collections.singletonList(href));
                                                } else {
                                                    LOG.log(Level.INFO, "{0} not registered, defaulting to the specified URI template", resourceName);
                                                    sink.success(Collections.singletonList(orDefaultUriTemplate));
                                                }
                                            }
                                        } else {
                                            LOG.log(Level.INFO, "{0} not registered, defaulting to the specified URI template", resourceName);
                                            sink.success(Collections.singletonList(orDefaultUriTemplate));
                                        }
                                    } else {
                                        LOG.log(Level.INFO, "no registered resources found, defaulting to the specified default URI template");
                                        sink.success(Collections.singletonList(orDefaultUriTemplate));
                                    }
                                } catch (Exception e) {
                                    LOG.log(Level.INFO, "unknown error requesting the CTMS Registry, defaulting to the specified URI template");
                                    sink.success(Collections.singletonList(orDefaultUriTemplate));
                                }
                            } else {
                                LOG.log(Level.INFO, "CTMS Registry not reachable, defaulting to the specified URI template");
                                sink.success(Collections.singletonList(orDefaultUriTemplate));
                            }
                        }

                        @Override
                        public void cancelled() {
                            LOG.log(Level.INFO, "CTMS Registry not reachable (request cancelled), defaulting to the specified URI template");
                            sink.success(Collections.singletonList(orDefaultUriTemplate));
                        }

                        @Override
                        public void failed(UnirestException e) {
                            LOG.log(Level.INFO, "CTMS Registry not reachable (request failed), defaulting to the specified URI template");
                            sink.success(Collections.singletonList(orDefaultUriTemplate));
                        }
                    });
            } catch (final JSONException e) {
                sink.error(e);
            }
        });
    }

    /**
     * Promises delivery of a HAL resource representing the current session/identity token with the passed authorization
     * endpoint HAL resource.
     *
     * @param lastResponse a response object encapsulating the authorization endpoint HAL resource
     * @return  promise, which promises delivery of a HAL resource representing the current session/identity token
     *          encapsulated in an HttpResponse<JsonNode>
     */
    public static Mono<HttpResponse<JsonNode>> getCurrentToken(HttpResponse<JsonNode> lastResponse) {
        return Mono.<HttpResponse<JsonNode>>create(sink -> {
            try {
                final JsonNode authResult = lastResponse.getBody();
                final JSONArray authTokens = authResult.getObject().getJSONObject("_links").getJSONArray("auth:token");

                String urlCurrentToken = null;
                for (int i = 0; i < authTokens.length(); ++i) {
                    if (Objects.equals(authTokens.getJSONObject(i).getString("name"), "current")) {
                        urlCurrentToken = authTokens.getJSONObject(i).getString("href");
                        break;
                    }
                }

                if (null != urlCurrentToken) {
                    Unirest
                            .get(urlCurrentToken)
                            .header("Accept", "application/json")
                            .asJsonAsync(new Callback<JsonNode>() {
                                @Override
                                public void completed(HttpResponse<JsonNode> currentTokenResponse) {
                                    if (200 == currentTokenResponse.getStatus() || 303 == currentTokenResponse.getStatus()) {
                                        sink.success(currentTokenResponse);
                                    } else {
                                        sink.error(new Exception(currentTokenResponse.getStatusText()));
                                    }
                                }

                                @Override
                                public void cancelled() {
                                    LOG.log(Level.INFO, "Getting current token cancelled");
                                    sink.error(new Exception("Getting current token cancelled"));
                                }

                                @Override
                                public void failed(UnirestException e) {
                                    LOG.log(Level.SEVERE, "Getting current token failed", e);
                                    sink.error(new Exception("Getting current token failed", e));
                                }
                            });
                }
            } catch (final JSONException e) {
                sink.error(e);
            }
        });
    }

    /**
     * Promises removal of session identified by the passed session/identity token.
     *
     * @param lastResponse a response object encapsulating the session/identity token HAL resource
     * @return  promise, which promises removal of the session
     */
    public static Mono<HttpResponse<JsonNode>> removeToken(HttpResponse<JsonNode> lastResponse){
        return Mono.<HttpResponse<JsonNode>>create(sink -> {
            try {
                final int tokenStatusCode = lastResponse.getStatus();
                final String tokenRemoval = lastResponse.getBody().getObject().getJSONObject("_links").getJSONArray("auth-token:removal").getJSONObject(0).getString("href");

                Unirest
                        .delete(tokenRemoval)
                        .asJsonAsync(new Callback<JsonNode>() {
                            @Override
                            public void completed(HttpResponse<JsonNode> deleteTokenResponse) {
                                // Should result in 204:
                                final int responseCode = deleteTokenResponse.getStatus();
                                sink.success(deleteTokenResponse);
                            }

                            @Override
                            public void cancelled() {
                                LOG.log(Level.INFO, "Removing session cancelled");
                                sink.error(new Exception("Removing session cancelled"));
                            }

                            @Override
                            public void failed(UnirestException e) {
                                LOG.log(Level.SEVERE, "Removing session cancelled", e);
                                sink.error(new Exception("Removing session failed", e));
                            }
                        });
            } catch (final JSONException e) {
                sink.error(e);
            }
        });
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
     *          resultPageURL encapsulated in a List<JSONObject>
     */
    public static void pageThroughResultsAsync(String resultPageURL, FluxSink<JSONObject> sink) {
        if(!sink.isCancelled()) {
            try {
                Unirest
                        .get(resultPageURL)
                        .header("Accept", "application/json")
                        .asJsonAsync(new Callback<JsonNode>() {
                            @Override
                            public void completed(HttpResponse<JsonNode> response) {
                                try {
                                    if (200 == response.getStatus() || 303 == response.getStatus()) {
                                        if (response.getBody().getObject().has("_embedded")) { // Do we have (more) results?
                                            final JSONObject embeddedResults = response.getBody().getObject().getJSONObject("_embedded");
                                            sink.next(embeddedResults);

                                            // If we have more results, follow the next link and get the next page:
                                            final JSONObject links = response.getBody().getObject().getJSONObject("_links");
                                            final JSONObject nextPageLinkObject = links.has("next") ? links.getJSONObject("next") : null;
                                            if (null != nextPageLinkObject) {
                                                final String nextHref = nextPageLinkObject.getString("href");
                                                pageThroughResultsAsync(nextHref, sink);
                                            } else {
                                                sink.complete();
                                            }
                                        } else {
                                            sink.complete();
                                        }
                                    } else {
                                        sink.error(new Exception(response.getStatusText()));
                                    }
                                } catch (final Exception e) {
                                    sink.error(new Exception(String.format("Paging failed for <%s>", resultPageURL)));
                                }
                            }

                            @Override
                            public void cancelled() {
                                LOG.log(Level.INFO, "Paging cancelled for <{0}>", resultPageURL);
                                sink.complete();
                            }

                            @Override
                            public void failed(UnirestException e) {
                                LOG.log(Level.SEVERE, e, () -> String.format("Paging failed for <%s>", resultPageURL));
                                sink.error(new Exception(String.format("Paging failed for <%s>", resultPageURL)));
                            }
                        });
            } catch (final Exception e) {
                sink.error(e);
            }
        } else {
            sink.complete();
        }
    }


    /**
     * Signals to the platform, that our session is still in use.
     *
     * @param apiDomain address against to which we want send a keep alive signal
     * @throws IOException
     */
    public static void sessionKeepAlive(String apiDomain) throws IOException {
        // TODO: this is a workaround, see {CORE-7359}. In future the access token prolongation API should be used.
        Unirest
            .get(String.format("https://%s/api/middleware/service/ping", apiDomain))
            .header("Accept", "application/json")
            .asJsonAsync(new Callback<JsonNode>() {
                @Override
                public void completed(HttpResponse<JsonNode> response) {
                    LOG.log(Level.INFO, "Pinged");
                }

                @Override
                public void cancelled() {
                    LOG.log(Level.INFO, "The ping request was cancelled.");
                }

                @Override
                public void failed(UnirestException e) {
                    LOG.log(Level.SEVERE, "The ping request failed: {0}", e);
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
            Unirest.shutdown();
        } catch (final IOException exception) {
            LOG.log(Level.SEVERE, "failure", exception);
        }
    }
}

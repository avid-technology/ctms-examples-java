package com.avid.ctms.examples.tools.async;

import com.avid.ctms.examples.tools.common.*;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;

import javax.net.ssl.*;
import javax.ws.rs.client.InvocationCallback;
import javax.ws.rs.client.*;
import javax.ws.rs.core.Cookie;
import javax.ws.rs.core.NewCookie;
import javax.ws.rs.core.Response;

import java.io.IOException;
import java.net.*;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.*;

/**
 * Copyright 2013-2017 by Avid Technology, Inc.
 * User: nludwig
 * Date: 2016-10-30
 * Time: 07:36
 * Project: CTMS
 */

/**
 * A set of asynchronous tooling methods.
 */
public class PlatformToolsAsync {
    private static final Logger LOG = Logger.getLogger(PlatformToolsAsync.class.getName());

    private static ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static ScheduledFuture<?> sessionRefresher;

    private static ClientBuilder clientBuilder;

    /**
     * Retrieves the default connection timeout in ms.
     *
     * @return the default connection timeout in ms
     */
    public static int getDefaultConnectionTimeouts() {
        return 60_000;
    }

    /**
     * Retrieves the default request timeout in ms.
     *
     * @return the default request timeout in ms
     */
    public static int getDefaultReadTimeouts() {
        return 60_000;
    }

    private PlatformToolsAsync() {
    }

    static {
        final TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return null;
                    }

                    @Override
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {
                    }
                }
        };

        SSLContext sslContext = null;
        try {
            sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
        } catch (final KeyManagementException | NoSuchAlgorithmException exception) {
            LOG.log(Level.SEVERE, "failure", exception);
        }

        final HostnameVerifier allHostsValid = (hostname, session) -> true;

        clientBuilder = new ResteasyClientBuilder()
                /// Establish tolerant certificate check:
                .sslContext(sslContext)
                .hostnameVerifier(allHostsValid)
                /// Set timeouts:
                .establishConnectionTimeout(getDefaultConnectionTimeouts(), TimeUnit.SECONDS)
                .socketTimeout(getDefaultReadTimeouts(), TimeUnit.SECONDS)
        /// Add proxy:
        //.defaultProxy("127.0.0.1", 8888)
        ;
    }

    public static ClientBuilder getClientBuilder() {
        return clientBuilder;
    }

    /**
     * MCUX-identity-provider based authorization via credentials and cookies.
     *
     * @param apiDomain address to get "auth"
     * @param username  MCUX login
     * @param password  MCUX password
     * @param done      a "continuation" callback, which is called, if the authorization procedure ended successfully. The
     *                  callback should execute the code, which continues working with the session resulting from the
     *                  authorization.
     * @param failed    a "continuation" callback, which is called, if the authorization procedure failed.
     */
    public static void authorize(String apiDomain, String username, String password, Consumer<Client> done, Terminator<String, Throwable> failed) throws KeyManagementException, NoSuchAlgorithmException {
        final Client client = clientBuilder.build();

        client.target(String.format("https://%s/auth", apiDomain))
                .request()
                .accept("application/json")
                .async()
                .get(new InvocationCallback<String>() {
                    @Override
                    public void completed(String rawAuthResult) {
                        final JSONObject authResult = JSONObject.fromObject(rawAuthResult);
                        final String urlIdentityProviders = authResult.getJSONObject("_links").getJSONArray("auth:identity-providers").getJSONObject(0).getString("href");
                        client.target(urlIdentityProviders)
                                .request()
                                .accept("application/json")
                                .async()
                                .get(new InvocationCallback<String>() {
                                    @Override
                                    public void completed(String rawIdentityProvidersResult) {
                                        // Select MC|UX identity provider and retrieve login URL:
                                        String urlAuthorization = null;
                                        final JSONObject identityProvidersResult = JSONObject.fromObject(rawIdentityProvidersResult);
                                        final JSONArray identityProviders = identityProvidersResult.getJSONObject("_embedded").getJSONArray("auth:identity-provider");
                                        for (final Object item : identityProviders) {
                                            final JSONObject identityProvider = (JSONObject) item;
                                            if (Objects.equals(identityProvider.get("kind"), "mcux")) {
                                                final JSONArray logins = identityProvider.getJSONObject("_links").getJSONArray("auth-mcux:login");
                                                if (!logins.isEmpty()) {
                                                    urlAuthorization = logins.getJSONObject(0).getString("href");
                                                }
                                                break;
                                            }
                                        }

                                        if (null != urlAuthorization) {
                                            client.target(urlAuthorization)
                                                    .request()
                                                    .accept("application/json")
                                                    .async()
                                                    .post(Entity.json(String.format("{ \"username\" : \"%s\", \"password\" :  \"%s\"}", username, password))
                                                            , new InvocationCallback<Response>() {
                                                        @Override
                                                        public void completed(Response loginResponse) {
                                                            if (200 == loginResponse.getStatus() || 303 == loginResponse.getStatus()) {
                                                                final Map<String, NewCookie> cookies = loginResponse.getCookies();
                                                                final List<Cookie> cookies1 = new ArrayList<>(cookies.size());
                                                                for (final Map.Entry<String, NewCookie> item : cookies.entrySet()) {
                                                                    cookies1.add(item.getValue().toCookie());
                                                                }

                                                                scheduler = Executors.newScheduledThreadPool(1);
                                                                final Runnable sessionRefresherCode = () -> {
                                                                    try {
                                                                        sessionKeepAlive(cookies1, apiDomain);
                                                                    } catch (final IOException exception) {
                                                                        LOG.log(Level.SEVERE, "failure", exception);
                                                                    }
                                                                };

                                                                final long refreshPeriodSeconds = 120;
                                                                sessionRefresher = scheduler.scheduleAtFixedRate(sessionRefresherCode, refreshPeriodSeconds, refreshPeriodSeconds, TimeUnit.SECONDS);
                                                                loginResponse.close();
                                                                done.accept(client);
                                                            } else {
                                                                loginResponse.close();
                                                                client.close();
                                                                failed.terminate(loginResponse.getStatusInfo().getReasonPhrase(), null);
                                                                unregister();
                                                            }
                                                        }

                                                        @Override
                                                        public void failed(Throwable throwable) {
                                                            client.close();
                                                            failed.terminate("Authorization failed", throwable);
                                                            unregister();
                                                        }
                                                    });
                                        }
                                    }

                                    @Override
                                    public void failed(Throwable throwable) {
                                        client.close();
                                        failed.terminate("Getting 'identity-providers' failed", throwable);
                                        unregister();
                                    }
                                });
                    }

                    @Override
                    public void failed(Throwable throwable) {
                        client.close();
                        failed.terminate("Getting 'auth' failed", throwable);
                        unregister();
                    }
                });
    }


    /**
     * Queries the CTMS Registry via the passed apiDomain and registryServiceVersion. The results are passed to the
     * additionally passed "continuation" callback "done", which is executed after the query has completed.
     *
     * @param client the client, which is used to get the query the CTMS Registry
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
     *          orDefaultUriTemplate as single entry. The callback should execute the code, which continues working
     *          with the session given in the Client parameter.
     * @throws IOException
     */
    public static void findInRegistry(Client client, String apiDomain, List<String> serviceTypes, String registryServiceVersion, String resourceName, String orDefaultUriTemplate, Consumer<Map.Entry<Client, List<String>>> done) throws IOException {
        client.target(String.format("https://%s/apis/avid.ctms.registry;version=%s/serviceroots", apiDomain, registryServiceVersion))
                .request()
                .accept("application/json")
                .async()
                .get(new InvocationCallback<String>() {
                    @Override
                    public void completed(String rawServiceRootsResult) {
                        /// Doing the registry lookup and write the results to stdout:
                        final JSONObject serviceRootsResult = JSONObject.fromObject(rawServiceRootsResult);

                        final JSONObject resources = serviceRootsResult.getJSONObject("resources");
                        if (null != resources) {
                            if (resources.has(resourceName)) {
                                final Object resourcesObject = resources.get(resourceName);
                                if (resourcesObject instanceof JSONArray) {
                                    final JSONArray asArray = (JSONArray) resourcesObject;
                                    final List<String> result = new ArrayList<>(asArray.size());
                                    for (final Object singleLinkObject : asArray) {
                                        final String href = ((JSONObject) singleLinkObject).getString("href");
                                        if (serviceTypes.stream().anyMatch(href::contains)) {
                                            result.add(href);
                                        }
                                    }

                                    if (result.isEmpty()) {
                                        LOG.log(Level.INFO, "{0} not registered, defaulting to the specified URI template", resourceName);
                                        done.accept(new AbstractMap.SimpleEntry<>(client, Collections.singletonList(orDefaultUriTemplate)));
                                    } else {
                                        done.accept(new AbstractMap.SimpleEntry<>(client, Collections.unmodifiableList(result)));
                                    }
                                } else {
                                    final String href = ((JSONObject) resourcesObject).getString("href");

                                    if (serviceTypes.stream().anyMatch(href::contains)) {
                                        done.accept(new AbstractMap.SimpleEntry<>(client, Collections.singletonList(href)));
                                    } else {
                                        LOG.log(Level.INFO, "{0} not registered, defaulting to the specified URI template", resourceName);
                                        done.accept(new AbstractMap.SimpleEntry<>(client, Collections.singletonList(orDefaultUriTemplate)));
                                    }
                                }
                            } else {
                                LOG.log(Level.INFO, "{0} not registered, defaulting to the specified URI template", resourceName);
                                done.accept(new AbstractMap.SimpleEntry<>(client, Collections.singletonList(orDefaultUriTemplate)));
                            }
                        } else {
                            LOG.log(Level.INFO, "no registered resources found, defaulting to the specified default URI template");
                            done.accept(new AbstractMap.SimpleEntry<>(client, Collections.singletonList(orDefaultUriTemplate)));
                        }
                    }

                    @Override
                    public void failed(Throwable throwable) {
                        LOG.log(Level.INFO, "CTMS Registry not reachable (request failed), defaulting to the specified URI template");
                        done.accept(new AbstractMap.SimpleEntry<>(client, Collections.singletonList(orDefaultUriTemplate)));
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
     * @param client        the client, which is used to get the pages
     * @param resultPageURL URL to a HAL resource, which supports paging
     * @param done          a "continuation" callback, which is called, if the paging procedure ended successfully, the pages
     *                      passed to this block.
     * @param failed        a "continuation" callback, which is called, if the paging procedure failed.
     */
    public static void pageThroughResultsAsync(Client client, String resultPageURL, Consumer<List<JSONObject>> done,
                                               Terminator<String, Throwable> failed) {
        final List<JSONObject> pages = new ArrayList<>();

        client.target(resultPageURL)
                .request()
                .accept("application/json")
                .async()
                .get(new InvocationCallback<String>() {
                    @Override
                    public void completed(String rawSimpleSearchPageResult) {
                        final JSONObject simpleSearchPageResult = JSONObject.fromObject(rawSimpleSearchPageResult);
                        final JSONObject embeddedResults = (JSONObject) simpleSearchPageResult.get("_embedded");
                        // Do we have results:
                        if (null != embeddedResults) {
                            pages.add(embeddedResults);

                            // If we have more results, follow the next link and get the next page:
                            final JSONObject linkToNextPage = (JSONObject) simpleSearchPageResult.getJSONObject("_links").get("next");

                            if (null != linkToNextPage) {
                                pageThroughResultsAsync(client
                                        , linkToNextPage.getString("href")
                                        , objects -> {
                                    pages.addAll(objects);
                                    done.accept(pages);
                                }
                                        , failed::terminate);
                            } else {
                                done.accept(pages);
                            }
                        } else {
                            done.accept(pages);
                            unregister();
                        }
                    }

                    @Override
                    public void failed(Throwable throwable) {
                        client.close();
                        failed.terminate(String.format("Service <%s> not reachable (request failed)", resultPageURL), throwable);
                        unregister();
                    }
                });
    }


    /**
     * Signals the platform, that our session is still in use.
     *
     * @param cookies   the cookies representing the status of the MC|UX session
     * @param apiDomain address against to which we want send a keep alive signal
     * @throws IOException
     */
    public static void sessionKeepAlive(List<Cookie> cookies, String apiDomain) throws IOException {
        // TODO: this is a workaround, see {CORE-7359}. In future the access token prolongation API should be used.
        final Client client = clientBuilder.build();

        final Invocation.Builder builder =
                client.target(String.format("https://%s/api/middleware/service/ping", apiDomain))
                        .request();

        for (final Cookie cookie : cookies) {
            builder.cookie(cookie);
        }

        builder.accept("application/json")
                .async()
                .get(new InvocationCallback<String>() {
                    @Override
                    public void completed(String ping) {
                        client.close();
                    }

                    @Override
                    public void failed(Throwable throwable) {
                        client.close();
                    }
                });
    }

    /**
     * Performs a logout against the platform.
     *
     * @param apiDomain address against to which we want to logout
     */
    public static void logout(Client client, String apiDomain, Consumer<Client> done, Terminator<String, Throwable> failed) {
        client.target(String.format("https://%s/auth", apiDomain))
                .request()
                .accept("application/json")
                .async()
                .get(new InvocationCallback<String>() {
                    @Override
                    public void completed(String rawAuthResult) {
                        final JSONObject authResult = JSONObject.fromObject(rawAuthResult);
                        final JSONArray authTokens = authResult.getJSONObject("_links").getJSONArray("auth:token");
                        String currentTokenURL = null;
                        for (final Object item : authTokens) {
                            final JSONObject authToken = (JSONObject) item;
                            if (Objects.equals(authToken.get("name"), "current")) {
                                currentTokenURL = authToken.getString("href");
                                break;
                            }
                        }

                        if (null != currentTokenURL) {
                            client.target(currentTokenURL)
                                    .request()
                                    .accept("application/json")
                                    .async()
                                    .get(new InvocationCallback<String>() {
                                        @Override
                                        public void completed(String rawCurrentTokenResult) {
                                            final JSONObject currentTokenResult = JSONObject.fromObject(rawCurrentTokenResult);

                                            final String urlCurrentTokenRemoval = currentTokenResult.getJSONObject("_links").getJSONArray("auth-token:removal").getJSONObject(0).getString("href");
                                            client.target(urlCurrentTokenRemoval)
                                                    .request()
                                                    .accept("application/json")
                                                    .async()
                                                    .delete(new InvocationCallback<Response>() {
                                                        @Override
                                                        public void completed(Response currentTokenRemovalResponse) {
                                                            // Should result in 204:
                                                            final int responseCode = currentTokenRemovalResponse.getStatus();
                                                            currentTokenRemovalResponse.close();
                                                            client.close();
                                                            done.accept(client);
                                                            unregister();
                                                        }

                                                        @Override
                                                        public void failed(Throwable throwable) {
                                                            client.close();
                                                            failed.terminate("Deleting current token failed", throwable);
                                                            unregister();
                                                        }
                                                    });
                                        }

                                        @Override
                                        public void failed(Throwable throwable) {
                                            client.close();
                                            failed.terminate("Getting current token failed", throwable);
                                            unregister();
                                        }
                                    });
                        }
                    }

                    @Override
                    public void failed(Throwable throwable) {
                        client.close();
                        failed.terminate("Getting 'auth' failed", throwable);
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
    }
}

package com.avid.ctms.examples.tools.common;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.security.*;
import java.security.cert.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

import net.sf.json.*;

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

    private PlatformTools() {
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
    public static boolean authorize(String apiDomain, String username, String password)
            throws NoSuchAlgorithmException, KeyManagementException, IOException {

        /// Establish tolerant certificate check:
        final TrustManager[] trustAllCerts = new TrustManager[] {
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
        final SSLContext sslContext = SSLContext.getInstance("SSL");
        sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
        final HostnameVerifier allHostsValid = (hostname, session) -> true;
        HttpsURLConnection.setDefaultHostnameVerifier(allHostsValid);
        /// END - Establish tolerant certificate check


        /// Establish in-memory cookie store:
        CookieHandler.setDefault(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
        /// END - Establish in-memory cookie store


        /// Authorization procedure:
        // Get identity providers:
        final URL authURL = new URL(String.format("https://%s/auth", apiDomain));
        final HttpURLConnection connectionAuth = prepareHttpURLConnection(authURL);
        connectionAuth.setRequestProperty("Accept", "application/json");
        final int authStatusCode = connectionAuth.getResponseCode();

        final String rawAuthResult = getContent(connectionAuth);
        final JSONObject authResult = JSONObject.fromObject(rawAuthResult);
        final String urlIdentityProviders = authResult.getJSONObject("_links").getJSONArray("auth:identity-providers").getJSONObject(0).getString("href");

        final URL identityProvidersURL = new URL(urlIdentityProviders);
        final HttpURLConnection connectionIdentityProviders = prepareHttpURLConnection(identityProvidersURL);
        connectionIdentityProviders.setRequestProperty("Accept", "application/json");
        final int identityProvidersStatusCode = connectionIdentityProviders.getResponseCode();

        final String rawIdentityProvidersResult = getContent(connectionIdentityProviders);
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

        // Do the login:
        if (null != urlAuthorization) {
            final URL loginURL = new URL(urlAuthorization);
            final HttpURLConnection connectionLogin = prepareHttpURLConnection(loginURL);
            connectionLogin.setRequestMethod("POST");
            connectionLogin.setDoOutput(true);
            connectionLogin.setRequestProperty("Content-Type", "application/json");
            connectionLogin.setRequestProperty("Accept", "application/json");
            connectionLogin.setInstanceFollowRedirects(true);
            final String loginContent = String.format("{ \"username\" : \"%s\", \"password\" : \"%s\"}", username, password);


            connectionLogin.getOutputStream().write(loginContent.getBytes());
            // Check success:
            final int loginStatusCode = connectionLogin.getResponseCode();
            if (HttpURLConnection.HTTP_SEE_OTHER == loginStatusCode || HttpURLConnection.HTTP_OK == loginStatusCode) {
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
     * Signals the platform, that our session is still in use.
     *
     * @param apiDomain address against to which we want send a keep alive signal
     * @throws IOException
     */
    public static void sessionKeepAlive(String apiDomain) throws IOException {
        // TODO: this is a workaround, see {CORE-7359}. In future the access token prolongation API should be used.
        final URL pingURL = new URL(String.format("https://%s/api/middleware/service/ping", apiDomain));
        final HttpURLConnection connectionPing = prepareHttpURLConnection(pingURL);
        connectionPing.setRequestProperty("Accept", "application/json");
        final int ignoredPingStatusCode = connectionPing.getResponseCode();
    }

    /**
     * Performs a logout against the platform.
     *
     * @param apiDomain address against to which we want to logout
     * @throws IOException
     */
    public static void logout(String apiDomain) throws IOException {
        /// Logout from platform:
        final URL authURL = new URL(String.format("https://%s/auth", apiDomain));
        final HttpURLConnection connectionAuth = (HttpURLConnection) authURL.openConnection();
        connectionAuth.setRequestProperty("Accept", "application/json");
        connectionAuth.setConnectTimeout(getDefaultConnectionTimeoutms());
        connectionAuth.setReadTimeout(getDefaultReadTimeoutms());
        final int authStatusCode = connectionAuth.getResponseCode();

        final String rawAuthResult = getContent(connectionAuth);
        final JSONObject authResult = JSONObject.fromObject(rawAuthResult);
        final JSONArray authTokens = authResult.getJSONObject("_links").getJSONArray("auth:token");
        URL currentTokenURL = null;
        for (final Object item : authTokens) {
            final JSONObject authToken = (JSONObject) item;
            if (Objects.equals(authToken.get("name"), "current")) {
                currentTokenURL = new URL(authToken.getString("href"));
                break;
            }
        }

        if (null != currentTokenURL) {
            final HttpURLConnection currentTokenConnection = prepareHttpURLConnection(currentTokenURL);
            currentTokenConnection.setRequestProperty("Accept", "application/json");
            final int currentTokenStatusCode = currentTokenConnection.getResponseCode();

            final String rawCurrentTokenResult = getContent(currentTokenConnection);
            final JSONObject currentTokenResult = JSONObject.fromObject(rawCurrentTokenResult);

            final String urlCurrentTokenRemoval = currentTokenResult.getJSONObject("_links").getJSONArray("auth-token:removal").getJSONObject(0).getString("href");
            final URL currentTokenRemovalURL = new URL(urlCurrentTokenRemoval);

            final HttpURLConnection currentTokenRemovalConnection = prepareHttpURLConnection(currentTokenRemovalURL);
            currentTokenRemovalConnection.setRequestMethod("DELETE");
            // Should result in 204:
            final int currentTokenRemovalStatusCode = currentTokenRemovalConnection.getResponseCode();
        }


        /// Unregister the keep alive task:
        if (null != scheduler) {
            sessionRefresher.cancel(true);
            scheduler.shutdown();
        }
    }

    public static HttpURLConnection prepareHttpURLConnection(URL identityProvidersURL) throws IOException {
        final HttpURLConnection connectionIdentityProviders = (HttpURLConnection) identityProvidersURL.openConnection();
        connectionIdentityProviders.setConnectTimeout(getDefaultConnectionTimeoutms());
        connectionIdentityProviders.setReadTimeout(getDefaultReadTimeoutms());
        return connectionIdentityProviders;
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

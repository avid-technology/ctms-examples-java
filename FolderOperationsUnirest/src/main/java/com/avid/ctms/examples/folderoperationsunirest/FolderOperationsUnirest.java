package com.avid.ctms.examples.folderoperationsunirest;

import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.logging.*;

import com.avid.ctms.examples.tools.common.AuthorizationResponse;
import com.avid.ctms.examples.tools.common.ItemInfo;
import com.avid.ctms.examples.tools.common.PlatformTools;
import kong.unirest.*;

import javax.ws.rs.core.UriBuilder;

/**
 * Copyright 2013-2019 by Avid Technology, Inc.
 * User: nludwig
 * Date: 2017-1-31
 * Time: 10:45
 * Project: CTMS
 */

/**
 * This example performs some folder operations: create, update (rename) and delete a folder.
 * !! Notice, that this example uses Unirest to put those operations into effect, esp. because the HTTP verb "PATCH" is
 * not supported in the JDK !!
 */
public class FolderOperationsUnirest {
    private static final Logger LOG = Logger.getLogger(FolderOperationsUnirest.class.getName());

    private FolderOperationsUnirest() {
    }

    private static void performItemOperations(String locationsURL) {
        final HttpResponse<JsonNode> locationsResponse =
                Unirest.get(locationsURL)
                        .header("Accept", "application/hal+json")
                        .asJson();

        final int locationsStatus = locationsResponse.getStatus();
        if (HttpURLConnection.HTTP_OK == locationsStatus) {
            String itemByIDTemplated = locationsResponse.getBody().getObject().getJSONObject("_links").getJSONObject("loc:item-by-id").getString("href");

            itemByIDTemplated = itemByIDTemplated.replace("{id}", "/Catalogs/OneArchive/Admin/Success/");





            final HttpResponse<JsonNode> itemByIDResponse =
                    Unirest.get("https://kl-serenity-mcs/apis/avid.pam;version=9999;realm=3D6B717E-B061-451B-A991-9CBDEA634050/locations/items/%2FCatalogs%2FOneArchive%2FAdmin%2FSuccess%2F")
                            .header("Accept", "application/hal+json")
                            .asJson();

            final int itemByIDResponseStatus = itemByIDResponse.getStatus();

            LOG.warning(""+itemByIDResponseStatus);
        }

    }

    private static void renameFolder(String folderUrl, String newFolderName) {
        final String folderUpdateDescription = "{\"common\": {\"name\": \"" + newFolderName + "\"}}";

        final HttpResponse<JsonNode> updateFolderResponse =
                Unirest.patch(folderUrl)
                        .header("Content-Type", "application/json")
                        .body(folderUpdateDescription)
                        .asJson();

        final int updateFolderStatus = updateFolderResponse.getStatus();
        if (HttpURLConnection.HTTP_OK == updateFolderStatus) {
            final String actualNewFolderName = updateFolderResponse.getBody().getObject().getJSONObject("common").getString("name");
            if (!Objects.equals(newFolderName, actualNewFolderName)) {
                LOG.log(Level.INFO, "Folder rename was not successful; expected name {0}, actual name {1}", new Object[] {newFolderName, actualNewFolderName});
            }
        } else {
            LOG.log(Level.INFO, "Folder rename, error in resource <{0}>. -> {1}", new Object[] {folderUrl, updateFolderResponse.getBody()});
        }
    }

    private static void performFolderOperations(ItemInfo parentItem) {
        final String now = PlatformTools.nowFormatted().replaceAll("[\\-.:\\s+]", "_");
        final String newFolderName = "Java_Unirest_Example_Folder_" + now;
        final String newFolderDescription = "{\"common\": {\"name\": \"" + newFolderName + "\"}}";

        final HttpResponse<JsonNode> createFolderResponse =
                Unirest.post(parentItem.href.toString())
                        .header("Content-Type", "application/json")
                        .body(newFolderDescription)
                        .asJson();

        final int createFolderStatus = createFolderResponse.getStatus();
        if (HttpURLConnection.HTTP_OK == createFolderStatus) {
            String urlCreatedFolder = "";
            try {
                urlCreatedFolder = createFolderResponse.getBody().getObject().getJSONObject("_links").getJSONObject("self").getString("href");
                renameFolder(urlCreatedFolder, newFolderName + "_REN");
            } finally {
                final HttpResponse<JsonNode> deleteFolderResponse = Unirest.delete(urlCreatedFolder).asJson();
                final int code = deleteFolderResponse.getStatus();
                if (HttpURLConnection.HTTP_NO_CONTENT != code) {
                    LOG.log(Level.INFO, "Folder deletion, error in resource <{0}>. -> {1}", new Object[] {urlCreatedFolder, deleteFolderResponse.getBody()});
                }
            }
        } else {
            LOG.log(Level.INFO, "Folder creation, error in resource <{0}>. -> {1}", new Object[] {parentItem.href, createFolderResponse.getBody()});
        }
    }

    public static void main(String[] args) throws Exception {
        if (5 != args.length) {
            LOG.log(Level.INFO, "Usage: {0} <apidomain> <httpbasicauthstring> <servicetype> <serviceversion> <realm>", FolderOperationsUnirest.class.getSimpleName());
        } else {
            final String apiDomain = args[0];
            final String httpBasicAuthString = args[1];
            final String serviceType = args[2];
            final String serviceVersion = args[3];
            final String realm = args[4];

            final AuthorizationResponse authorizationResponse = PlatformTools.authorize(apiDomain, httpBasicAuthString);
            if (authorizationResponse.getLoginResponse().map(HttpResponse::isSuccess).orElse(false)) {
                /// Query CTMS Registry:
                final String registryServiceVersion = "0";
                final String locationsUriTemplate = String.format("https://%s/apis/%s;version=%s;realm=%s/locations", apiDomain, serviceType, serviceVersion, realm);
                final List<String> locationsUriTemplates = PlatformTools.findInRegistry(apiDomain, Collections.singletonList(serviceType), registryServiceVersion, "loc:locations", locationsUriTemplate);
                final String urlLocations = locationsUriTemplates.get(0);

                //performItemOperations(urlLocations);

                try {
                    /// Check presence of the locations resource and continue with HATEOAS:
                    final HttpResponse<JsonNode> locationsResponse =
                            Unirest.get(urlLocations)
                                    .header("Accept", "application/hal+json")
                                    .asJson();

                    final int locationsStatus = locationsResponse.getStatus();
                    if (HttpURLConnection.HTTP_OK == locationsStatus) {
                        /// Get the root folder item:
                        final String urlRootItem = locationsResponse.getBody().getObject().getJSONObject("_links").getJSONObject("loc:root-item").getString("href");
                        final URL itemURL
                                = UriBuilder
                                .fromUri(urlRootItem)
                                .path("1")
                                .build()
                                .toURL();

                        // !!
                        // The MAM Connectivity Toolkit Connector does always embed all direct items of a folder. For other
                        // service types, the query parameter embed=asset must be added if necessary.
                        // E.g. resulting in => https://$apiDomain/apis/$serviceType;version=0;realm=$realm/locations/folders?embed=asset
                        // !!

                        final ItemInfo rootItem = new ItemInfo(null, null, 0, itemURL, true);
//                        performFolderOperations(rootItem);

                    } else {
                        LOG.log(Level.INFO, "Resource <{0}> not found. -> {1}", new Object[] {urlLocations, locationsResponse.getBody()});
                    }
                } catch (final Exception exception) {
                    LOG.log(Level.SEVERE, "failure", exception);
                } finally {
                    PlatformTools.logout(apiDomain);
                }
            } else {
                LOG.log(Level.INFO, "Authorization failed.");
            }

            LOG.log(Level.INFO, "End");
        }
    }
}

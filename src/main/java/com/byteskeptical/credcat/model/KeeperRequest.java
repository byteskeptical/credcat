package com.byteskeptical.credcat.model;

import java.util.List;

/**
 * Service payload format.
 */
public class KeeperRequest {
    private String clientKey;
    private String config;
    private String saveLocation;
    private List<String> titles;
    private List<String> uids;

    /**
     * Gets the configuration path.
     *
     * @return The configuration path.
     */
    public String getConfig() {
        return config;
    }

    /**
     * Sets the configuration path.
     *
     * @param config The configuration path to set.
     */
    public void setConfig(String config) {
        this.config = config;
    }

    /**
     * Retrieves the client key.
     *
     * @return The client key.
     */
    public String getClientKey() {
        return clientKey;
    }

    /**
     * Sets the client key.
     *
     * @param clientKey The client key to set.
     */
    public void setClientKey(String clientKey) {
        this.clientKey = clientKey;
    }

    /**
     * Gets the save location for downloaded files.
     *
     * @return The file save location.
     */
    public String getSaveLocation() {
        return saveLocation;
    }

    /**
     * Sets the save location for downloaded files.
     *
     * @param saveLocation The location to save downloaded files too.
     */
    public void setSaveLocation(String saveLocation) {
        this.saveLocation = saveLocation;
    }

    /**
     * Gets the list of titles to search for.
     *
     * @return The list of titles cooresponding to records for lookup.
     */
    public List<String> getTitles() {
        return titles;
    }

    /**
     * Sets the list of titles to preform a record search on.
     *
     * @param titles A list with titles to search records for.
     */
    public void setTitles(List<String> titles) {
        this.titles = titles;
    }

    /**
     * Gets the list of uid(s) to search for.
     *
     * @return The list of uid(s) cooresponding to records for lookup.
     */
    public List<String> getUids() {
        return uids;
    }

    /**
     * Sets the list of uid(s) to preform a record search on.
     *
     * @param uids A list with uid(s) to search records for.
     */
    public void setUids(List<String> uids) {
        this.uids = uids;
    }
}

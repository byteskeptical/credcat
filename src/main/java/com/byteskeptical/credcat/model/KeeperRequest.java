package com.byteskeptical.credcat.model;

import java.util.List;

/**
 * Service payload format.
 */
public class KeeperRequest {
    private String clientKey;
    private String config;
    private String configName;
    private String fileTransport;
    private String saveLocation;
    private List<String> titles;
    private List<String> uids;

    /**
     * Gets the configuration path or literal.
     *
     * @return The configuration value.
     */
    public String getConfig() {
        return config;
    }

    /**
     * Sets the configuration path or literal.
     *
     * @param config The configuration value to set.
     */
    public void setConfig(String config) {
        this.config = config;
    }

    /**
     * Gets the symbolic config name to look up (e.g. "dev").
     *
     * @return The config name.
     */
    public String getConfigName() {
        return configName;
    }

    /**
     * Sets the symbolic config name to look up.
     *
     * @param configName The config name to set.
     */
    public void setConfigName(String configName) {
        this.configName = configName;
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
     * Gets the per-request file transport override (DISK, INLINE, NONE).
     *
     * @return The file transport value.
     */
    public String getFileTransport() {
        return fileTransport;
    }

    /**
     * Sets the per-request file transport override.
     *
     * @param fileTransport The file transport value to set.
     */
    public void setFileTransport(String fileTransport) {
        this.fileTransport = fileTransport;
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

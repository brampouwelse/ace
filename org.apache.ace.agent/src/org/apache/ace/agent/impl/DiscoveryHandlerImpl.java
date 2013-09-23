/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.ace.agent.impl;

import static org.apache.ace.agent.AgentConstants.CONFIG_DISCOVERY_CHECKING;
import static org.apache.ace.agent.AgentConstants.CONFIG_DISCOVERY_SERVERURLS;
import static org.apache.ace.agent.AgentConstants.EVENT_AGENT_CONFIG_CHANGED;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ace.agent.DiscoveryHandler;
import org.apache.ace.agent.EventListener;

/**
 * Default {@link DiscoveryHandler} implementation that reads the serverURL(s) from the configuration using key
 * {@link CONFIG_DISCOVERY_SERVERURLS}.
 */
public class DiscoveryHandlerImpl extends ComponentBase implements DiscoveryHandler, EventListener {

    /*
     * The caching logic in this code is not strictly thread-safe. It does not need to be considering the use-case and
     * the fact that it is eventually correct. Therefore the implementation is optimized for simplicity, performance and
     * garbage reduction in the performance critical path.
     */

    private static class CheckedURL {

        private final URL m_url;
        private final long m_cacheTime;
        private volatile long m_timestamp = 0l;
        private volatile boolean m_available = false;

        public CheckedURL(URL url, long cacheTime) {
            m_url = url;
            m_cacheTime = cacheTime;
        }

        public void setAvailable(boolean value) {
            m_available = value;
            m_timestamp = System.currentTimeMillis();
        }

        public boolean isAvailable() {
            return m_available;
        }

        public boolean isRecentlyChecked() {
            return m_timestamp > (System.currentTimeMillis() - m_cacheTime);
        }

        public URL getURL() {
            return m_url;
        }
    }

    private static final String DEFAULT_SERVER_URL = "http://localhost:8080";
    private static final boolean DEFAULT_CHECK_SERVER_URLS = true;
    private static final long DEFAULT_CACHE_MILLISECONDS = 30000;
    private final Map<String, CheckedURL> m_checkedURLs = new HashMap<String, CheckedURL>();

    private volatile List<String> m_serverURLs = Arrays.asList(DEFAULT_SERVER_URL);
    private volatile boolean m_checkURLs = DEFAULT_CHECK_SERVER_URLS;

    public DiscoveryHandlerImpl() {
        super("discovery");
    }

    @Override
    protected void onInit() throws Exception {
        getEventsHandler().addListener(this);
    }

    @Override
    protected void onStop() throws Exception {
        getEventsHandler().removeListener(this);
        m_checkedURLs.clear();
    }

    @Override
    public void handle(String topic, Map<String, String> payload) {
        if (!EVENT_AGENT_CONFIG_CHANGED.equals(topic)) {
            return;
        }

        List<String> serverURLs = new ArrayList<String>();
        String urlsValue = payload.get(CONFIG_DISCOVERY_SERVERURLS);
        if (urlsValue == null || "".equals(urlsValue.trim())) {
            serverURLs.addAll(Arrays.asList(DEFAULT_SERVER_URL));
        }
        else {
            String[] urls = urlsValue.trim().split("\\s*,\\s*");
            serverURLs.addAll(Arrays.asList(urls));
        }
        m_serverURLs = serverURLs;

        String checkingValue = payload.get(CONFIG_DISCOVERY_CHECKING);
        m_checkURLs = Boolean.parseBoolean(checkingValue);

        logDebug("Config changed: urls: %s, checking: %s", urlsValue, checkingValue);
        m_checkedURLs.clear();
    }

    /**
     * Returns the first available URL from a the ordered list of the configured server URLs. If the
     * {@link CONFIG_DISCOVERY_CHECKING} flag is set a connection is opened to test whether a serverURL is available
     * before it is returned.
     * 
     * @return a (valid) server URL, or <code>null</code> in case no server URL was valid.
     */
    @Override
    public URL getServerUrl() {

        List<String> serverURLs = m_serverURLs; // local reference
        boolean checkURLs = m_checkURLs; // local value

        URL url = null;
        for (String urlValue : serverURLs) {
            url = getURL(urlValue, checkURLs);
            if (url != null) {
                break;
            }
        }
        if (url == null) {
            logWarning("No valid server URL discovered?!");
        }
        return url;
    }

    private URL getURL(String serverURL, boolean checkURL) {

        try {
            if (!checkURL) {
                return new URL(serverURL);
            }

            CheckedURL checkedURL = m_checkedURLs.get(serverURL);
            if (checkedURL == null) {
                checkedURL = new CheckedURL(new URL(serverURL), DEFAULT_CACHE_MILLISECONDS);
                m_checkedURLs.put(serverURL, checkedURL);
            }
            else {
                if (checkedURL.isRecentlyChecked()) {
                    if (checkedURL.isAvailable()) {
                        logDebug("Returning cached serverURL: %s", serverURL);
                        return checkedURL.getURL();
                    }
                    else {
                        logDebug("Ignoring blacklisted serverURL: %s", serverURL);
                        return null;
                    }
                }
            }

            try {
                tryConnect(checkedURL.m_url);
                logDebug("Succesfully connected to serverURL: %s", serverURL);
                checkedURL.setAvailable(true);
                return checkedURL.getURL();
            }
            catch (IOException e) {
                logWarning("Blacklisting unavailable serverURL: %s", serverURL);
                checkedURL.setAvailable(false);
                return null;
            }

        }
        catch (MalformedURLException e) {
            logWarning("Ignoring invalid/malformed serverURL: %s", serverURL);
            return null;
        }
    }

    private void tryConnect(URL serverURL) throws IOException {
        URLConnection connection = null;
        try {
            connection = getConnectionHandler().getConnection(serverURL);
            connection.connect();
        }
        finally {
            if (connection instanceof HttpURLConnection) {
                ((HttpURLConnection) connection).disconnect();
            }
        }
    }
}

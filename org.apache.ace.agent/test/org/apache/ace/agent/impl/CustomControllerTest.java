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

import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.notNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.SortedSet;
import java.util.TreeSet;

import org.apache.ace.agent.AgentControl;
import org.apache.ace.agent.DeploymentHandler;
import org.apache.ace.agent.DownloadHandle;
import org.apache.ace.agent.DownloadResult;
import org.apache.ace.agent.DownloadState;
import org.apache.ace.agent.testutil.BaseAgentTest;
import org.osgi.framework.Version;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

/**
 * Testing {@link CustomController}.
 */
public class CustomControllerTest extends BaseAgentTest {

    Version m_version1 = Version.parseVersion("1.0.0");
    Version m_version2 = Version.parseVersion("2.0.0");
    Version m_version3 = Version.parseVersion("3.0.0");
    SortedSet<Version> m_availableVersions = new TreeSet<Version>();

    File m_workDir;
    File m_dummyFile;
    URL m_dummyFileUrl;
    InputStream m_dummyInputStream;

    AgentControl m_agentControl;
    AgentContextImpl m_agentContext;

    @BeforeTest
    public void setUpOnceAgain() throws Exception {

        m_availableVersions.add(m_version1);
        m_availableVersions.add(m_version2);
        m_availableVersions.add(m_version3);

        m_dummyFile = File.createTempFile("mock", ".txt");
        m_dummyFile.deleteOnExit();
        m_dummyFileUrl = m_dummyFile.toURI().toURL();

        m_workDir = new File(m_dummyFile.getParentFile(), "test-" + System.currentTimeMillis());
        m_workDir.mkdir();
    }

    @BeforeMethod
    public void setUpAgain() throws Exception {

        m_dummyInputStream = new FileInputStream(m_dummyFile);

        DownloadResult downloadResult = addTestMock(DownloadResult.class);
        expect(downloadResult.getState()).andReturn(DownloadState.SUCCESSFUL).anyTimes();
        expect(downloadResult.getInputStream()).andReturn(m_dummyInputStream).anyTimes();

        DownloadHandle downloadHandle = addTestMock(DownloadHandle.class);
        expect(downloadHandle.start()).andReturn(downloadHandle).anyTimes();
        expect(downloadHandle.result()).andReturn(downloadResult).anyTimes();

        DeploymentHandler deploymentHandler = addTestMock(DeploymentHandler.class);
        expect(deploymentHandler.getInstalledVersion()).andReturn(m_version2).anyTimes();
        expect(deploymentHandler.getAvailableVersions()).andReturn(m_availableVersions).anyTimes();
        expect(deploymentHandler.getDownloadHandle(eq(m_version3), eq(true))).andReturn(downloadHandle).once();
        deploymentHandler.deployPackage(notNull(InputStream.class));
        expectLastCall().once();

        m_agentContext = mockAgentContext();
        m_agentContext.setHandler(DeploymentHandler.class, deploymentHandler);
        replayTestMocks();
        
        m_agentContext.start();
        m_agentControl = new AgentControlImpl(m_agentContext);
    }

    @AfterMethod
    public void tearDownOnceAgain() throws Exception {
        m_dummyInputStream.close();
        m_agentContext.stop();
        verifyTestMocks();
        clearTestMocks();
    }

    @Test
    public void testDowlownloading() throws Exception {

        Version current = m_agentControl.getDeploymentHandler().getInstalledVersion();
        Version highest = m_agentControl.getDeploymentHandler().getAvailableVersions().last();
        if (highest.compareTo(current) > 0) {

            DownloadHandle handle = m_agentControl.getDeploymentHandler().getDownloadHandle(highest, true);
            DownloadResult result = handle.start().result();

            if (result.getState() == DownloadState.SUCCESSFUL) {
                InputStream inputStream = result.getInputStream();
                try {
                    m_agentControl.getDeploymentHandler().deployPackage(inputStream);
                }
                finally {
                    inputStream.close();
                }
            }
        }
    }
}

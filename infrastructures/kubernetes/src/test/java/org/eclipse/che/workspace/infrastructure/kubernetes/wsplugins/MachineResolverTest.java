/*
 * Copyright (c) 2012-2018 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.che.workspace.infrastructure.kubernetes.wsplugins;

import static com.google.common.collect.ImmutableMap.of;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static org.eclipse.che.api.core.model.workspace.config.MachineConfig.MEMORY_LIMIT_ATTRIBUTE;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

import io.fabric8.kubernetes.api.model.Container;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.che.api.core.model.workspace.config.ServerConfig;
import org.eclipse.che.api.workspace.server.model.impl.ServerConfigImpl;
import org.eclipse.che.api.workspace.server.model.impl.VolumeImpl;
import org.eclipse.che.api.workspace.server.spi.InfrastructureException;
import org.eclipse.che.api.workspace.server.spi.environment.InternalMachineConfig;
import org.eclipse.che.api.workspace.server.wsplugins.model.CheContainer;
import org.eclipse.che.api.workspace.server.wsplugins.model.ChePluginEndpoint;
import org.eclipse.che.api.workspace.server.wsplugins.model.Volume;
import org.eclipse.che.api.workspace.shared.Constants;
import org.eclipse.che.commons.lang.Pair;
import org.eclipse.che.workspace.infrastructure.kubernetes.util.Containers;
import org.eclipse.che.workspace.infrastructure.kubernetes.util.KubernetesSize;
import org.mockito.testng.MockitoTestNGListener;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

/** @author Oleksandr Garagatyi */
@Listeners(MockitoTestNGListener.class)
public class MachineResolverTest {

  private static final String DEFAULT_MEM_LIMIT = "100001";
  private static final String PLUGIN_ID = "testplugin";
  private static final String PROJECTS_ENV_VAR = "env_with_with_location_of_projects";
  private static final String PROJECTS_MOUNT_PATH = "/wherever/i/may/roam";

  private List<ChePluginEndpoint> endpoints;
  private Map<String, String> wsAttributes;
  private CheContainer cheContainer;
  private Container container;
  private MachineResolver resolver;

  @BeforeMethod
  public void setUp() {
    endpoints = new ArrayList<>();
    cheContainer = new CheContainer();
    container = new Container();
    wsAttributes = new HashMap<>();
    resolver =
        new MachineResolver(
            PLUGIN_ID,
            new Pair<>(PROJECTS_ENV_VAR, PROJECTS_MOUNT_PATH),
            container,
            cheContainer,
            DEFAULT_MEM_LIMIT,
            endpoints,
            wsAttributes);
  }

  @Test
  public void shouldSetVolumesInMachineConfig() throws InfrastructureException {
    List<Volume> sidecarVolumes =
        asList(
            new Volume().name("vol1").mountPath("/path1"),
            new Volume().name("vol2").mountPath("/path2"));
    cheContainer.setVolumes(sidecarVolumes);
    Map<String, Object> expected = of("vol1", volume("/path1"), "vol2", volume("/path2"));

    InternalMachineConfig machineConfig = resolver.resolve();

    assertEquals(machineConfig.getVolumes(), expected);
  }

  @Test(dataProvider = "serverProvider")
  public void shouldSetServersInMachineConfig(
      List<ChePluginEndpoint> containerEndpoints, Map<String, ServerConfig> expected)
      throws InfrastructureException {
    endpoints.addAll(containerEndpoints);

    InternalMachineConfig machineConfig = resolver.resolve();

    assertEquals(machineConfig.getServers(), expected);
  }

  @DataProvider
  public static Object[][] serverProvider() {
    return new Object[][] {
      // default minimal case
      {
        asList(endpt("endp1", 8080), endpt("endp2", 10000)),
        of("endp1", server(8080), "endp2", server(10000))
      },
      // case with publicity setting
      {
        asList(endpt("endp1", 8080, false), endpt("endp2", 10000, true)),
        of("endp1", server(8080, false), "endp2", server(10000, true))
      },
      // case with protocol attribute
      {
        asList(endptPrtc("endp1", 8080, "http"), endptPrtc("endp2", 10000, "ws")),
        of("endp1", serverPrtc(8080, "http"), "endp2", serverPrtc(10000, "ws"))
      },
      // case with path attribute
      {
        asList(endptPath("endp1", 8080, "/"), endptPath("endp2", 10000, "/some/thing")),
        of("endp1", serverPath(8080, "/"), "endp2", serverPath(10000, "/some/thing"))
      },
      // case with other attributes
      {
        asList(
            endpt("endp1", 8080, of("a1", "v1")),
            endpt("endp2", 10000, of("a2", "v1", "a3", "v3"))),
        of(
            "endp1",
            server(8080, of("a1", "v1")),
            "endp2",
            server(10000, of("a2", "v1", "a3", "v3")))
      },
    };
  }

  @Test
  public void shouldSetDefaultMemLimitIfSidecarDoesNotHaveOne() throws InfrastructureException {
    InternalMachineConfig machineConfig = resolver.resolve();

    assertEquals(machineConfig.getAttributes().get(MEMORY_LIMIT_ATTRIBUTE), DEFAULT_MEM_LIMIT);
  }

  @Test(dataProvider = "memoryAttributeProvider")
  public void shouldSetMemoryLimitOfASidecarIfCorrespondingWSConfigAttributeIsSet(
      String attributeValue, String expectedMemLimit) throws InfrastructureException {
    wsAttributes.put(
        format(Constants.SIDECAR_MEMORY_LIMIT_ATTR_TEMPLATE, PLUGIN_ID), attributeValue);

    InternalMachineConfig machineConfig = resolver.resolve();

    assertEquals(machineConfig.getAttributes().get(MEMORY_LIMIT_ATTRIBUTE), expectedMemLimit);
  }

  @DataProvider
  public static Object[][] memoryAttributeProvider() {
    return new Object[][] {
      {"", DEFAULT_MEM_LIMIT},
      {null, DEFAULT_MEM_LIMIT},
      {"100Ki", toBytesString("100Ki")},
      {"1M", toBytesString("1M")},
      {"10Gi", toBytesString("10Gi")},
    };
  }

  @Test
  public void shouldOverrideMemoryLimitOfASidecarIfCorrespondingWSConfigAttributeIsSet()
      throws InfrastructureException {
    String attributeValue = "300Mi";
    String expectedMemLimit = toBytesString(attributeValue);
    Containers.addRamLimit(container, 123456789);
    wsAttributes.put(
        format(Constants.SIDECAR_MEMORY_LIMIT_ATTR_TEMPLATE, PLUGIN_ID), attributeValue);

    InternalMachineConfig machineConfig = resolver.resolve();

    assertEquals(machineConfig.getAttributes().get(MEMORY_LIMIT_ATTRIBUTE), expectedMemLimit);
  }

  @Test
  public void shouldNotSetMemLimitAttributeIfLimitIsInContainer() throws InfrastructureException {
    Containers.addRamLimit(container, 123456789);

    InternalMachineConfig machineConfig = resolver.resolve();

    assertNull(machineConfig.getAttributes().get(MEMORY_LIMIT_ATTRIBUTE));
  }

  @Test(expectedExceptions = InfrastructureException.class)
  public void shouldRefuseToMountProjectsManually() throws InfrastructureException {
    cheContainer.setMountSources(false);
    Volume volume = new Volume();
    volume.setName(Constants.PROJECTS_VOLUME_NAME);
    volume.setMountPath("anything, like");
    cheContainer.getVolumes().add(volume);

    resolver.resolve();
  }

  @Test
  public void shouldAddProjectMountPointWhenMountSources() throws InfrastructureException {
    cheContainer.setMountSources(true);

    InternalMachineConfig config = resolver.resolve();

    assertEquals(1, config.getVolumes().size());
    assertEquals(
        PROJECTS_MOUNT_PATH, config.getVolumes().get(Constants.PROJECTS_VOLUME_NAME).getPath());
  }

  private static String toBytesString(String k8sMemorySize) {
    return Long.toString(KubernetesSize.toBytes(k8sMemorySize));
  }

  private static ChePluginEndpoint endptPath(String name, int port, String path) {
    return new ChePluginEndpoint().name(name).targetPort(port).attributes(of("path", path));
  }

  private static ChePluginEndpoint endptPrtc(String name, int port, String protocol) {
    return new ChePluginEndpoint().name(name).targetPort(port).attributes(of("protocol", protocol));
  }

  private static ChePluginEndpoint endpt(String name, int port, boolean isPublic) {
    return new ChePluginEndpoint().name(name).targetPort(port).setPublic(isPublic);
  }

  private static ChePluginEndpoint endpt(String name, int port, Map<String, String> attributes) {
    return new ChePluginEndpoint().name(name).targetPort(port).attributes(attributes);
  }

  private static ChePluginEndpoint endpt(String name, int port) {
    return new ChePluginEndpoint().name(name).targetPort(port);
  }

  private static ServerConfigImpl server(int port) {
    return server(port, false);
  }

  private static ServerConfig server(int port, Map<String, String> attributes) {
    ServerConfigImpl server = server(port);
    server.getAttributes().putAll(attributes);
    return server;
  }

  private static ServerConfigImpl serverPath(int port, String path) {
    return server(port).withPath(path);
  }

  private static ServerConfigImpl serverPrtc(int port, String protocol) {
    return server(port).withProtocol(protocol);
  }

  private static ServerConfigImpl server(int port, boolean external) {
    return new ServerConfigImpl()
        .withPort(port + "/tcp")
        .withAttributes(of("internal", Boolean.toString(!external)));
  }

  private org.eclipse.che.api.core.model.workspace.config.Volume volume(String mountPath) {
    return new VolumeImpl().withPath(mountPath);
  }
}

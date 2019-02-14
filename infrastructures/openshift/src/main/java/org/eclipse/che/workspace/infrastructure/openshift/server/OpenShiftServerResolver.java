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
package org.eclipse.che.workspace.infrastructure.openshift.server;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.openshift.api.model.Route;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.eclipse.che.api.workspace.server.model.impl.ServerImpl;
import org.eclipse.che.workspace.infrastructure.kubernetes.Annotations;
import org.eclipse.che.workspace.infrastructure.kubernetes.server.KubernetesServerResolver;
import org.eclipse.che.workspace.infrastructure.kubernetes.server.RuntimeServerBuilder;

/**
 * Helps to resolve {@link ServerImpl servers} by machine name according to specified {@link Route
 * routes} and {@link Service services}.
 *
 * <p>Objects annotations are used to check if {@link Service service} or {@link Route route}
 * exposes the specified machine servers.
 *
 * @author Sergii Leshchenko
 * @author Alexander Garagatyi
 * @see OpenShiftExternalServerExposer
 * @see Annotations
 */
public class OpenShiftServerResolver extends KubernetesServerResolver {

  private final Multimap<String, Route> routes;

  public OpenShiftServerResolver(List<Service> services, List<Route> routes) {
    super(services, Collections.emptyList());

    this.routes = ArrayListMultimap.create();
    for (Route route : routes) {
      String machineName =
          Annotations.newDeserializer(route.getMetadata().getAnnotations()).machineName();
      this.routes.put(machineName, route);
    }
  }

  @Override
  protected void fillExternalServers(String machineName, Map<String, ServerImpl> servers) {
    routes.get(machineName).forEach(route -> fillRouteServers(route, servers));
  }

  private void fillRouteServers(Route route, Map<String, ServerImpl> servers) {
    Annotations.newDeserializer(route.getMetadata().getAnnotations())
        .servers()
        .forEach(
            (name, config) ->
                servers.put(
                    name,
                    new RuntimeServerBuilder()
                        .protocol(config.getProtocol())
                        .host(route.getSpec().getHost())
                        .path(config.getPath())
                        .attributes(config.getAttributes())
                        .targetPort(config.getPort())
                        .build()));
  }
}

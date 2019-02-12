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
package org.eclipse.che.api.deploy;

import com.google.inject.Binder;
import com.google.inject.Module;
import com.google.inject.name.Names;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import java.util.concurrent.ExecutorService;
import org.eclipse.che.api.deploy.CheMajorWebSocketEndpoint.CheMajorWebSocketEndpointExecutorServiceProvider;
import org.eclipse.che.core.metrics.ExecutorServiceMetricsProvider;

public class MetricsOverrideBinding implements Module {
  @Override
  public void configure(Binder binder) {
    if (Boolean.valueOf(System.getenv("CHE_METRICS_ENABLED"))) {
      binder
          .bind(ExecutorService.class)
          .annotatedWith(Names.named("che.core.jsonrpc.major_executor"))
          .toProvider(
              new ExecutorServiceMetricsProvider(
                  "che.core.jsonrpc.major_executor",
                  binder.getProvider(CheMajorWebSocketEndpointExecutorServiceProvider.class),
                  binder.getProvider(PrometheusMeterRegistry.class)));

      binder
          .bind(ExecutorService.class)
          .annotatedWith(Names.named("che.core.jsonrpc.minor_executor"))
          .toProvider(
              new ExecutorServiceMetricsProvider(
                  "che.core.jsonrpc.minor_executor",
                  binder.getProvider(
                      CheMinorWebSocketEndpoint.CheMinorWebSocketEndpointExecutorServiceProvider
                          .class),
                  binder.getProvider(PrometheusMeterRegistry.class)));
    }
  }
}

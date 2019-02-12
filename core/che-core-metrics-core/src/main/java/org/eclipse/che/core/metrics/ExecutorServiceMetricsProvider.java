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
package org.eclipse.che.core.metrics;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import java.util.concurrent.ExecutorService;
import javax.inject.Provider;
import javax.inject.Singleton;

@Singleton
public class ExecutorServiceMetricsProvider implements Provider<ExecutorService> {

  private ExecutorService instance;

  private final String executorServiceName;

  private final Provider<? extends Provider<ExecutorService>> provider;

  private final Provider<PrometheusMeterRegistry> meterRegistryProvider;

  public ExecutorServiceMetricsProvider(
      String executorServiceName,
      Provider<? extends Provider<ExecutorService>> provider,
      Provider<PrometheusMeterRegistry> meterRegistryProvider) {
    this.executorServiceName = executorServiceName;
    this.provider = provider;
    this.meterRegistryProvider = meterRegistryProvider;
  }

  @Override
  public ExecutorService get() {
    if (instance == null) {
      instance =
          ExecutorServiceMetrics.monitor(
              meterRegistryProvider.get(), provider.get().get(), executorServiceName, Tags.empty());
    }
    return instance;
  }
}

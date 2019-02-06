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
import com.google.inject.multibindings.Multibinder;
import com.google.inject.name.Names;
import java.util.concurrent.ExecutorService;
import org.eclipse.che.api.core.jsonrpc.commons.RequestProcessorConfigurator;

public class CheJsonRpcWebSocketConfigurationModule implements Module {
  @Override
  public void configure(Binder binder) {
    binder
        .bind(ExecutorService.class)
        .annotatedWith(Names.named("che.core.jsonrpc.major_executor"))
        .toProvider(
            CheMajorWebSocketEndpoint.CheMajorWebSocketEndpointExecutorServiceProvider.class);

    binder
        .bind(ExecutorService.class)
        .annotatedWith(Names.named("che.core.jsonrpc.minor_executor"))
        .toProvider(
            CheMinorWebSocketEndpoint.CheMinorWebSocketEndpointExecutorServiceProvider.class);

    Multibinder<RequestProcessorConfigurator.Configuration> configurationMultibinder =
        Multibinder.newSetBinder(binder, RequestProcessorConfigurator.Configuration.class);
    configurationMultibinder
        .addBinding()
        .to(CheMajorWebSocketEndpoint.CheMajorWebSocketEndpointConfiguration.class);
    configurationMultibinder
        .addBinding()
        .to(CheMinorWebSocketEndpoint.CheMinorWebSocketEndpointConfiguration.class);
  }
}

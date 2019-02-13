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
package org.eclipse.che.workspace.infrastructure.openshift.environment;

import static java.lang.String.format;

import com.google.common.annotations.VisibleForTesting;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.openshift.api.model.DeploymentConfig;
import io.fabric8.openshift.api.model.Route;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import org.eclipse.che.api.core.ValidationException;
import org.eclipse.che.api.core.model.workspace.Warning;
import org.eclipse.che.api.installer.server.InstallerRegistry;
import org.eclipse.che.api.workspace.server.spi.InfrastructureException;
import org.eclipse.che.api.workspace.server.spi.environment.*;
import org.eclipse.che.commons.annotation.Nullable;
import org.eclipse.che.workspace.infrastructure.kubernetes.Names;
import org.eclipse.che.workspace.infrastructure.kubernetes.util.Containers;
import org.eclipse.che.workspace.infrastructure.openshift.OpenShiftClientFactory;

/**
 * Parses {@link InternalEnvironment} into {@link OpenShiftEnvironment}.
 *
 * @author Sergii Leshchenko
 */
public class OpenShiftEnvironmentFactory extends InternalEnvironmentFactory<OpenShiftEnvironment> {

  private final OpenShiftClientFactory clientFactory;
  private final OpenShiftEnvironmentValidator envValidator;
  private final MemoryAttributeProvisioner memoryProvisioner;

  @Inject
  public OpenShiftEnvironmentFactory(
      InstallerRegistry installerRegistry,
      RecipeRetriever recipeRetriever,
      MachineConfigsValidator machinesValidator,
      OpenShiftClientFactory clientFactory,
      OpenShiftEnvironmentValidator envValidator,
      MemoryAttributeProvisioner memoryProvisioner) {
    super(installerRegistry, recipeRetriever, machinesValidator);
    this.clientFactory = clientFactory;
    this.envValidator = envValidator;
    this.memoryProvisioner = memoryProvisioner;
  }

  @Override
  protected OpenShiftEnvironment doCreate(
      @Nullable InternalRecipe recipe,
      Map<String, InternalMachineConfig> machines,
      List<Warning> sourceWarnings)
      throws InfrastructureException, ValidationException {
    checkNotNull(recipe, "Null recipe is not supported by openshift environment factory");
    List<Warning> warnings = new ArrayList<>();
    if (sourceWarnings != null) {
      warnings.addAll(sourceWarnings);
    }
    String content = recipe.getContent();
    String contentType = recipe.getContentType();
    checkNotNull(contentType, "OpenShift Recipe content type should not be null");

    switch (contentType) {
      case "application/x-yaml":
      case "text/yaml":
      case "text/x-yaml":
        break;
      default:
        throw new ValidationException(
            "Provided environment recipe content type '"
                + contentType
                + "' is unsupported. Supported values are: "
                + "application/x-yaml, text/yaml, text/x-yaml");
    }

    final List<HasMetadata> list;
    try {
      // Behavior:
      // - If `content` is a Kubernetes List, load().get() will get the objects in that list
      // - If `content` is an OpenShift template, load().get() will get the objects in the template
      //   with parameters substituted (e.g. with default values).
      list = clientFactory.create().load(new ByteArrayInputStream(content.getBytes())).get();
    } catch (KubernetesClientException e) {
      // KubernetesClient wraps the error when a JsonMappingException occurs so we need the cause
      String message = e.getCause() == null ? e.getMessage() : e.getCause().getMessage();
      if (message.contains("\n")) {
        // Clean up message if it comes from JsonMappingException. Format is e.g.
        // `No resource type found for:v1#Route1\n at [...]`
        message = message.split("\\n", 2)[0];
      }
      throw new ValidationException(format("Could not parse OpenShift recipe: %s", message));
    }

    Map<String, Pod> pods = new HashMap<>();
    Map<String, Deployment> deployments = new HashMap<>();
    Map<String, Service> services = new HashMap<>();
    Map<String, ConfigMap> configMaps = new HashMap<>();
    Map<String, PersistentVolumeClaim> pvcs = new HashMap<>();
    Map<String, Route> routes = new HashMap<>();
    Map<String, Secret> secrets = new HashMap<>();
    for (HasMetadata object : list) {
      checkNotNull(object.getKind(), "Environment contains object without specified kind field");
      checkNotNull(object.getMetadata(), "%s metadata must not be null", object.getKind());
      checkNotNull(object.getMetadata().getName(), "%s name must not be null", object.getKind());

      // needed because Che master namespace is set by K8s API during list loading
      object.getMetadata().setNamespace(null);

      if (object instanceof DeploymentConfig) {
        throw new ValidationException("Supporting of deployment configs is not implemented yet.");
      } else if (object instanceof Pod) {
        Pod pod = (Pod) object;
        pods.put(pod.getMetadata().getName(), pod);
      } else if (object instanceof Deployment) {
        Deployment deployment = (Deployment) object;
        deployments.put(deployment.getMetadata().getName(), deployment);
      } else if (object instanceof Service) {
        Service service = (Service) object;
        services.put(service.getMetadata().getName(), service);
      } else if (object instanceof Route) {
        Route route = (Route) object;
        routes.put(route.getMetadata().getName(), route);
      } else if (object instanceof PersistentVolumeClaim) {
        PersistentVolumeClaim pvc = (PersistentVolumeClaim) object;
        pvcs.put(pvc.getMetadata().getName(), pvc);
      } else if (object instanceof Secret) {
        Secret secret = (Secret) object;
        secrets.put(secret.getMetadata().getName(), secret);
      } else if (object instanceof ConfigMap) {
        ConfigMap configMap = (ConfigMap) object;
        configMaps.put(configMap.getMetadata().getName(), configMap);
      } else {
        throw new ValidationException(
            format(
                "Found unknown object type in recipe -- name: '%s', kind: '%s'",
                object.getMetadata().getName(), object.getKind()));
      }
    }

    addRamAttributes(machines, pods.values());

    OpenShiftEnvironment osEnv =
        OpenShiftEnvironment.builder()
            .setInternalRecipe(recipe)
            .setMachines(machines)
            .setWarnings(warnings)
            .setPods(pods)
            .setDeployments(deployments)
            .setServices(services)
            .setPersistentVolumeClaims(pvcs)
            .setSecrets(secrets)
            .setConfigMaps(configMaps)
            .setRoutes(routes)
            .build();

    envValidator.validate(osEnv);

    return osEnv;
  }

  @VisibleForTesting
  void addRamAttributes(Map<String, InternalMachineConfig> machines, Collection<Pod> pods) {
    for (Pod pod : pods) {
      for (Container container : pod.getSpec().getContainers()) {
        final String machineName = Names.machineName(pod, container);
        InternalMachineConfig machineConfig;
        if ((machineConfig = machines.get(machineName)) == null) {
          machineConfig = new InternalMachineConfig();
          machines.put(machineName, machineConfig);
        }
        memoryProvisioner.provision(
            machineConfig, Containers.getRamLimit(container), Containers.getRamRequest(container));
      }
    }
  }

  private void checkNotNull(Object object, String errorMessage) throws ValidationException {
    if (object == null) {
      throw new ValidationException(errorMessage);
    }
  }

  private void checkNotNull(Object object, String messageFmt, Object... messageArguments)
      throws ValidationException {
    if (object == null) {
      throw new ValidationException(format(messageFmt, messageArguments));
    }
  }
}

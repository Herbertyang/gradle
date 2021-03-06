/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.locking;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.gradle.api.Transformer;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.internal.artifacts.dsl.dependencies.DependencyLockingProvider;
import org.gradle.api.internal.artifacts.dsl.dependencies.DependencyLockingState;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ArtifactSet;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ValidatingArtifactsVisitor;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.DependencyGraphNode;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.RootGraphNode;
import org.gradle.api.internal.artifacts.repositories.resolver.MavenUniqueSnapshotComponentIdentifier;
import org.gradle.internal.component.local.model.LocalFileDependencyMetadata;
import org.gradle.internal.component.local.model.RootConfigurationMetadata;
import org.gradle.internal.component.model.ComponentResolveMetadata;
import org.gradle.util.CollectionUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DependencyLockingArtifactVisitor implements ValidatingArtifactsVisitor {

    private final DependencyLockingProvider dependencyLockingProvider;
    private final String configurationName;
    private Set<ModuleComponentIdentifier> allResolvedModules;
    private Set<ModuleComponentIdentifier> changingResolvedModules;
    private Set<ModuleComponentIdentifier> modulesToBeLocked;
    private Set<ModuleComponentIdentifier> extraModules;
    private DependencyLockingState dependencyLockingState;

    public DependencyLockingArtifactVisitor(String configurationName, DependencyLockingProvider dependencyLockingProvider) {
        this.configurationName = configurationName;
        this.dependencyLockingProvider = dependencyLockingProvider;
    }

    @Override
    public void startArtifacts(RootGraphNode root) {
        RootConfigurationMetadata metadata = root.getMetadata();
        dependencyLockingState = metadata.getDependencyLockingState();
        if (dependencyLockingState.mustValidateLockState()) {
            Set<ModuleComponentIdentifier> lockedModules = dependencyLockingState.getLockedDependencies();
            modulesToBeLocked = Sets.newHashSet(lockedModules);
            allResolvedModules = Sets.newHashSetWithExpectedSize(this.modulesToBeLocked.size());
            extraModules = Sets.newHashSet();
        } else {
            modulesToBeLocked = Collections.emptySet();
            allResolvedModules = Sets.newHashSet();
        }
    }

    @Override
    public void visitNode(DependencyGraphNode node) {
        boolean changing = false;
        ComponentIdentifier identifier = node.getOwner().getComponentId();
        ComponentResolveMetadata metadata = node.getOwner().getMetadata();
        if (metadata != null && metadata.isChanging()) {
            changing = true;
        }
        if (identifier instanceof ModuleComponentIdentifier) {
            ModuleComponentIdentifier id = (ModuleComponentIdentifier) identifier;
            if (identifier instanceof MavenUniqueSnapshotComponentIdentifier) {
                id = ((MavenUniqueSnapshotComponentIdentifier) id).getSnapshotComponent();
            }
            if (!id.getVersion().isEmpty()) {
                if (allResolvedModules.add(id)) {
                    if (changing) {
                        addChangingModule(id);
                    }
                    if (dependencyLockingState.mustValidateLockState()) {
                        if (!modulesToBeLocked.remove(id)) {
                            extraModules.add(id);
                        }
                    }
                }
            }
        }
    }

    private void addChangingModule(ModuleComponentIdentifier id) {
        if (changingResolvedModules == null) {
            changingResolvedModules = new HashSet<ModuleComponentIdentifier>();
        }
        changingResolvedModules.add(id);
    }

    @Override
    public void visitArtifacts(DependencyGraphNode from, DependencyGraphNode to, int artifactSetId, ArtifactSet artifacts) {
        // No-op
    }

    @Override
    public void visitArtifacts(DependencyGraphNode from, LocalFileDependencyMetadata fileDependency, int artifactSetId, ArtifactSet artifactSet) {
        // No-op
    }

    @Override
    public void finishArtifacts() {
    }

    private Set<String> getSortedDisplayNames(Set<ModuleComponentIdentifier> modules) {
        return CollectionUtils.collect(modules, new Transformer<String, ModuleComponentIdentifier>() {
            @Override
            public String transform(ModuleComponentIdentifier moduleComponentIdentifier) {
                return moduleComponentIdentifier.getDisplayName();
            }
        });
    }

    private void throwLockOutOfDateException(Set<String> notResolvedConstraints, Set<String> extraModules) {
        List<String> errors = Lists.newArrayListWithCapacity(notResolvedConstraints.size() + extraModules.size());
        for (String notResolvedConstraint : notResolvedConstraints) {
            errors.add("Did not resolve '" + notResolvedConstraint + "' which is part of the lock state");
        }
        for (String extraModule : extraModules) {
            errors.add("Resolved '" + extraModule + "' which is not part of the lock state");
        }
        throw LockOutOfDateException.createLockOutOfDateException(configurationName, errors);
    }

    public void complete() {
        if (dependencyLockingState.mustValidateLockState()) {
            if (!modulesToBeLocked.isEmpty() || !extraModules.isEmpty()) {
                throwLockOutOfDateException(getSortedDisplayNames(modulesToBeLocked), getSortedDisplayNames(extraModules));
            }
        }
        Set<ModuleComponentIdentifier> changingModules = this.changingResolvedModules == null ? Collections.<ModuleComponentIdentifier>emptySet() : this.changingResolvedModules;
        dependencyLockingProvider.persistResolvedDependencies(configurationName, allResolvedModules, changingModules);
    }
}

/**
 * Copyright (c) 2017, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.afs.ext.base;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.powsybl.afs.ProjectFile;
import com.powsybl.afs.storage.NodeId;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.iidm.import_.Importer;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.NetworkFactory;
import groovy.json.JsonOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class LocalNetworkService implements NetworkService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalNetworkService.class);

    private final class ModifiedNetwork {

        private final Network network;

        private final ScriptError scriptError;

        private final String scriptOutput;

        private ModifiedNetwork(Network network, ScriptError scriptError, String scriptOutput) {
            this.network = Objects.requireNonNull(network);
            this.scriptError = scriptError;
            this.scriptOutput = Objects.requireNonNull(scriptOutput);
        }

        private Network getNetwork() {
            return network;
        }

        private ScriptError getScriptError() {
            return scriptError;
        }

        private String getScriptOutput() {
            return scriptOutput;
        }
    }

    private final Cache<NodeId, ModifiedNetwork> networkCache;

    public LocalNetworkService() {
        networkCache = CacheBuilder.newBuilder()
                .maximumSize(50)
                .expireAfterAccess(1, TimeUnit.HOURS)
                .removalListener(notification -> LOGGER.info("Network of project case {} removed ({})", notification.getKey(), notification.getCause()))
                .build();
    }

    private ModifiedNetwork loadNetworkFromImportedCase(ImportedCase importedCase) {
        LOGGER.info("Loading network of project case {}", importedCase.getId());

        Importer importer = importedCase.getImporter();
        ReadOnlyDataSource dataSource = importedCase.getDataSource();
        Properties parameters = importedCase.getParameters();
        Network network = importer.importData(dataSource, parameters);
        return new ModifiedNetwork(network, null, "");
    }

    private ModifiedNetwork applyScript(Network network, String previousScriptOutput, ModificationScript script) {
        ScriptResult result = ScriptUtils.runScript(network, script.getScriptType(), script.readScript());
        if (result.getError() == null) {
            return new ModifiedNetwork(network, null, previousScriptOutput + result.getOutput());
        } else {
            // return an empty network
            return new ModifiedNetwork(NetworkFactory.create("error", ""), result.getError(), result.getOutput());
        }
    }

    private ModifiedNetwork loadNetworkFromVirtualCase(VirtualCase virtualCase) {
        ProjectCase baseCase = virtualCase.getCase();

        ModifiedNetwork modifiedNetwork = loadNetworkFromProjectCase(baseCase);

        if (modifiedNetwork.getScriptError() != null) {
            return modifiedNetwork;
        }

        ModificationScript script = virtualCase.getScript();

        LOGGER.info("Applying script to network of project case " + virtualCase.getId());

        return applyScript(modifiedNetwork.getNetwork(), modifiedNetwork.getScriptOutput(), script);
    }

    private ModifiedNetwork loadNetworkFromProjectCase(ProjectCase projectCase) {
        if (projectCase instanceof ImportedCase) {
            return loadNetworkFromImportedCase((ImportedCase) projectCase);
        } else if (projectCase instanceof VirtualCase) {
            return loadNetworkFromVirtualCase((VirtualCase) projectCase);
        } else {
            throw new AssertionError("ProjectCase implementation " + projectCase.getClass().getName() + " not supported");
        }
    }

    private <T extends ProjectFile & ProjectCase> ModifiedNetwork loadNetwork(T projectCase) {
        Objects.requireNonNull(projectCase);
        try {
            return networkCache.get(projectCase.getId(), () -> loadNetworkFromProjectCase(projectCase));
        } catch (ExecutionException e) {
            throw new UncheckedExecutionException(e);
        }
    }

    @Override
    public <T extends ProjectFile & ProjectCase> String queryNetwork(T projectCase, String groovyScript) {
        Objects.requireNonNull(projectCase);
        Objects.requireNonNull(groovyScript);
        ScriptResult result = ScriptUtils.runScript(getNetwork(projectCase), ScriptType.GROOVY, groovyScript);
        String json = null;
        if (result.getError() == null) {
            if (result.getValue() != null) {
                json = JsonOutput.toJson(result.getValue());
            }
        } else {
            LOGGER.error("Network query error {}", result.getError());
        }
        return json;
    }

    @Override
    public <T extends ProjectFile & ProjectCase> Network getNetwork(T projectCase) {
        return loadNetwork(projectCase).getNetwork();
    }

    @Override
    public <T extends ProjectFile & ProjectCase> ScriptError getScriptError(T projectCase) {
        return loadNetwork(projectCase).getScriptError();
    }

    @Override
    public <T extends ProjectFile & ProjectCase> String getScriptOutput(T projectCase) {
        return loadNetwork(projectCase).getScriptOutput();
    }

    @Override
    public <T extends ProjectFile & ProjectCase> void invalidateCache(T projectCase) {
        Objects.requireNonNull(projectCase);
        networkCache.invalidate(projectCase.getId());
    }
}

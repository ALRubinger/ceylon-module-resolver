/*
 * Copyright 2011 Red Hat inc. and third party contributors as noted
 * by the author tags.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.redhat.ceylon.cmr.maven;

import java.io.File;

import org.jboss.shrinkwrap.resolver.api.ResolutionException;
import org.jboss.shrinkwrap.resolver.api.maven.Maven;
import org.jboss.shrinkwrap.resolver.api.maven.MavenFormatStage;
import org.jboss.shrinkwrap.resolver.api.maven.MavenResolverSystem;
import org.jboss.shrinkwrap.resolver.api.maven.MavenStrategyStage;

import com.redhat.ceylon.cmr.api.ArtifactContext;
import com.redhat.ceylon.cmr.api.Logger;
import com.redhat.ceylon.cmr.spi.Node;

/**
 * Aether utils.
 * <p/>
 * We actually use JBoss ShrinkWrap Resolver.
 *
 * @author <a href="mailto:ales.justin@jboss.org">Ales Justin</a>
 */
public class AetherUtils {

    private Logger log;
    private String settingsXml;

    AetherUtils(Logger log) {
        this.log = log;
        settingsXml = getDefaultMavenSettings();
    }

    void overrideSettingsXml(String settingsXml) {
        this.settingsXml = settingsXml;
    }

    File findDependency(Node node) {
        final File[] files = findDependencies(node);
        return (files != null) ? files[0] : null;
    }

    File[] findDependencies(Node node) {
        final ArtifactContext ac = ArtifactContext.fromNode(node);
        if (ac == null)
            return null;

        final String name = ac.getName();
        final int p = name.contains(":") ? name.lastIndexOf(":") : name.lastIndexOf(".");
        if (p == -1) {
            return null;
        }
        final String groupId = name.substring(0, p);
        final String artifactId = name.substring(p + 1);
        final String version = ac.getVersion();

        return fetchDependencies(groupId, artifactId, version, ac.isFetchSingleArtifact());
    }

    private File[] fetchDependencies(String groupId, String artifactId, String version, boolean fetchSingleArtifact) {
        final String coordinates = groupId + ":" + artifactId + ":" + version;
        try {
            final MavenStrategyStage mss = getResolver().resolve(coordinates);
            final MavenFormatStage mfs = fetchSingleArtifact ? mss.withoutTransitivity() : mss.withTransitivity();
            return mfs.as(File.class);
        } catch (ResolutionException e) {
            log.debug("Could not resolve artifact [" + coordinates + "] : " + e);
            return null;
        }
    }

    public static String getDefaultMavenSettings() {
        String path = System.getProperty("maven.repo.local");
        if (path != null) {
            File file = new File(path, "settings.xml");
            if (file.exists())
                return file.getAbsolutePath();
        }

        path = System.getProperty("user.home");
        if (path != null) {
            File file = new File(path, ".m2/settings.xml");
            if (file.exists())
                return file.getAbsolutePath();
        }

        path = System.getenv("M2_HOME");
        if (path != null) {
            File file = new File(path, "conf/settings.xml");
            if (file.exists())
                return file.getAbsolutePath();
        }

        return "classpath:settings.xml";
    }

    private MavenResolverSystem getResolver() {
        if(settingsXml.startsWith("classpath:"))
            return Maven.configureResolver().fromClassloaderResource(settingsXml.substring(10));
        return Maven.configureResolver().fromFile(settingsXml);
    }
}

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

package com.redhat.ceylon.test.maven.test;

import java.io.File;
import java.net.URL;
import java.util.List;

import com.redhat.ceylon.cmr.api.ArtifactResult;
import com.redhat.ceylon.cmr.api.Logger;
import com.redhat.ceylon.cmr.api.Repository;
import com.redhat.ceylon.cmr.api.RepositoryManager;
import com.redhat.ceylon.cmr.impl.JULLogger;
import com.redhat.ceylon.cmr.impl.MavenRepositoryHelper;
import com.redhat.ceylon.cmr.impl.SimpleRepositoryManager;
import com.redhat.ceylon.cmr.maven.AetherContentStore;
import com.redhat.ceylon.cmr.maven.AetherRepository;
import com.redhat.ceylon.cmr.spi.StructureBuilder;
import org.junit.Assert;
import org.junit.Test;

/**
 * Aether tests.
 *
 * @author <a href="mailto:ales.justin@jboss.org">Ales Justin</a>
 */
public class AetherTestCase {

    private Logger log = new JULLogger();

    @Test
    public void testSimpleTest() throws Throwable {
        StructureBuilder structureBuilder = new AetherContentStore(log);
        Repository repository = MavenRepositoryHelper.getMavenRepository(structureBuilder);
        RepositoryManager manager = new SimpleRepositoryManager(repository, log);
        File artifact = manager.getArtifact("org.slf4j.slf4j-api", "1.6.4");
        boolean exists = false;
        try {
            Assert.assertNotNull(artifact);
            Assert.assertTrue(artifact.exists());
            exists = true;
        } finally {
            if (exists) {
                Assert.assertTrue(artifact.delete()); // delete this one
            }
        }
    }

    @Test
    public void testAether() throws Throwable {
        Repository repository = AetherRepository.createRepository(log);
        RepositoryManager manager = new SimpleRepositoryManager(repository, log);
        ArtifactResult result = manager.getArtifactResult("org.slf4j.slf4j-api", "1.6.4");
        Assert.assertNotNull(result);
        File artifact = result.artifact();
        boolean exists = false;
        try {
            Assert.assertNotNull(artifact);
            Assert.assertTrue(artifact.exists());
            exists = true;
            List<ArtifactResult> deps = result.dependencies();
            log.debug("deps = " + deps);
        } finally {
            if (exists) {
                Assert.assertTrue(artifact.delete()); // delete this one
            }
        }
    }

    @Test
    public void testAetherWithExternalSettings() throws Throwable {
        Repository repository = createAetherRepository();
        RepositoryManager manager = new SimpleRepositoryManager(repository, log);
        ArtifactResult result = manager.getArtifactResult("org.apache.camel.camel-core", "2.9.2");
        Assert.assertNotNull(result);
        Assert.assertEquals(result.name(), "org.apache.camel.camel-core");
        File artifact = result.artifact();
        boolean exists = false;
        try {
            Assert.assertNotNull(artifact);
            Assert.assertTrue(artifact.exists());
            exists = true;
            List<ArtifactResult> deps = result.dependencies();
            Assert.assertEquals(deps.size(), 1);
            Assert.assertEquals("org.slf4j:slf4j-api", deps.get(0).name());
            Assert.assertEquals("1.6.1", deps.get(0).version());
            log.debug("deps = " + deps);
        } finally {
            if (exists) {
                Assert.assertTrue(artifact.delete()); // delete this one
            }
        }
    }

    @Test
    public void testAetherWithSemiColonModule() throws Throwable {
        Repository repository = createAetherRepository();
        RepositoryManager manager = new SimpleRepositoryManager(repository, log);
        ArtifactResult result = manager.getArtifactResult("org.restlet.jse:org.restlet", "2.0.10");
        Assert.assertNotNull(result);
        File artifact = result.artifact();
        boolean exists = false;
        try {
            Assert.assertNotNull(artifact);
            Assert.assertTrue(artifact.exists());
            exists = true;
            List<ArtifactResult> deps = result.dependencies();
            Assert.assertEquals(deps.size(), 1);
            Assert.assertEquals("org.osgi:org.osgi.core", deps.get(0).name());
            Assert.assertEquals("4.0.0", deps.get(0).version());
            log.debug("deps = " + deps);
        } finally {
            if (exists) {
                Assert.assertTrue(artifact.delete()); // delete this one
            }
        }
    }

    private Repository createAetherRepository() throws Exception {
        URL settingsXml = getClass().getClassLoader().getResource("maven-settings/settings.xml");
        return AetherRepository.createRepository(log, new File(settingsXml.toURI()).getPath());
    }
}

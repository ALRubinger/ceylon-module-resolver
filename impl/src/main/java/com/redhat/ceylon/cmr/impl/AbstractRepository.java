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

package com.redhat.ceylon.cmr.impl;

import com.redhat.ceylon.cmr.api.ArtifactContext;
import com.redhat.ceylon.cmr.api.ModuleQuery;
import com.redhat.ceylon.cmr.api.ModuleQuery.Type;
import com.redhat.ceylon.cmr.api.ContentFinder;
import com.redhat.ceylon.cmr.api.ModuleSearchResult;
import com.redhat.ceylon.cmr.api.ModuleVersionQuery;
import com.redhat.ceylon.cmr.api.ModuleVersionResult;
import com.redhat.ceylon.cmr.api.ModuleResult;
import com.redhat.ceylon.cmr.api.ModuleVersionDetails;
import com.redhat.ceylon.cmr.api.ArtifactResult;
import com.redhat.ceylon.cmr.api.Repository;
import com.redhat.ceylon.cmr.api.RepositoryManager;
import com.redhat.ceylon.cmr.spi.Node;
import com.redhat.ceylon.cmr.spi.OpenNode;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Abstract repository.
 *
 * @author <a href="mailto:ales.justin@jboss.org">Ales Justin</a>
 */
public abstract class AbstractRepository implements Repository {

    private static final Comparator<? super Node> AlphabeticalNodeComparator = new Comparator<Node>(){
        @Override
        public int compare(Node a, Node b) {
            return a.getLabel().compareToIgnoreCase(b.getLabel());
        }
    };
    
    private OpenNode root;

    public AbstractRepository(OpenNode root) {
        this.root = root;
    }

    private List<String> getDefaultParentPathInternal(ArtifactContext context) {
        final String name = context.getName();
        final List<String> tokens = new ArrayList<String>();
        tokens.addAll(Arrays.asList(name.split("\\.")));
        final String version = context.getVersion();
        if (RepositoryManager.DEFAULT_MODULE.equals(name) == false && version != null)
            tokens.add(version); // add version
        return tokens;
    }

    protected List<String> getDefaultParentPath(ArtifactContext context) {
        List<String> tokens = LookupCaching.getTokens(getClass());
        if (tokens == null) {
            tokens = getDefaultParentPathInternal(context);
            if (LookupCaching.isEnabled()) {
                LookupCaching.setTokens(getClass(), tokens);
            }
        }
        return tokens;
    }

    protected static String getArtifactName(String name, String version, String suffix) {
        if (ArtifactContext.DOCS.equals(suffix))
            return ArtifactContext.DOCS;
        else if (ArtifactContext.DOCS_ZIPPED.equals(suffix))
            return ArtifactContext.DOCS_ZIPPED;
        else if (RepositoryManager.DEFAULT_MODULE.equals(name))
            return name + suffix;
        else
            return buildArtifactName(name, version, suffix);
    }

    protected static String buildArtifactName(String name, String version, String suffix) {
        return name + "-" + version + suffix;
    }

    public OpenNode getRoot() {
        return root;
    }

    public Node findParent(ArtifactContext context) {
        final List<String> tokens = getDefaultParentPath(context);
        return NodeUtils.getNode(root, tokens);
    }

    public String getArtifactName(ArtifactContext context) {
        return getArtifactName(context.getName(), context.getVersion(), context.getSuffix());
    }

    public OpenNode createParent(ArtifactContext context) {
        final List<String> tokens = getDefaultParentPath(context);
        OpenNode current = root;
        for (String token : tokens) {
            current = current.addNode(token);
        }
        return current;
    }

    protected abstract ArtifactResult getArtifactResultInternal(RepositoryManager manager, Node node);

    public ArtifactResult getArtifactResult(RepositoryManager manager, Node node) {
        return (node != null) ? getArtifactResultInternal(manager, node) : null;
    }

    @Override
    public int hashCode() {
        return root.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Repository == false)
            return false;

        final Repository daca = (Repository) obj;
        return root.equals(daca.getRoot());
    }

    @Override
    public String toString() {
        return "Repository (" + getClass().getName() + ") for root: " + root;
    }

    @Override
    public String getDisplayString() {
        return root.getDisplayString();
    }
    
    @Override
    public void completeModules(ModuleQuery lookup, ModuleResult result) {
        // check for delegate
        ContentFinder delegate = root.getService(ContentFinder.class);
        if(delegate != null){
            delegate.completeModules(lookup, result);
            return;
        }
        // we NEED the -1 limit here to get empty tokens
        String[] paths = lookup.getName().split("\\.", -1);
        // find the right parent
        Node parent = root;
        for(int i=0;i<paths.length-1;i++){
            parent = parent.getChild(paths[i]);
            // no completion from here
            if(parent == null)
                return;
        }
        String lastPart = paths[paths.length-1];
        // now find a matching child
        for(Node child : parent.getChildren()){
            if(child.getLabel().startsWith(lastPart)
                    && hasChildrenContainingArtifact(child, lookup.getType())){
                Node deepestNode = findDeepestUnambiguousNode(child, lookup.getType());
                String path = toModuleName(deepestNode);
                result.addResult(path);
            }
        }
    }

    private Node findDeepestUnambiguousNode(Node node, Type type) {
        Node childWithArtifacts = null;
        for(Node child : node.getChildren()){
            if(hasChildrenContainingArtifact(child, type)){
                if(childWithArtifacts == null)
                    childWithArtifacts = child;
                else{
                    // we have two children with artifacts so it's ambiguous
                    return node;
                }
            }
            // ignore the child if it has no artifacts
        }
        // if we have only one, find its deepest node
        // FIXME: this is very unefficient and should be done in one pass
        if(childWithArtifacts != null)
            return findDeepestUnambiguousNode(childWithArtifacts, type);
        return node;
    }

    private boolean hasChildrenContainingArtifact(Node node, Type type) {
        // We don't look directly at our children, we want the children's children, because if there's
        // nothing in those children it means either this is an empty folder, or its children contain
        // artifacts (in which case we don't want to match it since its name must be a version component),
        // or we could only find artifacts of the wrong type.
        
        // This allows us to never match the default module, since it's at "/default/default.car" which
        // cannot match this rule. Normal modules always have at least one "/name/version/bla.car".
        for(Node child : node.getChildren()){
            String name = child.getLabel();
            // Winner of the less aptly-named method
            boolean isFolder = !child.hasBinaries();
            if(isFolder && !name.equals(ArtifactContext.DOCS) && containsArtifact(child, type))
                return true;
        }
        // could not find any
        return false;
    }

    private boolean containsArtifact(Node node, Type type) {
        for(Node child : node.getChildren()){
            String name = child.getLabel();
            // Winner of the less aptly-named method
            boolean isFolder = !child.hasBinaries();
            if(isFolder){
                // recurse
                if(!name.equals(ArtifactContext.DOCS) && containsArtifact(child, type))
                    return true;
            }else if(isArtifactOfType(name, type)){
                return true;
            }
        }
        return false;
    }

    // FIXME: this is weak, we should check the full artifact name
    private boolean isArtifactOfType(String name, Type type) {
        switch(type){
        case JS:
            return name.endsWith(ArtifactContext.JS);
        case JVM:
            return name.endsWith(ArtifactContext.CAR)
                    || name.endsWith(ArtifactContext.JAR);
        case SRC:
            return name.endsWith(ArtifactContext.SRC);
        }
        return false;
    }

    /*
     * This method is almost like hasChildrenContainingArtifact but it scans for any type of artifact and records
     * the specific one we want in an out param
     */
    private boolean hasChildrenContainingAnyArtifact(Node node, ModuleQuery query, Ret ret) {
        // We don't look directly at our children, we want the children's children, because if there's
        // nothing in those children it means either this is an empty folder, or its children contain
        // artifacts (in which case we don't want to match it since its name must be a version component),
        // or we could only find artifacts of the wrong type.
        
        // This allows us to never match the default module, since it's at "/default/default.car" which
        // cannot match this rule. Normal modules always have at least one "/name/version/bla.car".
        for(Node child : node.getChildren()){
            String name = child.getLabel();
            // Winner of the less aptly-named method
            boolean isFolder = !child.hasBinaries();
            if(isFolder && !name.equals(ArtifactContext.DOCS) && containsAnyArtifact(child, query, ret))
                return true;
        }
        // could not find any
        return false;
    }

    /*
     * This method is almost like containsArtifact but it scans for any type of artifact and records
     * the specific one we want in an out param. It's also not recursive so it only scans the current children.
     */
    private boolean containsAnyArtifact(Node node, ModuleQuery query, Ret ret) {
        boolean foundArtifact = false;
        for(Node child : node.getChildren()){
            String name = child.getLabel();
            // Winner of the less aptly-named method
            boolean isFolder = !child.hasBinaries();
            if(isFolder){
                // we don't recurse
            }else if(isArtifactOfType(name, query.getType())
                     && matchesSearch(name, child, node, query)){
                // we found what we were looking for
                ret.foundRightType = true;
                return true;
            }else if(isArtifact(name)){
                // we found something, but not the type we wanted
                foundArtifact = true;
            }
        }
        return foundArtifact;
    }

    private boolean matchesSearch(String name, Node artifact, Node versionNode, ModuleQuery query) {
        // match on the module name first
        Node moduleNode = NodeUtils.firstParent(versionNode);
        // can't happen but hey
        if(moduleNode == null)
            return false;
        String moduleName = toModuleName(moduleNode);
        if(moduleName.toLowerCase().contains(query.getName()))
            return true;
        // now search on the metadata
        // this is easy for cars
        if(query.getType() == Type.JVM){
            return matchFromCar(artifact, moduleName, query.getName());
        }
        // for now, for SRC and JS we will fall back to the JVM archive if present, though in the
        // future we might try to load the metadata from the SRC or JS archives
        String carName;
        switch(query.getType()){
        case JS:
            carName = name.substring(0, name.length() - ArtifactContext.JS.length()) + ArtifactContext.CAR;
            break;
        case SRC:
            carName = name.substring(0, name.length() - ArtifactContext.SRC.length()) + ArtifactContext.CAR;
            break;
        default:
            // shouldn't happen
            return false;
        }
        Node carArtifact = versionNode.getChild(carName);
        // did we find it?
        if(carArtifact == null)
            return false;
        // try to match from the car
        return matchFromCar(carArtifact, moduleName, query.getName());
    }

    private boolean matchFromCar(Node artifact, String moduleName, String query) {
        try {
            File file = artifact.getContent(File.class);
            return file != null && BytecodeUtils.matchesModuleInfo(moduleName, file, query); 
        } catch (Exception e) {
            return false;
        }
    }

    // FIXME: this is weak, we should check the full artifact name
    private boolean isArtifact(String name) {
        return name.endsWith(ArtifactContext.JS)
                || name.endsWith(ArtifactContext.CAR)
                || name.endsWith(ArtifactContext.JAR)
                || name.endsWith(ArtifactContext.SRC);
    }

    private String toModuleName(Node node) {
        String path = NodeUtils.getFullPath(node, ".");
        // That's sort of an invariant, but let's be safe
        if(path.startsWith("."))
            path = path.substring(1);
        return path;
    }
    
    @Override
    public void completeVersions(ModuleVersionQuery lookup, ModuleVersionResult result) {
        // check for delegate
        ContentFinder delegate = root.getService(ContentFinder.class);
        if(delegate != null){
            delegate.completeVersions(lookup, result);
            return;
        }
        // FIXME: handle default module
        // FIXME: we should really get this splitting done somewhere in common
        String name = lookup.getName();
        Node namePart = NodeUtils.getNode(root, Arrays.asList(name.split("\\.")));
        if(namePart == null)
            return;
        String[] suffixes = lookup.getType().getSuffixes();
        // now each child is supposed to be a version part, let's verify that
        for(Node child : namePart.getChildren()){
            // Winner of the less aptly-named method
            boolean isFolder = !child.hasBinaries();
            // ignore non-folders
            if(!isFolder)
                continue;
            // now make sure we can find the artifact we're looking for in there
            String version = child.getLabel();
            // optional filter on version
            if(lookup.getVersion() != null && !version.startsWith(lookup.getVersion()))
                continue;
            // avoid duplicates
            if(result.hasVersion(version))
                continue;
            // try every known suffix
            for(String suffix : suffixes){
                String artifactName = getArtifactName(name, version, suffix);
                Node artifact = child.getChild(artifactName);
                if(artifact == null)
                    continue;
                // we found the artifact: let's notify
                ModuleVersionDetails newVersion = result.addVersion(version);
                if(newVersion != null){
                    try {
                        File file = artifact.getContent(File.class);
                        if(file != null)
                            BytecodeUtils.readModuleInfo(name, file, newVersion);
                    } catch (Exception e) {
                        // bah
                    }
                }
                break;
            }
        }
    }

    private static class Ret {
        public boolean foundRightType;
        public long found;
    }
    private static class GetOut extends Exception {}

    @Override
    public void searchModules(ModuleQuery query, ModuleSearchResult result) {
        // check for delegate
        ContentFinder delegate = root.getService(ContentFinder.class);
        if(delegate != null){
            delegate.searchModules(query, result);
            return;
        }
        // do the searching the hard way
        try {
            searchModules(root, query, result, new Ret());
        } catch (GetOut e) {
            // easy out
        }
    }

    private void searchModules(Node parent, ModuleQuery query, ModuleSearchResult result, Ret ret) throws GetOut {
        List<Node> sortedChildren = new ArrayList<Node>();
        for(Node child : parent.getChildren())
            sortedChildren.add(child);
        Collections.sort(sortedChildren, AlphabeticalNodeComparator);
        for(Node child : sortedChildren){
            // Winner of the less aptly-named method
            boolean isFolder = !child.hasBinaries();
            // ignore non-folders
            if(!isFolder)
                continue;
            // is it a module? does it contains artifacts?
            ret.foundRightType = false;
            if(hasChildrenContainingAnyArtifact(child, query, ret)){
                // does it contain an artifact of the type we're looking for?
                if(ret.foundRightType){
                    if(ret.found++ >= query.getStart()){
                        // are we interested in this result or did we need to skip it?
                        String moduleName = toModuleName(child);
                        result.addResult(moduleName);
                        // stop if we're done searching
                        if(ret.found >= query.getStart() + query.getCount())
                            throw new GetOut();
                    }
                }
            }else{
                // it doesn't contain artifacts, it's probably leading to modules
                if(!child.getLabel().equals(ArtifactContext.DOCS)){ // safety check
                    searchModules(child, query, result, ret);
                }
            }
        }
    }
}

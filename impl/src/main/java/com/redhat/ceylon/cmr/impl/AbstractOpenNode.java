/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package com.redhat.ceylon.cmr.impl;

import com.redhat.ceylon.cmr.spi.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Default node impl.
 *
 * @author <a href="mailto:ales.justin@jboss.org">Ales Justin</a>
 */
@SuppressWarnings({"NullableProblems"})
public abstract class AbstractOpenNode implements OpenNode, Serializable {

    private static final long serialVersionUID = 1L;
    private static final String NODE_MARKER = ".marker";   
    
    protected static ContentHandle HANDLE_MARKER = new ContentHandle() {
        public InputStream getContent() throws IOException {
            return null;
        }
        public void clean() {
        }
    };

    private String label;
    private Object value;
    protected final ConcurrentMap<String, OpenNode> parents = new ConcurrentHashMap<String, OpenNode>();
    protected final ConcurrentMap<String, OpenNode> children = new ConcurrentHashMap<String, OpenNode>();

    private transient final Map<Class<?>, Object> services = new WeakHashMap<Class<?>, Object>();

    public AbstractOpenNode() {
        // serialization only
    }

    public AbstractOpenNode(String label, Object value) {
        this.label = label;
        this.value = value;
    }

    protected <T> T findService(Class<T> serviceType) {
        T service = getService(serviceType);
        if (service != null)
            return service;

        for (Node parent : getParents()) {
            if (parent instanceof AbstractOpenNode) {
                AbstractOpenNode dn = (AbstractOpenNode) parent;
                T ps = dn.findService(serviceType);
                if (ps != null) {
                    addService(serviceType, ps);
                    return ps;
                }
            }
        }

        throw new IllegalArgumentException("No such service [" + serviceType + "] found in node chain!");
    }

    protected synchronized <T> T getService(Class<T> serviceType) {
        return serviceType.cast(services.get(serviceType));
    }

    @Override
    public synchronized <T> void addService(Class<T> serviceType, T service) {
        if (serviceType == null)
            throw new IllegalArgumentException("Null service type");

        if (service != null)
            services.put(serviceType, service);
        else
            services.remove(serviceType);
    }

    @Override
    public void link(OpenNode child) {
        if (child == null)
            throw new IllegalArgumentException("Null node!");
        children.put(child.getLabel(), child);
        if (child instanceof AbstractOpenNode) {
            AbstractOpenNode dn = (AbstractOpenNode) child;
            dn.parents.put(getLabel(), this);
        }
    }

    @Override
    public OpenNode addNode(String label) {
        return addNode(label, null);
    }

    @Override
    public String getLabel() {
        return label;
    }

    @Override
    public <T> T getValue(Class<T> valueType) {
        if (valueType == null)
            throw new IllegalArgumentException("Null value type");

        return valueType.cast(value);
    }

    @Override
    public Node getChild(String label) {
        OpenNode child = children.get(label);
        if (child == null) {
            final String markerLabel = label + NODE_MARKER;
            final OpenNode marker = children.get(markerLabel);
            if (marker == null) {
                child = findService(StructureBuilder.class).find(this, label);
                if (child != null) {
                    child = put(children, label, child);
                }
                children.put(markerLabel, new MarkerNode(label, child));
            }
        }
        return child;
    }

    protected OpenNode put(ConcurrentMap<String, OpenNode> map, String label, OpenNode child) {
        final OpenNode previous = map.putIfAbsent(label, child);
        if (previous == null) {
            if (child instanceof AbstractOpenNode) {
                final AbstractOpenNode dn = (AbstractOpenNode) child;
                dn.parents.put(getLabel(), this);
            }
        } else {
            child = previous; // replace
        }
        return child;       
    }
    
    @Override
    public Iterable<? extends Node> getChildren() {
        if (children.isEmpty()) {
            ConcurrentMap<String, OpenNode> tmp = new ConcurrentHashMap<String, OpenNode>();
            for (OpenNode on : findService(StructureBuilder.class).find(this))
                put(tmp, on.getLabel(), on);
            children.putAll(tmp);
            children.put(NODE_MARKER, new MarkerNode());
            return tmp.values();            
        } else {
            List<Node> nodes = new ArrayList<Node>();
            for (Node on : children.values()) {
                if (on instanceof MarkerNode == false)
                    nodes.add(on);
            }
            return nodes;
        }
    }

    @Override
    public void refresh() {
        Iterator<String> iter = children.keySet().iterator();
        while (iter.hasNext()) {
            if (iter.next().endsWith(NODE_MARKER))
                iter.remove();
        }
    }

    @Override
    @SuppressWarnings({"unchecked"})
    public <T> T getContent(Class<T> contentType) throws IOException {
        if (contentType == null)
            throw new IllegalArgumentException("Null content type!");

        if (InputStream.class.equals(contentType)) {
            return (T) getInputStream();
        } else {
            ContentTransformer ct = getService(ContentTransformer.class);
            if (ct != null)
                return ct.transform(contentType, new LazyInputStream());
            else
                return IOUtils.fromStream(contentType, getInputStream());
        }
    }

    @Override
    public Node getParent(String label) {
        return parents.get(label);
    }

    @Override
    public Iterable<? extends Node> getParents() {
        return parents.values();
    }

    @Override
    public int hashCode() {
        return label.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Node == false)
            return false;

        Node dn = (Node) obj;

        if (label.equals(dn.getLabel()) == false)
            return false;

        // check if we have the same parents
        for (Node p : getParents()) {
            for (Node dp : dn.getParents()) {
                if (p.equals(dp))
                    return true; // one is enough to make it true
            }
        }

        return false;
    }

    protected class LazyInputStream extends InputStream {
        private InputStream delegate;

        private InputStream getDelegate() throws IOException {
            if (delegate == null) {
                InputStream is = AbstractOpenNode.this.getInputStream();
                if (is == null)
                    throw new IllegalArgumentException("Null input stream!");
                delegate = is;
            }
            return delegate;
        }

        public int read() throws IOException {
            return getDelegate().read();
        }

        @Override
        public int read(byte[] b) throws IOException {
            return getDelegate().read(b);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return getDelegate().read(b, off, len);
        }

        @Override
        public long skip(long n) throws IOException {
            return getDelegate().skip(n);
        }

        @Override
        public int available() throws IOException {
            return getDelegate().available();
        }

        @Override
        public void mark(int readlimit) {
            try {
                getDelegate().mark(readlimit);
            } catch (IOException ignored) {
            }
        }

        @Override
        public void reset() throws IOException {
            getDelegate().reset();
        }

        @Override
        public boolean markSupported() {
            try {
                return getDelegate().markSupported();
            } catch (IOException ignored) {
                return false;
            }
        }

        public void close() throws IOException {
            if (delegate != null)
                delegate.close();
        }
    }
}

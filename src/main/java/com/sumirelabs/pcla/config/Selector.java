package com.sumirelabs.pcla.config;

import java.util.*;
import java.util.regex.Pattern;

/** Registry ID plus optional state predicate; namespace:* selects an entire mod. */
public record Selector(String id, Map<String, String> properties) {
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:([a-z0-9_./-]+|\\*)");
    private static final Pattern PROPERTY = Pattern.compile("[a-z0-9_.-]+");
    public Selector {
        if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid target ID: " + id);
        properties = Collections.unmodifiableMap(new TreeMap<>(properties));
        if (id.endsWith(":*") && !properties.isEmpty()) throw new IllegalArgumentException("Mod selectors cannot specify block states");
        for (var e : properties.entrySet()) {
            if (!PROPERTY.matcher(e.getKey()).matches() || !PROPERTY.matcher(e.getValue()).matches())
                throw new IllegalArgumentException("Invalid state property: " + e);
        }
    }
    public boolean matches(String registryId, Map<String, String> state) {
        boolean sameId = id.endsWith(":*") ? registryId.startsWith(id.substring(0, id.length() - 1)) : id.equals(registryId);
        return sameId && properties.entrySet().stream().allMatch(e -> e.getValue().equals(state.get(e.getKey())));
    }
    public int specificity() { return id.endsWith(":*") ? -1 : properties.size(); }
    public boolean overlaps(Selector other) {
        if (!id.equals(other.id)) return false;
        for (var e : properties.entrySet()) {
            String value = other.properties.get(e.getKey());
            if (value != null && !value.equals(e.getValue())) return false;
        }
        return true;
    }
    public String canonical() { return id + properties; }
}

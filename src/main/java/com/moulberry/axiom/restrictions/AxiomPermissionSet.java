package com.moulberry.axiom.restrictions;

import java.util.EnumSet;

public class AxiomPermissionSet {

    public final EnumSet<AxiomPermission> explicitlyAllowed;
    public final EnumSet<AxiomPermission> explicitlyDenied;
    private volatile EnumSet<AxiomPermission> calculated = null;

    public static AxiomPermissionSet ALL = new AxiomPermissionSet(EnumSet.of(AxiomPermission.ALL), EnumSet.noneOf(AxiomPermission.class));
    public static AxiomPermissionSet DEFAULT = new AxiomPermissionSet(EnumSet.of(AxiomPermission.DEFAULT), EnumSet.noneOf(AxiomPermission.class));
    public static AxiomPermissionSet NONE = new AxiomPermissionSet(EnumSet.noneOf(AxiomPermission.class), EnumSet.allOf(AxiomPermission.class));

    public AxiomPermissionSet(EnumSet<AxiomPermission> explicitlyAllowed, EnumSet<AxiomPermission> explicitlyDenied) {
        this.explicitlyAllowed = explicitlyAllowed;
        this.explicitlyDenied = explicitlyDenied;
    }

    public boolean contains(AxiomPermission axiomPermission) {
        if (this.calculated == null) {
            this.calculate();
        }
        return this.calculated.contains(axiomPermission);
    }

    public boolean containsAll(EnumSet<AxiomPermission> permissions) {
        if (this.calculated == null) {
            this.calculate();
        }
        return this.calculated.containsAll(permissions);
    }

    public boolean containsAll(AxiomPermissionSet permissionSet) {
        if (this.calculated == null) {
            this.calculate();
        }
        if (permissionSet.calculated == null) {
            permissionSet.calculate();
        }
        return this.calculated.containsAll(permissionSet.calculated);
    }

    public boolean containsAllButIgnore(AxiomPermissionSet permissionSet, EnumSet<AxiomPermission> ignored) {
        if (this.calculated == null) {
            this.calculate();
        }
        if (permissionSet.calculated == null) {
            permissionSet.calculate();
        }
        EnumSet<AxiomPermission> thisCopy = EnumSet.copyOf(this.calculated);
        EnumSet<AxiomPermission> otherCopy = EnumSet.copyOf(permissionSet.calculated);
        thisCopy.removeAll(ignored);
        otherCopy.removeAll(ignored);
        return thisCopy.containsAll(otherCopy);
    }

    private static final AxiomPermission[] PERMISSIONS = AxiomPermission.values();

    private void calculate() {
        // Build into a local and publish once. A RegularEnumSet is a bare long bitmask, so
        // publishing it and then mutating it in place would let another region read a partially
        // built mask (and lose the transitive parent resolution, which reads back the same set).
        EnumSet<AxiomPermission> result;
        if (this.explicitlyAllowed.contains(AxiomPermission.ALL)) {
            result = EnumSet.allOf(AxiomPermission.class);
        } else {
            result = EnumSet.noneOf(AxiomPermission.class);
            for (AxiomPermission value : PERMISSIONS) {
                if (this.explicitlyAllowed.contains(value)) {
                    result.add(value);
                } else if (!this.explicitlyDenied.contains(value) && value.parent != null) {
                    if (result.contains(value.parent)) {
                        result.add(value);
                    }
                }
            }
        }

        this.calculated = result;
    }

}

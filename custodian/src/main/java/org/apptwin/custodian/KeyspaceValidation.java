package org.apptwin.custodian;

import org.apptwin.custodian.contract.CustodianContract;

import java.util.UUID;

final class KeyspaceValidation {
    private KeyspaceValidation() {
    }

    static String requireCanonicalUuid(String value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " is required");
        try {
            String canonical = UUID.fromString(value).toString();
            if (!canonical.equals(value)) {
                throw new IllegalArgumentException(field + " must be canonical");
            }
            return canonical;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(field + " must be a canonical UUID");
        }
    }

    static String requireSupportedPackage(String packageName) {
        if (!CustodianContract.LINE_PACKAGE.equals(packageName)) {
            throw new IllegalArgumentException("Unsupported guest package");
        }
        return packageName;
    }
}

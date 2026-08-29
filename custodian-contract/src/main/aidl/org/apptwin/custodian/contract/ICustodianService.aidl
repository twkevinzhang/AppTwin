package org.apptwin.custodian.contract;

interface ICustodianService {
    int getProtocolVersion();
    int getHealthStatus();
    String registerKeyspace(String spaceId, String packageName);
    String resolveKeyspace(String spaceId, String packageName);
    boolean transferKeyspace(
        String sourceSpaceId,
        String destinationSpaceId,
        String packageName,
        String keyspaceId
    );
    boolean releaseKeyspace(String spaceId, String packageName, String keyspaceId);
}

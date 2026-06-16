package dev.jobhunter.people.dto;

import dev.jobhunter.people.model.enums.ContactDiscoverySource;

public record ContactDiscoveryRunDto(
    String id,
    String companyId,
    String companyName,
    ContactDiscoverySource source,
    int contactsFound,
    int contactsNew,
    String runAt
) {}

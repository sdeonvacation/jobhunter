package dev.jobhunter.strategy.aggregator;

import java.util.Map;

/**
 * Board-neutral listing record produced by {@link UniversityBoardStrategy} subclasses.
 * {@code attributes} carries board-specific listing fields that are not part of the
 * shared model (for wissenschaftsstellen: kategorie, befristung, arbeitszeit_pct,
 * entgeltgruppe, tags, quelle, inst_typ, fachbereich_raw).
 */
public record BoardJob(
    String externalId,
    String title,
    String companyName,
    String location,
    String applyUrl,
    String detailUrl,
    Map<String, Object> attributes
) {}

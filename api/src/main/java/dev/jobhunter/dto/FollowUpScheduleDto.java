package dev.jobhunter.dto;

import java.util.List;

public record FollowUpScheduleDto(
        List<FollowUpDto> followUps,
        int total,
        int overdueCount
) {}

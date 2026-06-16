package dev.jobhunter.people.service;

import dev.jobhunter.linkedin.OutreachContact;
import dev.jobhunter.linkedin.ProfileCache;
import dev.jobhunter.people.dto.ContactScore;
import dev.jobhunter.people.poster.PosterExtractionService;
import dev.jobhunter.repository.ProfileCacheRepository;
import dev.jobhunter.service.PersonalProfile;
import dev.jobhunter.service.PersonalProfileLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContactPriorityScorerTest {

    @Mock
    private PersonalProfileLoader profileLoader;
    @Mock
    private ProfileCacheRepository profileCacheRepository;
    @Mock
    private PosterExtractionService posterExtractionService;

    private ContactPriorityScorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new ContactPriorityScorer(profileLoader, profileCacheRepository, posterExtractionService);
    }

    @Test
    void score_noLinkedinUrl_zeroWarmth() {
        OutreachContact contact = OutreachContact.builder()
                .id(UUID.randomUUID())
                .personName("Test Person")
                .title("Recruiter")
                .linkedinUrl(null)
                .build();

        when(posterExtractionService.inferSeniority("Recruiter"))
                .thenReturn(dev.jobhunter.people.model.enums.Seniority.MID);

        ContactScore score = scorer.score(contact);

        assertThat(score.contactId()).isEqualTo(contact.getId());
        assertThat(score.interviewGenerationWeight()).isEqualTo(55); // MID
        assertThat(score.warmthScore()).isEqualTo(0);
        // composite = 0.6 * 55 + 0.4 * 0 = 33
        assertThat(score.contactPriorityScore()).isEqualTo(33);
    }

    @Test
    void score_withCachedProfile_computesWarmth() {
        UUID contactId = UUID.randomUUID();
        OutreachContact contact = OutreachContact.builder()
                .id(contactId)
                .personName("Senior Person")
                .title("VP Engineering")
                .linkedinUrl("https://linkedin.com/in/senior")
                .build();

        ProfileCache cache = new ProfileCache();
        cache.setProfileData(Map.of(
                "location", "Berlin, Germany",
                "skills", List.of("java", "spring boot", "kotlin"),
                "mutualConnections", 12
        ));

        PersonalProfile profile = mockProfile();

        when(posterExtractionService.inferSeniority("VP Engineering"))
                .thenReturn(dev.jobhunter.people.model.enums.Seniority.VP);
        when(profileCacheRepository.findByLinkedinUrlAndExpiresAtAfter(eq("https://linkedin.com/in/senior"), any()))
                .thenReturn(Optional.of(cache));
        when(profileLoader.getProfile()).thenReturn(profile);

        ContactScore score = scorer.score(contact);

        assertThat(score.interviewGenerationWeight()).isEqualTo(85); // VP
        assertThat(score.warmthScore()).isGreaterThan(0);
        assertThat(score.contactPriorityScore()).isGreaterThan(0);
    }

    @Test
    void score_noCachedProfile_zeroWarmth() {
        OutreachContact contact = OutreachContact.builder()
                .id(UUID.randomUUID())
                .title("Director")
                .linkedinUrl("https://linkedin.com/in/nobody")
                .build();

        when(posterExtractionService.inferSeniority("Director"))
                .thenReturn(dev.jobhunter.people.model.enums.Seniority.SENIOR);
        when(profileCacheRepository.findByLinkedinUrlAndExpiresAtAfter(eq("https://linkedin.com/in/nobody"), any()))
                .thenReturn(Optional.empty());

        ContactScore score = scorer.score(contact);

        assertThat(score.interviewGenerationWeight()).isEqualTo(70); // SENIOR
        assertThat(score.warmthScore()).isEqualTo(0);
        // composite = 0.6 * 70 + 0.4 * 0 = 42
        assertThat(score.contactPriorityScore()).isEqualTo(42);
    }

    @Test
    void scoreBatch_scoresAllContacts() {
        OutreachContact c1 = OutreachContact.builder().id(UUID.randomUUID()).title("Recruiter").build();
        OutreachContact c2 = OutreachContact.builder().id(UUID.randomUUID()).title("VP People").build();

        when(posterExtractionService.inferSeniority("Recruiter"))
                .thenReturn(dev.jobhunter.people.model.enums.Seniority.MID);
        when(posterExtractionService.inferSeniority("VP People"))
                .thenReturn(dev.jobhunter.people.model.enums.Seniority.VP);

        List<ContactScore> scores = scorer.scoreBatch(List.of(c1, c2));

        assertThat(scores).hasSize(2);
        assertThat(scores.get(0).contactId()).isEqualTo(c1.getId());
        assertThat(scores.get(1).contactId()).isEqualTo(c2.getId());
        assertThat(scores.get(1).interviewGenerationWeight())
                .isGreaterThan(scores.get(0).interviewGenerationWeight());
    }

    @Test
    void score_sameCountryMatch_addsWarmthPoints() {
        OutreachContact contact = OutreachContact.builder()
                .id(UUID.randomUUID())
                .title("Engineer")
                .linkedinUrl("https://linkedin.com/in/eng")
                .build();

        ProfileCache cache = new ProfileCache();
        cache.setProfileData(Map.of("location", "Munich, Germany"));

        PersonalProfile profile = mockProfile();

        when(posterExtractionService.inferSeniority("Engineer"))
                .thenReturn(dev.jobhunter.people.model.enums.Seniority.MID);
        when(profileCacheRepository.findByLinkedinUrlAndExpiresAtAfter(any(), any()))
                .thenReturn(Optional.of(cache));
        when(profileLoader.getProfile()).thenReturn(profile);

        ContactScore score = scorer.score(contact);

        // Should get SAME_COUNTRY_WEIGHT (25)
        assertThat(score.warmthScore()).isEqualTo(25);
    }

    @Test
    void score_mutualConnections_addsWarmthPoints() {
        OutreachContact contact = OutreachContact.builder()
                .id(UUID.randomUUID())
                .title("Manager")
                .linkedinUrl("https://linkedin.com/in/mgr")
                .build();

        ProfileCache cache = new ProfileCache();
        cache.setProfileData(Map.of("mutualConnections", 6));

        PersonalProfile profile = mockProfile();

        when(posterExtractionService.inferSeniority("Manager"))
                .thenReturn(dev.jobhunter.people.model.enums.Seniority.MID);
        when(profileCacheRepository.findByLinkedinUrlAndExpiresAtAfter(any(), any()))
                .thenReturn(Optional.of(cache));
        when(profileLoader.getProfile()).thenReturn(profile);

        ContactScore score = scorer.score(contact);

        // 5-9 mutual connections: 15 * 2/3 = 10
        assertThat(score.warmthScore()).isEqualTo(10);
    }

    @SuppressWarnings("unchecked")
    private PersonalProfile mockProfile() {
        PersonalProfile.Preferences prefs = new PersonalProfile.Preferences(
                List.of("Germany", "remote"), "FULL_TIME", 65000, List.of("senior", "mid"), List.of("English", "German"), List.of()
        );
        List<PersonalProfile.ProfileSkill> skills = List.of(
                new PersonalProfile.ProfileSkill("java", "expert", "language"),
                new PersonalProfile.ProfileSkill("spring boot", "expert", "framework"),
                new PersonalProfile.ProfileSkill("kotlin", "proficient", "language")
        );
        return new PersonalProfile(
                "Sam", "Software Engineer", 4, skills, prefs, null, null, null, null
        );
    }
}

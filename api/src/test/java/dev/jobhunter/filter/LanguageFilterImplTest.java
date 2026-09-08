package dev.jobhunter.filter;

import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.service.PersonalProfile;
import dev.jobhunter.service.PersonalProfileLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LanguageFilterImplTest {

    private static LanguageFilterImpl filter;

    @BeforeAll
    static void setUp() {
        // Full config with German detection and standard exclude patterns
        PersonalProfileLoader loader = mock(PersonalProfileLoader.class);
        when(loader.getProfile()).thenReturn(new PersonalProfile(
                "", "", 0, List.of(),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                new PersonalProfile.FilterConfig(
                        null, null, null,
                        new PersonalProfile.LanguageFilterConfig(
                                "en",
                                List.of("german"),
                                0.85,
                                List.of(
                                        "german\\s+c[12]",
                                        "deutsch\\s+c[12]",
                                        "flie[ßs]end\\s+deutsch",
                                        "fluent\\s+german",
                                        "muttersprache",
                                        "native\\s+german",
                                        "german\\s+native",
                                        "verhandlungssicher"
                                ),
                                List.of("nice\\s+to\\s+have", "preferred", "von\\s+vorteil",
                                        "\\bB[12]\\b", "basic\\s+german", "bonus", "optional",
                                        "ideal(ly)?", "advantage")
                        ), null),
                null, null, null));
        filter = new LanguageFilterImpl(loader);
    }

    @Test
    void englishDescription_noGermanRequirement_keep() {
        var result = filter.filter(
                "Backend Engineer",
                "We are looking for a backend engineer with experience in Java and Spring Boot. " +
                        "You will build microservices and work with distributed systems."
        );
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.reason()).isNull();
    }

    @Test
    void germanDescription_skip() {
        var result = filter.filter(
                "Backend Entwickler",
                "Wir suchen einen erfahrenen Backend-Entwickler mit Java-Kenntnissen. " +
                        "Sie werden Microservices entwickeln und mit verteilten Systemen arbeiten. " +
                        "Gute Deutschkenntnisse sind erforderlich."
        );
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.reason()).isEqualTo("non-English JD (German)");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "German C1 required for this role",
            "German C2 level is mandatory",
            "Deutsch C1 erforderlich",
            "fluent German is required",
            "fließend Deutsch required",
            "Muttersprache Deutsch or equivalent",
            "native German speaker required",
            "German native level communication",
            "verhandlungssicher in Deutsch"
    })
    void englishWithStrictGermanRequirement_skip(String requirement) {
        var result = filter.filter(
                "Software Engineer",
                "We are looking for a software engineer. Requirements: 5+ years Java. " + requirement
        );
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.reason()).isEqualTo("non-English language required");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "German B1 is nice to have",
            "nice to have: German C1",
            "preferred: German C2",
            "German von Vorteil",
            "basic German is helpful"
    })
    void softGermanRequirement_keep(String softReq) {
        var result = filter.filter(
                "Software Engineer",
                "We are looking for a software engineer with Java skills. " + softReq +
                        ". Strong English communication required."
        );
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
    }

    @Test
    void nullDescription_keep() {
        var result = filter.filter("Engineer", null);
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
    }

    @Test
    void longEnglishDescription_withHighGermanScore_keep() {
        // Regression: Lingua returns per-language confidence scores, not normalized
        // probabilities. A long English JD can score German >= 0.85 (e.g. EN=1.0, DE=0.84)
        // even though the text is clearly English. Only flag non-English when it beats English.
        var result = filter.filter(
                "Senior Backend + Infrastructure Engineer (Platform)",
                "Data quality is the bottleneck of the AI era. Every Fortune 500 is trying to solve it. " +
                        "We're the team they call. Soda is the data quality layer for Disney, Ralph Lauren, " +
                        "CBRE, HelloFresh, 2K Games, and Nubank. Our open-source engine, Soda Core, is used " +
                        "by companies like Tesla, Slack, Adyen, Walmart, and JPMC. The category is growing " +
                        "fast because AI doesn't work on bad data, and we're hiring a Senior Full-Stack " +
                        "Engineer to build the platform that catches data problems before they become " +
                        "business problems. About Us Soda's mission is to monitor the world's decisions. " +
                        "We're building the first Data & AI performance monitoring platform that spans data " +
                        "collection to automated decision-making. Soda helps customers find, understand, and " +
                        "fix data quality issues by combining automated post-production monitoring with " +
                        "pre-production pipeline data testing. Soda was founded in 2019 by Maarten and Tom. " +
                        "We commercialize through Soda Cloud and we're well-funded, with investors like " +
                        "Point 9 Capital, Hummingbird, Singular, and Eurazeo. The Role You'll join our " +
                        "Platform team and own the full path from backend service to production: the Java " +
                        "services that power Soda Cloud and the AWS/Kubernetes infrastructure they run on. " +
                        "To be clear about the shape of this role: you're a backend engineer first. Most " +
                        "of your time goes into designing and building services. Infrastructure is the part " +
                        "of your craft that lets you own them all the way to production, not a separate job. " +
                        "This is not a DevOps or SRE position. You ship features, and you make sure the " +
                        "platform underneath them scales to billions of datasets. We're product engineers, " +
                        "not ticket-takers: you'll talk to users, shape solutions with your PM, and ship " +
                        "end-to-end. No hand-offs, no waiting. We also build agentically: AI coding agents " +
                        "are part of how the whole team ships, and the platform you own is a big part of " +
                        "what makes them effective. What You'll Do Design and build robust backend services " +
                        "and APIs in Java for a multi-tenant SaaS platform. This is where most of your time " +
                        "goes. Ship product features end-to-end and work directly with product and customers " +
                        "on what to build. Your opinion matters here. Improve the reliability, performance, " +
                        "and scalability of the services you own, and carry them all the way to production " +
                        "on our AWS/Kubernetes platform. Keep the team shipping fast: CI/CD pipelines, " +
                        "GitOps workflows, observability. This is the platform work that multiplies " +
                        "everyone else. Improve the systems that make agentic development work: fast CI " +
                        "feedback, reliable dev environments, and the guardrails that let agents ship " +
                        "safely. Drive architectural decisions, from service design to how the platform " +
                        "underneath should evolve. Hiring Process CV screen, 20-minute phone screen, first " +
                        "technical interview, second technical interview, culture fit. We answer every " +
                        "application, and every candidate hears back within 3-5 days of each step. " +
                        "Requirements You Should Have 5+ years building backend software at a product " +
                        "company: you've designed, built, and owned real production services, not just " +
                        "operated them. Strong Java: APIs, async processing, concurrency, performance " +
                        "tuning. Solid SQL in a backend context: schema design, query optimization, " +
                        "migrations in production. We run MySQL and ClickHouse. Architectural judgment: " +
                        "you understand how API and service design choices shape data flow, query patterns, " +
                        "latency, and failure modes at runtime. You run what you build: your services have " +
                        "lived on Kubernetes and AWS (EKS, IAM, networking, RDS, S3) in production, and " +
                        "you're happy owning that layer for the team. CI/CD, GitOps, and " +
                        "infrastructure-as-code: GitHub Actions, ArgoCD/Flux, Terraform or Pulumi, plus " +
                        "solid scripting. Observability as a habit: metrics, logging, tracing (Prometheus, " +
                        "Grafana, OpenTelemetry), and you actually write tests. Agentic engineering: you " +
                        "ship production code with AI coding agents (Claude Code, Cursor, or similar) in " +
                        "the loop, and you know how to direct them well while keeping the quality bar " +
                        "high. Independence: you don't need hand-holding in a distributed, async team " +
                        "across 12+ countries. Fluent English. Good to Have Multi-tenant SaaS at scale; " +
                        "high-growth startup experience. Python: our data engineering stack runs on it. " +
                        "Regulated-environment experience (ISO27001, SOC2). Experience building agentic " +
                        "workflows into the product: LLM-powered features, tool-using agents, or AI-driven " +
                        "automation shipped to real users. Data quality, data management, data " +
                        "observability, or ML monitoring industry experience. Benefits What We Offer " +
                        "Compensation 85,000-120,000/year plus equity. Fully remote (EU-based), with a " +
                        "Belgian office available if you want one. All the tokens you need, across all " +
                        "frontier models. Real ownership, real impact, no micromanagement. A team of " +
                        "senior engineers who ship, across 12+ countries. Our Values We value freedom with " +
                        "responsibility, transparency, and a growth mindset. We avoid politics, have no " +
                        "tolerance for dishonesty, and value open and direct communication without " +
                        "judgment. We proactively bring up and address potential issues before they become " +
                        "critical, and we overcommunicate frequently. Working at Soda, you will have full " +
                        "autonomy to make impactful product and roadmap decisions. Why you will love to " +
                        "join Soda: be part of a transformation, data quality is having its moment, and " +
                        "we're at the front of it. Ownership from day one: if you see a bottleneck, you " +
                        "remove it. International culture: colleagues in 12+ countries. Work on your own " +
                        "terms: fully remote, flexible hours. What you might not love: rapidly changing " +
                        "priorities, our roadmap can pivot quickly. It's a fast-paced environment with a " +
                        "LOT of work to be done. Flexibility required: you will have to learn new things " +
                        "all the time."
        );
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
    }

    @Test
    void emptyDescription_keep() {
        var result = filter.filter("Engineer", "");
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
    }

    @Test
    void nullTitle_handledGracefully() {
        var result = filter.filter(
                null,
                "We need a Java developer with strong Spring Boot experience and AWS knowledge."
        );
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
    }

    @Test
    void shortTextSkipsDetection_keep() {
        // Under 100 chars — Lingua detection not triggered
        var result = filter.filter("Dev", "Short text.");
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
    }

    @Test
    void filterResult_keepFactory() {
        var result = FilterResult.keep();
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.reason()).isNull();
    }

    @Test
    void filterResult_skipFactory() {
        var result = FilterResult.skip("some reason");
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.reason()).isEqualTo("some reason");
    }

    // --- stripNoise ---

    @Test
    void stripNoise_removesAshbyBracketUrl() {
        // Ashby descriptions embed image refs like [https://app.ashbyhq.com/.../dead.jpeg]
        // The hex UUIDs in those paths trigger false Dutch/German detections.
        String input = "[https://app.ashbyhq.com/api/images/user-content/1246dead-d30e-4c70-a1d3-ef7e5023d542/9ee053d4-9326-4e8b-89e5-15a98c57b52b/clash%20gold%20lovers.jpeg]\n\nWE ARE LOOKING FOR A SENIOR SOFTWARE ENGINEER TO JOIN OUR TEAM.";
        String result = LanguageFilterImpl.stripNoise(input);
        assertThat(result).doesNotContain("ashbyhq.com");
        assertThat(result).doesNotContain("dead");
        assertThat(result).contains("WE ARE LOOKING FOR A SENIOR SOFTWARE ENGINEER");
    }

    @Test
    void stripNoise_removesBareUrl() {
        String input = "Apply at https://jobs.example.com/apply/12345 before the deadline.";
        String result = LanguageFilterImpl.stripNoise(input);
        assertThat(result).doesNotContain("https://");
        assertThat(result).contains("Apply at");
        assertThat(result).contains("before the deadline");
    }

    @Test
    void stripNoise_removesMarkdownImageLink() {
        String input = "![Team photo](https://cdn.example.com/photo-dead-beef.jpg) Join our engineering team.";
        String result = LanguageFilterImpl.stripNoise(input);
        assertThat(result).doesNotContain("cdn.example.com");
        assertThat(result).contains("Join our engineering team");
    }

    @Test
    void stripNoise_preservesPlainText() {
        String input = "We are looking for a senior software engineer to join our team in Berlin.";
        assertThat(LanguageFilterImpl.stripNoise(input)).isEqualTo(input);
    }

    @Test
    void stripNoise_nullSafe() {
        assertThat(LanguageFilterImpl.stripNoise(null)).isNull();
        assertThat(LanguageFilterImpl.stripNoise("")).isEmpty();
    }

    @Test
    void ashbyDescription_withImageUrls_keepEnglishJob() {
        // Simulates a Supercell/Ashby job: image URL at top, English text below.
        // Before fix: Lingua detected "Dutch" from hex UUIDs → false SKIP.
        // After fix: URLs stripped → English detected → KEEP.
        String description = "[https://app.ashbyhq.com/api/images/user-content/1246dead-d30e-4c70-a1d3-ef7e5023d542/bad250f8-206a-41f4-9698-287303b69ce3/keyart%20supercell.png]\n\n"
                + "OUR ENGINE, TITAN, POWERS SOME OF THE HIGHEST-GROSSING MOBILE GAMES IN THE WORLD. "
                + "We are looking for a Senior Programmer to join our Engine team. "
                + "You will design and implement core engine systems, optimize rendering pipelines, "
                + "and work closely with game teams to ensure performance and stability across all Supercell titles. "
                + "We expect strong C++ skills and experience with mobile game development.";
        var result = filter.filter("Senior Programmer, Engine Reliability", description);
        assertThat(result.decision())
                .as("Ashby image URL should not trigger false non-English detection")
                .isEqualTo(FilterDecision.KEEP);
    }
}

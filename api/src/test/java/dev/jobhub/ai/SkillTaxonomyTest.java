package dev.jobhub.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class SkillTaxonomyTest {

    @ParameterizedTest
    @CsvSource({
            "k8s, Kubernetes",
            "K8s, Kubernetes",
            "Kubernetes, Kubernetes",
            "KUBERNETES, Kubernetes",
            "Postgres, PostgreSQL",
            "postgresql, PostgreSQL",
            "GCP, Google Cloud Platform",
            "gcp, Google Cloud Platform",
            "JS, JavaScript",
            "js, JavaScript",
            "TS, TypeScript",
            "React.js, React",
            "reactjs, React",
            "Node, Node.js",
            "nodejs, Node.js",
            "Mongo, MongoDB",
            "mongodb, MongoDB",
            "Docker, Docker",
            "docker, Docker",
            "kafka, Kafka",
            "Apache Kafka, Kafka",
            "AWS, AWS",
            "aws, AWS",
            "RabbitMQ, RabbitMQ",
            "rabbitmq, RabbitMQ",
            "golang, Go",
            "Go, Go",
            "Spring Boot, Spring Boot",
            "springboot, Spring Boot",
            "CI/CD, CI/CD",
            "cicd, CI/CD",
            "graphql, GraphQL",
            "elastic, Elasticsearch",
            "jpa, Hibernate/JPA",
            "hibernate, Hibernate/JPA"
    })
    void shouldNormalizeKnownAliases(String input, String expected) {
        assertThat(SkillTaxonomy.normalize(input)).isEqualTo(expected);
    }

    @Test
    void shouldReturnOriginalForUnknownSkills() {
        assertThat(SkillTaxonomy.normalize("Apache Flink")).isEqualTo("Apache Flink");
        assertThat(SkillTaxonomy.normalize("Snowflake")).isEqualTo("Snowflake");
        assertThat(SkillTaxonomy.normalize("dbt")).isEqualTo("dbt");
    }

    @Test
    void shouldHandleNullAndBlank() {
        assertThat(SkillTaxonomy.normalize(null)).isNull();
        assertThat(SkillTaxonomy.normalize("")).isEqualTo("");
        assertThat(SkillTaxonomy.normalize("   ")).isEqualTo("   ");
    }

    @Test
    void shouldTrimWhitespace() {
        assertThat(SkillTaxonomy.normalize("  k8s  ")).isEqualTo("Kubernetes");
        assertThat(SkillTaxonomy.normalize(" Docker ")).isEqualTo("Docker");
    }

    @Test
    void shouldBeCaseInsensitive() {
        assertThat(SkillTaxonomy.normalize("DOCKER")).isEqualTo("Docker");
        assertThat(SkillTaxonomy.normalize("docker")).isEqualTo("Docker");
        assertThat(SkillTaxonomy.normalize("Docker")).isEqualTo("Docker");
        assertThat(SkillTaxonomy.normalize("KUBERNETES")).isEqualTo("Kubernetes");
    }
}

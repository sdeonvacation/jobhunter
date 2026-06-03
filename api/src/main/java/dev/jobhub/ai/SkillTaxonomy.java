package dev.jobhub.ai;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Normalizes skill names to canonical forms.
 * Handles common aliases and abbreviations.
 */
public final class SkillTaxonomy {

    private static final Map<String, String> ALIASES = new ConcurrentHashMap<>();

    static {
        // Kubernetes
        ALIASES.put("k8s", "Kubernetes");
        ALIASES.put("kube", "Kubernetes");
        ALIASES.put("kubernetes", "Kubernetes");

        // Databases
        ALIASES.put("postgres", "PostgreSQL");
        ALIASES.put("postgresql", "PostgreSQL");
        ALIASES.put("mongo", "MongoDB");
        ALIASES.put("mongodb", "MongoDB");
        ALIASES.put("dynamodb", "DynamoDB");
        ALIASES.put("redis", "Redis");
        ALIASES.put("elasticsearch", "Elasticsearch");
        ALIASES.put("elastic", "Elasticsearch");

        // Cloud
        ALIASES.put("aws", "AWS");
        ALIASES.put("amazon web services", "AWS");
        ALIASES.put("gcp", "Google Cloud Platform");
        ALIASES.put("google cloud", "Google Cloud Platform");
        ALIASES.put("google cloud platform", "Google Cloud Platform");
        ALIASES.put("azure", "Microsoft Azure");

        // Languages
        ALIASES.put("js", "JavaScript");
        ALIASES.put("javascript", "JavaScript");
        ALIASES.put("ts", "TypeScript");
        ALIASES.put("typescript", "TypeScript");
        ALIASES.put("java", "Java");
        ALIASES.put("kotlin", "Kotlin");
        ALIASES.put("python", "Python");
        ALIASES.put("go", "Go");
        ALIASES.put("golang", "Go");
        ALIASES.put("rust", "Rust");

        // Frameworks
        ALIASES.put("react.js", "React");
        ALIASES.put("reactjs", "React");
        ALIASES.put("react", "React");
        ALIASES.put("node", "Node.js");
        ALIASES.put("nodejs", "Node.js");
        ALIASES.put("node.js", "Node.js");
        ALIASES.put("spring boot", "Spring Boot");
        ALIASES.put("springboot", "Spring Boot");
        ALIASES.put("spring", "Spring");
        ALIASES.put("django", "Django");
        ALIASES.put("flask", "Flask");
        ALIASES.put("express", "Express.js");
        ALIASES.put("expressjs", "Express.js");
        ALIASES.put("express.js", "Express.js");

        // Messaging
        ALIASES.put("rabbitmq", "RabbitMQ");
        ALIASES.put("kafka", "Kafka");
        ALIASES.put("apache kafka", "Kafka");

        // Infrastructure
        ALIASES.put("docker", "Docker");
        ALIASES.put("terraform", "Terraform");
        ALIASES.put("ansible", "Ansible");

        // CI/CD
        ALIASES.put("ci/cd", "CI/CD");
        ALIASES.put("cicd", "CI/CD");
        ALIASES.put("jenkins", "Jenkins");
        ALIASES.put("github actions", "GitHub Actions");

        // Other
        ALIASES.put("graphql", "GraphQL");
        ALIASES.put("rest", "REST APIs");
        ALIASES.put("restful", "REST APIs");
        ALIASES.put("microservices", "Microservices");
        ALIASES.put("git", "Git");
        ALIASES.put("sql", "SQL");
        ALIASES.put("nosql", "NoSQL");
        ALIASES.put("jpa", "Hibernate/JPA");
        ALIASES.put("hibernate", "Hibernate/JPA");
        ALIASES.put("gradle", "Gradle");
        ALIASES.put("maven", "Maven");
    }

    private SkillTaxonomy() {
    }

    /**
     * Normalize a raw skill name to its canonical form.
     * Returns the original (trimmed) name if no mapping exists.
     */
    public static String normalize(String rawSkill) {
        if (rawSkill == null || rawSkill.isBlank()) {
            return rawSkill;
        }
        String trimmed = rawSkill.trim();
        String lookup = trimmed.toLowerCase();
        return ALIASES.getOrDefault(lookup, trimmed);
    }
}

package dev.jobhub.resolution;

public interface EndpointResolver {

    ResolutionResultDto resolve(String companyName, String domain);
}

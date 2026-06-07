package dev.jobhunter.resolution;

public interface EndpointResolver {

    ResolutionResultDto resolve(String companyName, String domain);
}

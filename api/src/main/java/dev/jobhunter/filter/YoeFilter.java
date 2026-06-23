package dev.jobhunter.filter;

public interface YoeFilter {
    Integer extractYoe(String description);
    FilterResult filter(Integer yoe);
}

package dev.jobhunter.source;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Configuration
public class SourceConfigAggregator {

    @Bean("allSources")
    @Primary
    public List<SourceConfig> allSources(
            ObjectProvider<SourceConfig> componentSources,
            @Autowired(required = false) @Qualifier("dynamicSources") List<SourceConfig> dynamicSources) {
        var all = new ArrayList<>(componentSources.orderedStream().toList());
        if (dynamicSources != null) {
            all.addAll(dynamicSources);
        }
        return Collections.unmodifiableList(all);
    }
}

package dev.docassist.common.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Topics are declared in code so the stack starts with auto-create disabled on the broker. */
@Configuration
public class KafkaTopicsConfig {

    @Bean
    NewTopic docEvents() {
        return TopicBuilder.name(Topics.DOC_EVENTS).partitions(6).replicas(1).build();
    }

    @Bean
    NewTopic aclEvents() {
        return TopicBuilder.name(Topics.ACL_EVENTS).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic jobRequests() {
        return TopicBuilder.name(Topics.JOB_REQUESTS).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic jobEvents() {
        return TopicBuilder.name(Topics.JOB_EVENTS).partitions(3).replicas(1).build();
    }
}
